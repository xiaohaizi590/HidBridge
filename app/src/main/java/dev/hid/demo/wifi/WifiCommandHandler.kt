package dev.hid.demo.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * RFCOMM 命令处理器（阶段3-5）
 *
 * 负责：
 *  1. 解析 PC 端通过 RFCOMM 发来的 JSON 命令行（wifi_ok / wifi_mismatch / pong / driver_ok 等）
 *  2. 维护 WiFi 桥接状态机（WiFiChecking → WiFiOk → Ready 等）
 *  3. 向 PC 发送命令（wifi_info / start_udp / ping）
 *  4. 心跳：每 2 秒发 ping，保持通道活跃
 *  5. WiFi 同网检测 + 自动发现 PC IP
 *
 * 本类只处理命令协议，真正的通道收发由 [WifiCommandBridge] 负责。
 */
class WifiCommandHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val commandBridge: WifiCommandBridge
) {

    companion object {
        private const val TAG = "WifiCommandHandler"

        /** 心跳间隔（毫秒） */
        private const val PING_INTERVAL_MS = 2000L

        /** UDP 断流回退阈值（毫秒） */
        private const val UDP_FALLBACK_MS = 3000L
    }

    // ---------------- 状态 ----------------

    sealed interface BridgeState {
        data object Idle : BridgeState
        data object WaitingWifi : BridgeState        // 已连 RFCOMM，等待 WiFi 同网确认
        data class WifiOk(val pcIp: String) : BridgeState  // 同网，PC IP 已确认
        data object WifiMismatch : BridgeState      // 不同网
        data object Ready : BridgeState             // UDP 通道就绪
        data class Error(val msg: String) : BridgeState
    }

    private val _state = MutableStateFlow<BridgeState>(BridgeState.Idle)
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    /** PC 端 IP（由 wifi_ok 命令回传确认） */
    private val _pcIp = MutableStateFlow<String?>(null)
    val pcIp: StateFlow<String?> = _pcIp.asStateFlow()

    /** WiFi 同网状态 */
    private val _sameWifi = MutableStateFlow<Boolean?>(null)
    val sameWifi: StateFlow<Boolean?> = _sameWifi.asStateFlow()

    /** 最近一次 PC 心跳时间戳 */
    private val _lastPong = MutableStateFlow(0L)
    val lastPong: StateFlow<Long> = _lastPong.asStateFlow()

    /** 是否启用了 UDP 桥接 */
    private val _udpStarted = MutableStateFlow(false)
    val udpStarted: StateFlow<Boolean> = _udpStarted.asStateFlow()

    private var pingJob: Job? = null
    private var lastUdpPacketTime = 0L

    // ---------------- 初始化 ----------------

    private var lastChannelState: WifiCommandBridge.ChannelState = WifiCommandBridge.ChannelState.Idle

    init {
        commandBridge.onLineReceived = { line -> handleIncoming(line) }

        scope.launch(Dispatchers.IO) {
            commandBridge.channelState.collect { channelState ->
                if (channelState == lastChannelState) return@collect
                lastChannelState = channelState
                when (channelState) {
                    is WifiCommandBridge.ChannelState.Connected -> onChannelConnected()
                    is WifiCommandBridge.ChannelState.Disconnected -> onChannelDisconnected()
                    is WifiCommandBridge.ChannelState.Idle -> onChannelDisconnected()
                    else -> {}
                }
            }
        }
    }

    // ---------------- 通道事件 ----------------

    private fun onChannelConnected() {
        Log.i(TAG, "RFCOMM 已连接，开始 WiFi 同网检测")
        _state.value = BridgeState.WaitingWifi
        _sameWifi.value = null
        _pcIp.value = null
        startPingLoop()
        sendWifiInfo()
    }

    private fun onChannelDisconnected() {
        Log.i(TAG, "RFCOMM 已断开")
        pingJob?.cancel()
        pingJob = null
        _state.value = BridgeState.Idle
        _sameWifi.value = null
        _udpStarted.value = false
    }

    // ---------------- 入站命令解析 ----------------

    private fun handleIncoming(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return

        // 非 JSON 格式的简单命令（pong 等）
        if (trimmed == "pong") {
            _lastPong.value = SystemClock.elapsedRealtime()
            Log.d(TAG, "收到 pong")
            return
        }

        if (!trimmed.startsWith("{")) {
            Log.w(TAG, "未知命令: $trimmed")
            return
        }

        try {
            val json = JSONObject(trimmed)
            val cmd = json.optString("cmd", "")
            when (cmd) {
                "wifi_ok" -> handleWifiOk(json)
                "wifi_mismatch" -> handleWifiMismatch(json)
                "driver_ok" -> handleDriverOk(json)
                "driver_installed_ok" -> handleDriverInstalledOk(json)
                "udp_ready" -> handleUdpReady(json)
                "pong" -> _lastPong.value = SystemClock.elapsedRealtime()
                else -> Log.w(TAG, "未知 JSON 命令: $cmd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析命令失败: $trimmed", e)
        }
    }

    private fun handleWifiOk(json: JSONObject) {
        val pcIp = json.optString("pc_ip", "")
        Log.i(TAG, "WiFi 同网确认: PC IP=$pcIp")
        _sameWifi.value = true
        _pcIp.value = pcIp.ifEmpty { null }
        _state.value = BridgeState.WifiOk(pcIp)
        // 同网确认后，自动请求 PC 检查驱动
        requestDriverCheck()
    }

    private fun handleWifiMismatch(json: JSONObject) {
        val pcSsid = json.optString("my_ssid", "未知")
        val mySsid = getCurrentSsid()
        Log.w(TAG, "WiFi 不同网! 手机=$mySsid PC=$pcSsid")
        _sameWifi.value = false
        _state.value = BridgeState.WifiMismatch
    }

    private fun handleDriverOk(json: JSONObject) {
        Log.i(TAG, "PC 端驱动已就绪")
        _state.value = BridgeState.Ready
        maybeStartUdp()
    }

    private fun handleDriverInstalledOk(json: JSONObject) {
        Log.i(TAG, "PC 端驱动安装完成")
        _state.value = BridgeState.Ready
        maybeStartUdp()
    }

    private fun handleUdpReady(json: JSONObject) {
        Log.i(TAG, "PC 端 UDP 通道就绪，开始发送手柄数据")
        _udpStarted.value = true
        lastUdpPacketTime = SystemClock.elapsedRealtime()
    }

    // ---------------- 出站命令 ----------------

    /** 发送 WiFi 信息给 PC，请求同网检测 */
    private fun sendWifiInfo() {
        val ssid = getCurrentSsid()
        val ip = getLocalIpAddress()
        val json = JSONObject().apply {
            put("cmd", "wifi_info")
            put("ssid", ssid ?: "")
            put("ip", ip ?: "")
        }
        commandBridge.sendLine(json.toString())
        Log.i(TAG, "发送 wifi_info: ssid=$ssid ip=$ip")
    }

    /** 请求 PC 检查/安装驱动 */
    fun requestDriverCheck() {
        val json = JSONObject().apply {
            put("cmd", "install_driver")
        }
        commandBridge.sendLine(json.toString())
        Log.i(TAG, "请求 PC 检查驱动")
    }

    /** 请求启动 UDP 数据通道 */
    fun requestStartUdp() {
        val json = JSONObject().apply {
            put("cmd", "start_udp")
        }
        commandBridge.sendLine(json.toString())
        Log.i(TAG, "请求启动 UDP 数据通道")
    }

    /** 通知 PC 切换到指定回报率 */
    fun sendRateRate(hz: Int) {
        val json = JSONObject().apply {
            put("cmd", "set_rate")
            put("hz", hz)
        }
        commandBridge.sendLine(json.toString())
    }

    /** 心跳：发送 ping */
    private fun sendPing() {
        commandBridge.sendLine("ping")
    }

    private fun maybeStartUdp() {
        requestStartUdp()
    }

    // ---------------- 心跳 ----------------

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch(Dispatchers.IO) {
            while (isActive && commandBridge.isConnected()) {
                sendPing()
                delay(PING_INTERVAL_MS)
            }
        }
    }

    // ---------------- WiFi 信息获取 ----------------

    @Suppress("DEPRECATION")
    private fun getCurrentSsid(): String? {
        return runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            info?.ssid?.removeSurrounding("\"")
        }.getOrNull()
    }

    private fun getLocalIpAddress(): String? {
        return runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val int = wm?.connectionInfo?.ipAddress ?: return null
            "${int and 0xFF}.${int shr 8 and 0xFF}.${int shr 16 and 0xFF}.${int shr 24 and 0xFF}"
        }.getOrNull()
    }

    /** 手动设置 PC IP（用户手动输入时调用） */
    fun setManualPcIp(ip: String) {
        _pcIp.value = ip
        if (_state.value is BridgeState.WaitingWifi || _state.value is BridgeState.WifiMismatch) {
            _state.value = BridgeState.WifiOk(ip)
            _sameWifi.value = true
        }
    }

    /** 收到 UDP 包的时间戳更新，用于断流检测 */
    fun notifyUdpPacketSent() {
        lastUdpPacketTime = SystemClock.elapsedRealtime()
    }

    /** 检查 UDP 是否已断流（超过阈值未发包） */
    fun isUdpStreamAlive(): Boolean {
        if (!_udpStarted.value) return false
        val elapsed = SystemClock.elapsedRealtime() - lastUdpPacketTime
        return elapsed < UDP_FALLBACK_MS
    }

    fun close() {
        pingJob?.cancel()
        pingJob = null
        _state.value = BridgeState.Idle
        _udpStarted.value = false
    }
}
