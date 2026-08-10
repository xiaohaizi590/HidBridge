package dev.hid.demo.input

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WiFi 桥接（UDP 发送端）：把 [InputBridge] 的手柄状态通过局域网 UDP 发给电脑端接收程序，
 * 电脑端用 ViGEmBus 虚拟成 Xbox 360 手柄（真 XInput，可绕开蓝牙 HID 的 125Hz / DirectInput 限制）。
 *
 * 包格式（24 字节，小端）：
 *   offset 0 : seq      uint32  帧序号（电脑端用于丢包检测 / 超时判断）
 *   offset 4 : mask     uint32  18 位按键位图（与 InputBridge 位图一致）
 *   offset 8 : leftX    float32 左摇杆 X（-1..1）
 *   offset 12: leftY    float32 左摇杆 Y（-1..1）
 *   offset 16: rightX   float32 右摇杆 X（-1..1）
 *   offset 20: rightY   float32 右摇杆 Y（-1..1）
 *
 * 发送策略：单线程循环持续发送最新快照（默认 4ms ≈ 250Hz）。UDP 无重传，
 * 丢一帧很快被下一帧覆盖（绝对状态快照，无害）。启用时持有 WifiLock，
 * 防止系统 Wi-Fi 休眠导致断流。
 *
 * 链路确认（测试用）：电脑端收到数据后回一个 4 字节 ACK（小端 uint32 = 收到的 seq），
 * 手机端在同一 socket 上接收并显示"电脑已确认"，用于验证 UDP 双向链路是否打通。
 */
