package dev.hid.demo.wifi

import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * RFCOMM 控制通道（阶段2：通道建立）
 *
 * 手机作为蓝牙 RFCOMM **服务端**（BluetoothServerSocket 监听），
 * 电脑端 receiver.py 作为 RFCOMM 客户端主动连上来。通道建立后，
 * 双方通过**文本行协议**交换命令 / 响应（每行一条，UTF-8 + '\n'）。
 *
 * 阶段3 起的具体命令（wifi_info / install_driver / start_udp / ping）不在本文件，
 * 通过 [onLineReceived] 回调 + [sendLine] 交由上层处理。本文件只负责
 * 把通道建起来、保持连接、收发文本行。
 *
 * 与 HID 的关系：手机同时注册 HID Device（手柄报表）和 RFCOMM Server
 * （命令通道）两个蓝牙 profile，互不冲突。
 */
class WifiCommandBridge(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "WifiCommandBridge"

        /** SPP 标准 UUID，PC 端通过 SDP 服务发现找到此服务 */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val SERVICE_NAME = "HidDemo Command Channel"
    }

    sealed interface ChannelState {
        /** 未启动 */
        data object Idle : ChannelState

        /** 正在监听，等待 PC 连接 */
        data object Listening : ChannelState

        /** PC 已连上，通道就绪 */
        data class Connected(val deviceName: String, val address: String) : ChannelState

        /** 通道断开（可重新等待连接） */
        data object Disconnected : ChannelState
    }

    private val _channelState = MutableStateFlow<ChannelState>(ChannelState.Idle)
    val channelState: StateFlow<ChannelState> = _channelState.asStateFlow()

    /** 最近收到的一行文本（PC 发来的命令 / 应答） */
    private val _lastLine = MutableStateFlow<String?>(null)
    val lastLine: StateFlow<String?> = _lastLine.asStateFlow()

    /** 收到一行文本时的回调（由上层处理具体命令） */
    var onLineReceived: ((String) -> Unit)? = null

    private var acceptJob: Job? = null
    private var ioJob: Job? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var writer: OutputStreamWriter? = null

    /** 开始监听 RFCOMM，等待电脑连接。重复调用无副作用。 */
    fun startListening() {
        if (acceptJob?.isActive == true) return
        _channelState.value = ChannelState.Listening
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val ss = createServerSocket() ?: break
                serverSocket = ss
                try {
                    val client = ss.accept()
                    if (!isActive) {
                        runCatching { client.close() }
                        break
                    }
                    handleClient(client)
                } catch (e: Exception) {
                    Log.d(TAG, "accept 结束: ${e.message}")
                } finally {
                    runCatching { ss.close() }
                    serverSocket = null
                }
                if (!isActive) break
                _channelState.value = ChannelState.Listening
            }
        }
    }

    private fun createServerSocket(): BluetoothServerSocket? {
        return try {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter ?: return null
            adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
        } catch (e: SecurityException) {
            Log.e(TAG, "缺少 BLUETOOTH_CONNECT 权限，无法监听 RFCOMM", e)
            _channelState.value = ChannelState.Idle
            null
        } catch (e: Exception) {
            Log.e(TAG, "listenUsingRfcommWithServiceRecord 失败", e)
            _channelState.value = ChannelState.Idle
            null
        }
    }

    /** 客户端连上后：开 IO 协程读行，保持连接直到断开 */
    private suspend fun handleClient(client: BluetoothSocket) {
        socket = client
        val device = client.remoteDevice
        val name = runCatching { device.name }.getOrElse { "未知设备" }
        _channelState.value = ChannelState.Connected(name, device.address)
        Log.i(TAG, "命令通道已连接: $name (${device.address})")

        ioJob = scope.launch(Dispatchers.IO) {
            val reader = BufferedReader(InputStreamReader(client.inputStream))
            val out = OutputStreamWriter(client.outputStream)
            writer = out
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    _lastLine.value = line
                    Log.d(TAG, "收到: $line")
                    onLineReceived?.invoke(line)
                }
            } catch (e: Exception) {
                Log.d(TAG, "命令通道读取结束: ${e.message}")
            } finally {
                runCatching { out.flush() }
                runCatching { client.close() }
                socket = null
                writer = null
                _channelState.value = ChannelState.Disconnected
            }
        }
    }

    /** 向电脑发送一行命令（UTF-8，以 \n 结尾）。未连接时静默丢弃。 */
    fun sendLine(line: String) {
        val out = writer ?: return
        try {
            out.write(line)
            out.write('\n'.code)
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "发送命令失败", e)
        }
    }

    fun isConnected(): Boolean = _channelState.value is ChannelState.Connected

    /** 停止监听并断开当前连接 */
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        ioJob?.cancel()
        ioJob = null
        runCatching { serverSocket?.close() }
        runCatching { socket?.close() }
        serverSocket = null
        socket = null
        writer = null
        _channelState.value = ChannelState.Idle
    }
}
