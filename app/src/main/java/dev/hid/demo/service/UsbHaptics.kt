package dev.hid.demo.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * 实体拉伸手柄震动（通用 XInput 兼容框架）
 *
 * 原理：手机作为 USB Host，向拉伸手柄的 HID 接口发送 SET_REPORT（Output Report），
 * 写入两个马达字节，让手柄自己震动。数据流：
 *   PC 游戏震动 → UDP/RFCOMM → VibrateManager → [physicalHaptics] → [setVibration]
 *   → controlTransfer(0x21, SET_REPORT) → 手柄马达
 *
 * 识别策略：遍历 USB 设备，找 HID 接口（class=3）且非键盘/鼠标（boot protocol
 * protocol=1/2 的排除，游戏手柄通常 protocol=0），请求权限后尝试打开。
 *
 * 容错：任何一步失败（无手柄 / 无权限 / 被系统独占 / 私有协议）都静默降级，
 * 上层继续走手机震动兜底，震动不丢失。
 *
 * 注意：Android 系统可能已由 InputManager 占用 HID 接口，claimInterface 可能失败，
 * 成功率取决于具体 ROM 与手柄，需真机验证。
 */
class UsbHaptics(private val context: Context) {

    companion object {
        private const val TAG = "UsbHaptics"

        /** USB 权限请求结果广播 */
        private const val ACTION_USB_PERMISSION = "dev.hid.demo.USB_PERMISSION"

        /** HID 类请求：主机→设备（class 请求 + host to device 方向） */
        private const val REQUEST_TYPE_CLASS_HOST_TO_DEVICE = 0x21

        /** bRequest: SET_REPORT */
        private const val REQUEST_SET_REPORT = 0x09

        /** 输出报告类型（Report Type: 1=Input, 2=Output, 3=Feature） */
        private const val REPORT_TYPE_OUTPUT = 0x02

        /** 常见 XInput 兼容手柄 Output Report ID，逐个尝试 */
        private val CANDIDATE_REPORT_IDS = intArrayOf(0x00, 0x05, 0x03, 0x06)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    private var iface: UsbInterface? = null
    private var reportId = -1

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val d = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && d != null) {
                openDevice(d)
            } else {
                Log.w(TAG, "USB 权限被拒绝，实体手柄震动不可用（降级手机震动）")
            }
        }
    }

    /** App 启动时调用：注册权限广播 + 查找手柄 + 请求权限 */
    fun init() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 14+ 强制要求声明导出性；授权结果经本应用 PendingIntent 送达，用 NOT_EXPORTED
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }
        val candidate = findControllerDevice()
        if (candidate != null) {
            requestPermission(candidate)
        } else {
            Log.i(TAG, "未检测到 HID 手柄设备")
        }
    }

    /** 请求 USB 权限（弹窗授权） */
    private fun requestPermission(usbDevice: UsbDevice) {
        if (usbManager.hasPermission(usbDevice)) {
            openDevice(usbDevice)
            return
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
            flags
        )
        usbManager.requestPermission(usbDevice, pendingIntent)
    }

    /**
     * 查找可驱动的 HID 手柄设备：
     * HID 接口（class=3）且非键盘(protocol=1)/鼠标(protocol=2)。
     */
    private fun findControllerDevice(): UsbDevice? {
        val devices = usbManager.deviceList.values
        for (d in devices) {
            for (idx in 0 until d.interfaceCount) {
                val i = d.getInterface(idx)
                if (i.interfaceClass == 3 && i.interfaceProtocol != 1 && i.interfaceProtocol != 2) {
                    Log.i(TAG, "发现 HID 手柄: ${d.deviceName} (VID=0x%04X PID=0x%04X iface=$i)".format(
                        d.vendorId, d.productId
                    ))
                    return d
                }
            }
        }
        return null
    }

    /** 打开设备并声明 HID 接口（被系统占用时失败） */
    private fun openDevice(usbDevice: UsbDevice) {
        val targetIface = (0 until usbDevice.interfaceCount)
            .map { usbDevice.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == 3 && it.interfaceProtocol != 1 && it.interfaceProtocol != 2
            } ?: run {
            Log.w(TAG, "设备无可用 HID 接口: ${usbDevice.deviceName}")
            return
        }
        val conn = runCatching { usbManager.openDevice(usbDevice) }.getOrNull()
        if (conn == null) {
            Log.w(TAG, "openDevice 失败（可能被系统占用），实体手柄震动不可用")
            return
        }
        if (!conn.claimInterface(targetIface, true)) {
            Log.w(TAG, "claimInterface 失败（InputManager 可能已占用），实体手柄震动不可用")
            conn.close()
            return
        }
        device = usbDevice
        iface = targetIface
        connection = conn
        reportId = -1
        Log.i(TAG, "实体手柄震动通道就绪: ${usbDevice.deviceName}")
    }

    /**
     * 发送震动：l = 大马达 0-255，s = 小马达 0-255（l==0 且 s==0 停震）。
     * 逐一遍历常见 Output Report ID，命中后缓存。
     */
    fun setVibration(l: Int, s: Int): Boolean {
        val conn = connection ?: run {
            Log.d(TAG, "setVibration 未执行：USB 通道未建立（无手柄/未授权/claimInterface 失败）")
            return false
        }
        val targetIface = iface ?: return false
        val lByte = l.coerceIn(0, 255).toByte()
        val sByte = s.coerceIn(0, 255).toByte()
        Log.d(TAG, "setVibration l=$l s=$s reportId=$reportId")

        // 已找到可用 Report ID：直接复用
        if (reportId >= 0) {
            return sendReport(conn, targetIface, reportId, buildPayload(reportId, lByte, sByte))
        }
        // 首次：遍历候选 Report ID，找到第一个成功写入的
        for (rid in CANDIDATE_REPORT_IDS) {
            if (sendReport(conn, targetIface, rid, buildPayload(rid, lByte, sByte))) {
                reportId = rid
                Log.i(TAG, "手柄震动 Output Report ID = 0x%02X".format(rid))
                return true
            }
        }
        Log.w(TAG, "所有候选 Report ID 均写入失败，手柄可能不支持标准震动")
        return false
    }

    /**
     * HID 输出报表载荷：报表有 Report ID 时首字节必须是 ID（如 [0x05, l, s]），
     * Report ID = 0（无 ID）时只有数据体 [l, s]。
     */
    private fun buildPayload(rid: Int, l: Byte, s: Byte): ByteArray {
        return if (rid == 0) byteArrayOf(l, s) else byteArrayOf(rid.toByte(), l, s)
    }

    /** 执行一次 SET_REPORT，成功写入返回 true */
    private fun sendReport(conn: UsbDeviceConnection, targetIface: UsbInterface, rid: Int, data: ByteArray): Boolean {
        return try {
            val wValue = (REPORT_TYPE_OUTPUT shl 8) or rid
            val transferred = conn.controlTransfer(
                REQUEST_TYPE_CLASS_HOST_TO_DEVICE,
                REQUEST_SET_REPORT,
                wValue,
                targetIface.id,
                data,
                data.size,
                100
            )
            if (transferred != data.size) {
                Log.d(TAG, "SET_REPORT rid=0x%02X 写入长度不符: sent=$transferred expect=${data.size}".format(rid))
                return false
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "SET_REPORT 异常 reportId=0x%02X".format(rid), e)
            false
        }
    }

    /** 释放 USB 资源（App 退出时调用） */
    fun close() {
        runCatching { context.unregisterReceiver(usbPermissionReceiver) }
        connection?.let {
            iface?.let { f -> runCatching { it.releaseInterface(f) } }
            it.close()
        }
        connection = null
        iface = null
        device = null
        reportId = -1
    }
}