class UdpBridge(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "UdpBridge"

        /** 电脑端接收程序监听端口 */
        const val DEFAULT_PORT = 47808

        /** 电脑端 IP 发现端口（UDP 广播） */
        const val DISCOVERY_PORT = 47809

        /** 回报率 → 发送间隔映射 */
        val RATE_TO_INTERVAL = mapOf(125 to 8L, 250 to 4L, 500 to 2L, 750 to 1L)

        /** 超过该时间未收到电脑 ACK，判定链路确认丢失 */
        private const val ACK_TIMEOUT_MS = 2000L

        /** 开启 WiFi 桥接后，超过该时间仍未收到电脑 ACK，触发离线提醒（推送安装包询问） */
        private const val OFFLINE_CHECK_DELAY_MS = 3000L

        /** IP 发现超时（毫秒） */
        private const val DISCOVERY_TIMEOUT_MS = 3000
    }

    /** 当前发送间隔（毫秒），默认 250Hz = 4ms */
    @Volatile
    private var sendIntervalMs = 4L

    private val latest = AtomicReference(InputBridge.GamepadSnapshot(0, 0f, 0f, 0f, 0f))
    private val seq = AtomicInteger(0)
    private val frames = AtomicLong(0)
    private val socket = AtomicReference<DatagramSocket?>()
    private val target = AtomicReference<InetAddress?>()

    /** 最近收到的电脑确认 seq / 时间戳（elapsedRealtime） */
    private val ackSeq = AtomicLong(-1)
    private val ackTime = AtomicLong(0)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _status = MutableStateFlow("WiFi 桥接未启用")
    val status: StateFlow<String> = _status.asStateFlow()

    /** 电脑确认状态（链路测试）："电脑已确认（seq=..）" / "等待电脑确认…" */
    private val _ackStatus = MutableStateFlow("等待电脑确认…")
    val ackStatus: StateFlow<String> = _ackStatus.asStateFlow()

    private var sendJob: Job? = null
    private var ackJob: Job? = null
    private var offlineCheckJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * 电脑离线回调：开启 WiFi 桥接 [OFFLINE_CHECK_DELAY_MS] 毫秒后仍收不到电脑 ACK 时触发一次。
     * 由 UI 在 Compose 侧注册（回调在主线程执行，可直接更新界面状态）。
     */
    var onComputerOffline: (() -> Unit)? = null

    /** 由 InputBridge.send() 调用：更新待发送的最新快照 */
    fun update(snapshot: InputBridge.GamepadSnapshot) {
        latest.set(snapshot)
    }

    /**
     * UDP 广播发现 PC IP
     * 发送 DISC 广播到 [DISCOVERY_PORT] → 等待 PC 回复 JSON 响应
     * 返回发现的 IP 列表，超时或失败返回空列表
     */
    suspend fun discoverPcIps(): List<String> = withContext(Dispatchers.IO) {
        val discoveredIps = mutableListOf<String>()
        val socket = DatagramSocket().apply {
            broadcast = true
            soTimeout = DISCOVERY_TIMEOUT_MS
        }

        try {
            // 发 DISC 广播
            val request = "DISC".toByteArray()
            val broadcastAddr = getBroadcastAddress()
            val sendPacket = DatagramPacket(request, request.size, broadcastAddr, DISCOVERY_PORT)
            socket.send(sendPacket)
            Log.i(TAG, "发送 DISCOVERY 广播到 $broadcastAddr:$DISCOVERY_PORT")

            // 收 PC 回复（可能多个 PC 都响应）
            val buf = ByteArray(512)
            val recvPacket = DatagramPacket(buf, buf.size)

            try {
                while (true) {
                    socket.receive(recvPacket)
                    val response = String(recvPacket.data, 0, recvPacket.length)
                    val senderIp = recvPacket.address.hostAddress
                    Log.i(TAG, "收到 DISCOVERY 回复: $response from $senderIp")

                    // 解析 JSON: {"ips":["192.168.x.x","..."],"port":47808}
                    // 也可能只有 senderIp（简单回复）
                    val jsonIps = parseIpsFromJson(response)
                    if (jsonIps.isNotEmpty()) {
                        discoveredIps.addAll(jsonIps)
                    } else if (senderIp != null && senderIp !in discoveredIps) {
                        discoveredIps.add(senderIp)
                    }
                }
            } catch (_: java.net.SocketTimeoutException) {
                Log.d(TAG, "DISCOVERY 超时，共发现 ${discoveredIps.size} 台 PC")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DISCOVERY 异常: ${e.message}", e)
        } finally {
            socket.close()
        }

        discoveredIps
    }

    /** 从 JSON 响应中解析 IP 列表 */
    private fun parseIpsFromJson(json: String): List<String> {
        return try {
            val obj = org.json.JSONObject(json)
            val ipsArray = obj.optJSONArray("ips") ?: return emptyList()
            (0 until ipsArray.length()).mapNotNull { ipsArray.optString(it).ifBlank { null } }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 计算 WiFi 广播地址（255.255.255.255 或定向广播） */
    @Suppress("DEPRECATION")
    private fun getBroadcastAddress(): InetAddress {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp = wm.dhcpInfo
        // 定向广播：适合同子网
        val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
        val bytes = byteArrayOf(
            (broadcast and 0xFF).toByte(),
            (broadcast shr 8 and 0xFF).toByte(),
            (broadcast shr 16 and 0xFF).toByte(),
            (broadcast shr 24 and 0xFF).toByte()
        )
        return InetAddress.getByAddress(bytes)
    }

    /** 启用 / 停用 WiFi 桥接。启用时校验并设置电脑 IP；已启用状态下改 IP 会即时生效。 */
    fun setEnabled(enabled: Boolean, host: String, port: Int = DEFAULT_PORT) {
        if (enabled) {
            val inet = runCatching { InetAddress.getByName(host.trim()) }.getOrNull()
            if (inet == null) {
                _status.value = "电脑 IP 无效：$host"
                Log.w(TAG, "无效的电脑 IP: $host")
                return
            }
            target.set(inet)
            start(port)
        } else {
            stop()
        }
    }

    fun isEnabled(): Boolean = _enabled.value

    /** 设置回报率（Hz）：125/250/500/750。已启用时即时生效。 */
    fun setRate(hz: Int) {
        val interval = RATE_TO_INTERVAL[hz] ?: return
        sendIntervalMs = interval
        Log.d(TAG, "回报率设置为 ${hz}Hz (间隔 ${interval}ms)")
        if (_enabled.value) {
            val host = target.get()?.hostAddress ?: "?"
            _status.value = "WiFi 桥接运行中 → $host:$DEFAULT_PORT（${hz}Hz，已发 ${frames.get()} 帧）"
        }
    }

    private fun start(port: Int) {
        if (_enabled.value) {
            // 已启用：仅更新状态文本（目标地址已在 setEnabled 中更新）
            _status.value = "WiFi 桥接运行中 → ${target.get()?.hostAddress}:$port（250Hz）"
            return
        }
        val sock = runCatching { DatagramSocket() }.getOrNull()
        if (sock == null) {
            _status.value = "创建 UDP Socket 失败"
            Log.e(TAG, "DatagramSocket() 创建失败")
            return
        }
        _enabled.value = true
        socket.set(sock)
        acquireWifiLock()
        startAckReceiver(sock)
        startOfflineCheck()
        val host = target.get()?.hostAddress ?: "?"
        _status.value = "WiFi 桥接已启用 → $host:$port（${RATE_TO_INTERVAL.entries.first { it.value == sendIntervalMs }.key}Hz）"
        sendJob = scope.launch(Dispatchers.IO) {
            var lastStatusUpdate = 0L
            while (isActive) {
                sendLatest(port)
                val now = SystemClock.uptimeMillis()
                if (now - lastStatusUpdate >= 1000) {
                    lastStatusUpdate = now
                    refreshAckStatus()
                    _status.value = "WiFi 桥接运行中 → ${target.get()?.hostAddress}:$port（${currentRate()}Hz，已发 ${frames.get()} 帧）"
                }
                delay(sendIntervalMs)
            }
        }
    }

    /** 在同一 socket 上开启接收协程，等待电脑回 ACK（4 字节小端 seq） */
    private fun startAckReceiver(sock: DatagramSocket) {
        ackJob?.cancel()
        ackJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(16)
            while (isActive) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(packet)
                    if (packet.length >= 4) {
                        val ack = ByteBuffer.wrap(packet.data, packet.offset, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .int
                        ackSeq.set(ack.toLong())
                        ackTime.set(SystemClock.elapsedRealtime())
                        Log.d(TAG, "收到电脑确认: seq=$ack")
                    }
                } catch (e: Exception) {
                    if (isActive) Log.d(TAG, "ACK 接收结束: ${e.message}")
                }
            }
        }
    }

    /** 开启后延迟检测：若期间没收到过电脑 ACK，判定电脑未运行接收程序，触发离线回调一次 */
    private fun startOfflineCheck() {
        offlineCheckJob?.cancel()
        val startTime = SystemClock.elapsedRealtime()
        offlineCheckJob = scope.launch(Dispatchers.IO) {
            delay(OFFLINE_CHECK_DELAY_MS)
            if (!isActive || !_enabled.value) return@launch
            // 开启后才收到的 ACK 才算数（ackTime 在 start 前可能残留旧值）
            if (ackTime.get() < startTime) {
                Log.w(TAG, "开启 WiFi 桥接后 ${OFFLINE_CHECK_DELAY_MS}ms 未收到电脑回包，电脑可能未运行 GamepadBridge")
                val cb = onComputerOffline
                if (cb != null) {
                    scope.launch(Dispatchers.Main) { cb() }
                }
            }
        }
    }

    /** 根据最近 ACK 时间刷新确认状态文本 */
    private fun refreshAckStatus() {
        val ack = ackSeq.get()
        if (ack < 0) {
            _ackStatus.value = "等待电脑确认…"
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - ackTime.get()
        _ackStatus.value = if (elapsed <= ACK_TIMEOUT_MS) {
            "电脑已确认：seq=$ack（链路通）"
        } else {
            "电脑确认丢失：最近确认 seq=$ack"
        }
    }

    private fun stop() {
        if (!_enabled.value) return
        _enabled.value = false
        sendJob?.cancel()
        sendJob = null
        ackJob?.cancel()
        ackJob = null
        offlineCheckJob?.cancel()
        offlineCheckJob = null
        socket.getAndSet(null)?.close()
        releaseWifiLock()
        _status.value = "WiFi 桥接已停用"
        _ackStatus.value = "等待电脑确认…"
    }

    private fun sendLatest(port: Int) {
        val sock = socket.get() ?: return
        val host = target.get() ?: return
        val s = latest.get()
        val buffer = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(seq.incrementAndGet())
        buffer.putInt(s.buttonMask)
        buffer.putFloat(s.leftX)
        buffer.putFloat(s.leftY)
        buffer.putFloat(s.rightX)
        buffer.putFloat(s.rightY)
        val bytes = buffer.array()
        try {
            sock.send(DatagramPacket(bytes, bytes.size, host, port))
            frames.incrementAndGet()
        } catch (e: Exception) {
            Log.e(TAG, "UDP 发送失败", e)
        }
    }

    /** 持有 WifiLock，防止发送期间系统关闭 Wi-Fi 射频 */
    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock != null) return
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "UdpBridgeWifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.let {
            runCatching { it.release() }
        }
        wifiLock = null
    }

    /** 当前回报率（Hz） */
    private fun currentRate(): Int = RATE_TO_INTERVAL.entries.firstOrNull { it.value == sendIntervalMs }?.key ?: 250

    fun close() {
        stop()
    }
}
