package dev.hid.demo.input

import android.os.SystemClock
import android.util.Log
import dev.hid.demo.bluetooth.BluetoothKeyboardManager

/**
 * 手机虚拟手柄输入源（屏幕虚拟摇杆 / 虚拟按键）
 *
 * 与 [InputBridge] 同构：把屏幕上的虚拟手柄状态组装成 [InputBridge.GamepadSnapshot]，
 * 通过蓝牙 HID（[BluetoothKeyboardManager.sendGamepadReport]）+ WiFi UDP（[UdpBridge.update]）
 * 双通道转发给电脑，协议与实体手柄完全一致、零改动。
 *
 * 按键位定义与 [InputBridge] 完全一致（18 bit 位图）：
 *   bit0=A bit1=B bit2=X bit3=Y bit4=LB bit5=RB bit6=LT bit7=RT
 *   bit8=Select bit9=Start bit10=L3 bit11=R3
 *   bit12=DPad↑ bit13=DPad↓ bit14=DPad← bit15=DPad→
 *
 * 虚拟手柄走"绝对状态快照"：摇杆 / 按键只维护最新状态，发送端自带节流与去重，
 * 高频拖动时不会在发送路径上积压。
 */
class VirtualGamepad(private val btManager: BluetoothKeyboardManager) {

    companion object {
        private const val TAG = "VirtualGamepad"

        /** 最小上报间隔（毫秒）。摇杆拖动是高频事件，需要节流防止无意义地灌满发送队列 */
        private const val MIN_SEND_INTERVAL_MS = 8L

        // 按键位（与 InputBridge 位图一致）
        const val BIT_A = 0
        const val BIT_B = 1
        const val BIT_X = 2
        const val BIT_Y = 3
        const val BIT_LB = 4
        const val BIT_RB = 5
        const val BIT_LT = 6
        const val BIT_RT = 7
        const val BIT_SELECT = 8
        const val BIT_START = 9
        const val BIT_L3 = 10
        const val BIT_R3 = 11
        const val BIT_DPAD_UP = 12
        const val BIT_DPAD_DOWN = 13
        const val BIT_DPAD_LEFT = 14
        const val BIT_DPAD_RIGHT = 15
        // bit16=C（XBOX/PS 键）、bit17=Z（SHARE 键）：HID 位图原生支持，虚拟手柄可映射
        const val BIT_C = 16
        const val BIT_Z = 17
    }

    /** 左 / 右摇杆标识 */
    enum class Stick { LEFT, RIGHT }

    /** WiFi 桥接出口（可选）：最新快照同步给它，由它通过 UDP 转发到电脑 */
    @Volatile
    var udpBridge: UdpBridge? = null

    @Volatile
    private var buttonMask = 0

    @Volatile
    private var leftX = 0f
    @Volatile
    private var leftY = 0f
    @Volatile
    private var rightX = 0f
    @Volatile
    private var rightY = 0f

    /** 上次实际发送的快照（用于去重，NaN 保证首次必然发送） */
    private var lastSent = InputBridge.GamepadSnapshot(Int.MIN_VALUE, Float.NaN, Float.NaN, Float.NaN, Float.NaN)
    private var lastSendAt = 0L

    /** 虚拟按键按下 / 抬起（按住保持，抬起清位） */
    fun setButton(bit: Int, pressed: Boolean) {
        val before = buttonMask
        buttonMask = if (pressed) before or (1 shl bit) else before and (1 shl bit).inv()
        if (buttonMask != before) {
            Log.d(TAG, "setButton bit=$bit pressed=$pressed mask=0x${String.format("%06X", buttonMask)}")
            send()
        }
    }

    /** 虚拟摇杆拖动：x / y ∈ -1..1，松手归零时传 (0f, 0f) */
    fun setStick(stick: Stick, x: Float, y: Float) {
        val cx = x.coerceIn(-1f, 1f)
        val cy = y.coerceIn(-1f, 1f)
        when (stick) {
            Stick.LEFT -> {
                leftX = cx
                leftY = cy
            }
            Stick.RIGHT -> {
                rightX = cx
                rightY = cy
            }
        }
        send()
    }

    /** 清空所有按键与摇杆（退出界面 / 页面暂停时调用，避免电脑端卡键） */
    fun releaseAll() {
        buttonMask = 0
        leftX = 0f
        leftY = 0f
        rightX = 0f
        rightY = 0f
        send(force = true)
        Log.d(TAG, "releaseAll() 清空所有按键与摇杆")
    }

    /** 组装并发送手柄报表；默认带节流与去重，force=true 时强制发送 */
    private fun send(force: Boolean = false) {
        val snapshot = InputBridge.GamepadSnapshot(
            buttonMask = buttonMask,
            leftX = leftX,
            leftY = leftY,
            rightX = rightX,
            rightY = rightY
        )
        if (!force && snapshot == lastSent) return
        // 回中复位状态（无按键 + 四轴归零）必须立即发送，否则节流会吞掉
        // 松手回中帧，导致电脑端摇杆卡在最后一个非零位置
        val isReset = snapshot.buttonMask == 0 &&
            snapshot.leftX == 0f && snapshot.leftY == 0f &&
            snapshot.rightX == 0f && snapshot.rightY == 0f
        val now = SystemClock.uptimeMillis()
        if (!force && !isReset && now - lastSendAt < MIN_SEND_INTERVAL_MS) return
        lastSendAt = now
        lastSent = snapshot
        // 双通道：蓝牙 HID 主通道 + WiFi UDP 桥接通道（与 InputBridge 行为一致）
        udpBridge?.update(snapshot)
        btManager.sendGamepadReport(
            snapshot.buttonMask,
            snapshot.leftX,
            snapshot.leftY,
            snapshot.rightX,
            snapshot.rightY
        )
    }
}
