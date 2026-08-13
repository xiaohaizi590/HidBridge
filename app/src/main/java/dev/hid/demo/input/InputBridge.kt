package dev.hid.demo.input

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.hid.demo.bluetooth.BluetoothKeyboardManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 外部手柄输入桥（USB-C 拉伸手柄 / OTG 手柄）
 *
 * 作用：在 Activity 的 [android.app.Activity.dispatchKeyEvent] /
 * [android.app.Activity.dispatchGenericMotionEvent] 中截获 Android 物理手柄事件，
 * 映射为 HID 手柄报表（Report ID 3）后，通过 [BluetoothKeyboardManager.sendGamepadReport]
 * 转发给已连接的蓝牙主机。
 *
 * 注意事项：
 * 1. 外部手柄**必须走 USB-C / OTG**。蓝牙拉伸手柄不可行——手机处于 HID Device 角色，
 *    蓝牙射频同一通道无法同时作为 HID Host 去连接手柄。
 * 2. 只处理 SOURCE_GAMEPAD / SOURCE_JOYSTICK 来源的事件（即物理手柄），
 *    鼠标、键盘等其它输入设备的事件一律不拦截、不映射。
 * 3. 扳机（LT/RT）按现有 HID 描述符设计映射为数字按键位（bit6/bit7），摇杆为模拟轴。
 * 4. 右摇杆轴来源按 deviceId 缓存解析一次（优先 AXIS_RX/RY，兼容 DirectInput 手柄的
 *    AXIS_Z/RZ），不做逐事件动态判断——设备瞬断时 `InputDevice.getDevice()` 返回 null，
 *    动态判断会把 XInput 手柄的扳机轴（AXIS_Z/RZ）误读成右摇杆、或把摇杆误清零，
 *    导致两个摇杆同方向推满时偶发"漂移"。
 *
 * 按键位图（18 bit，与 hidDescriptor 中 Button 1..18 对应）：
 *   bit0=A  bit1=B  bit2=X  bit3=Y  bit4=LB  bit5=RB  bit6=LT  bit7=RT
 *   bit8=Select  bit9=Start  bit10=L3  bit11=R3
 *   bit12=DPad↑  bit13=DPad↓  bit14=DPad←  bit15=DPad→  bit16=C  bit17=Z
 */
class InputBridge(private val btManager: BluetoothKeyboardManager) {

    companion object {
        private const val TAG = "InputBridge"

        const val RATE_125 = 125  // 蓝牙默认
        const val RATE_250 = 250
        const val RATE_500 = 500  // WiFi 默认
        const val RATE_750 = 750
        const val RATE_1000 = 1000

        val RATE_TO_INTERVAL = mapOf(125 to 8L, 250 to 4L, 500 to 2L, 750 to 1L, 1000 to 1L)
    }

    /** WiFi 桥接出口（可选）：最新快照会同步给它，由它通过 UDP 转发到电脑 */
    @Volatile
    var udpBridge: UdpBridge? = null

    /** UI 展示用的实时快照 */
    data class GamepadSnapshot(
        val buttonMask: Int,
        val leftX: Float,
        val leftY: Float,
        val rightX: Float,
        val rightY: Float
    )

    @Volatile
    private var enabled = false

    val isEnabled: Boolean get() = enabled

    private val _state = MutableStateFlow(GamepadSnapshot(0, 0f, 0f, 0f, 0f))
    val state: StateFlow<GamepadSnapshot> = _state.asStateFlow()

    private val _controllerName = MutableStateFlow<String?>(null)
    val controllerName: StateFlow<String?> = _controllerName.asStateFlow()

    private val buttonMask = AtomicInteger(0)

    @Volatile private var leftX = 0f
    @Volatile private var leftY = 0f
    @Volatile private var rightX = 0f
    @Volatile private var rightY = 0f

    private var lastDeviceId = -1

    /** 摇杆死区：低于该值视为 0，避免摇杆中心抖动 */
    private val deadZone = 0.15f

    /** 最小上报间隔（毫秒），可通过 [setRate] 调整 */
    @Volatile
    private var minSendIntervalMs = 8L

    /** 当前回报率（Hz），可通过 [setRate] 获取 */
    @Volatile
    private var currentRateHz = 125

    /** 上次实际发送的快照（用于去重，NaN 保证首次必然发送） */
    private var lastSent = GamepadSnapshot(Int.MIN_VALUE, Float.NaN, Float.NaN, Float.NaN, Float.NaN)
    private var lastSendAt = 0L

    fun setEnabled(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        Log.d(TAG, "setEnabled=$enabled (controller=${_controllerName.value ?: "N/A"})")
        if (!enabled) {
            releaseAll()
        }
    }

    /** 设置回报率（Hz）：125/250/500/750/1000。即时生效。 */
    fun setRate(hz: Int) {
        val interval = RATE_TO_INTERVAL[hz] ?: return
        minSendIntervalMs = interval
        currentRateHz = hz
        Log.d(TAG, "回报率设置为 ${hz}Hz (间隔 ${interval}ms)")
    }

    /** 获取当前回报率（Hz） */
    fun getCurrentRate(): Int = currentRateHz

    /** 处理按键事件。返回 true 表示已消费（不再交给 UI / 系统）。 */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!enabled) return false
        if (!isGamepadSource(event.source)) return false

        val bit = keyCodeToButtonBit(event.keyCode)
        if (bit < 0) {
            Log.d(TAG, "忽略未映射的手柄按键 keyCode=${event.keyCode} action=${event.action}")
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // 长按的重复事件（repeatCount > 0）状态不变，仅消费
                if (event.repeatCount == 0) {
                    buttonMask.set(buttonMask.get() or (1 shl bit))
                }
            }
            KeyEvent.ACTION_UP -> {
                // 窗口失焦被系统取消的按键（FLAG_CANCELED）同样以 ACTION_UP 结束，一并清除
                buttonMask.set(buttonMask.get() and (1 shl bit).inv())
            }
            else -> return false
        }
        Log.d(
            TAG,
            "keyEvent action=${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"} " +
                "keyCode=${event.keyCode} bit=$bit mask=0x${String.format("%06X", buttonMask.get())}"
        )
        updateControllerName(event.deviceId)
        send()
        return true
    }

    /** 处理摇杆 / 扳机等模拟事件。返回 true 表示已消费。 */
    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (!enabled) return false
        if (!isGamepadSource(event.source)) return false

        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                // 右摇杆轴模式：按 deviceId 缓存，只在设备重连时重新解析。
                // 解析失败（设备暂不可用且无缓存）时直接跳过本事件——
                // 避免把 XInput 手柄的扳机轴(Z/RZ)误读成右摇杆，或把摇杆误清零，造成漂移
                val rightStick = resolveRightStickSource(event.deviceId) ?: return false
                leftX = axisValue(event, MotionEvent.AXIS_X)
                leftY = axisValue(event, MotionEvent.AXIS_Y)
                if (rightStick == RightStickSource.RX_RY) {
                    rightX = axisValue(event, MotionEvent.AXIS_RX)
                    rightY = axisValue(event, MotionEvent.AXIS_RY)
                } else {
                    // 兼容只上报 Z / RZ 的旧式手柄（DirectInput 右摇杆）
                    rightX = axisValue(event, MotionEvent.AXIS_Z)
                    rightY = axisValue(event, MotionEvent.AXIS_RZ)
                }
                // 部分手柄的 D-pad 只通过 HAT 轴上报（无 SOURCE_DPAD 时不产生 KEYCODE_DPAD_*）
                val device = InputDevice.getDevice(event.deviceId)
                if (device != null &&
                    (device.getMotionRange(MotionEvent.AXIS_HAT_X) != null ||
                        device.getMotionRange(MotionEvent.AXIS_HAT_Y) != null)
                ) {
                    if (!device.supportsSource(InputDevice.SOURCE_DPAD)) {
                        updateDpadFromHat(
                            event.getAxisValue(MotionEvent.AXIS_HAT_X),
                            event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                        )
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                Log.d(TAG, "motionEvent ACTION_CANCEL，摇杆清零")
                leftX = 0f
                leftY = 0f
                rightX = 0f
                rightY = 0f
            }
            else -> return false
        }
        updateControllerName(event.deviceId)
        send()
        return true
    }

    /** 手柄拔出 / 桥接关闭 / 页面暂停时调用，清空所有按键与摇杆，避免电脑端“卡键”。 */
    fun releaseAll() {
        Log.d(TAG, "releaseAll() 清空所有按键与摇杆")
        buttonMask.set(0)
        leftX = 0f
        leftY = 0f
        rightX = 0f
        rightY = 0f
        send(force = true)
    }

    // ---------------- 内部实现 ----------------

    private fun isGamepadSource(source: Int): Boolean {
        return (source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)) != 0
    }

    private fun keyCodeToButtonBit(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> 0
        KeyEvent.KEYCODE_BUTTON_B -> 1
        KeyEvent.KEYCODE_BUTTON_X -> 2
        KeyEvent.KEYCODE_BUTTON_Y -> 3
        KeyEvent.KEYCODE_BUTTON_L1 -> 4
        KeyEvent.KEYCODE_BUTTON_R1 -> 5
        KeyEvent.KEYCODE_BUTTON_L2 -> 6
        KeyEvent.KEYCODE_BUTTON_R2 -> 7
        KeyEvent.KEYCODE_BUTTON_SELECT -> 8
        KeyEvent.KEYCODE_BUTTON_START -> 9
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 10
        KeyEvent.KEYCODE_BUTTON_THUMBR -> 11
        KeyEvent.KEYCODE_DPAD_UP -> 12
        KeyEvent.KEYCODE_DPAD_DOWN -> 13
        KeyEvent.KEYCODE_DPAD_LEFT -> 14
        KeyEvent.KEYCODE_DPAD_RIGHT -> 15
        KeyEvent.KEYCODE_BUTTON_C -> 16
        KeyEvent.KEYCODE_BUTTON_Z -> 17
        // 兼容：部分廉价拉伸手柄把 Select / Start 映射为 BACK / MENU（仅手柄源会命中）
        KeyEvent.KEYCODE_BACK -> 8
        KeyEvent.KEYCODE_MENU -> 9
        else -> -1
    }

    /** 右摇杆轴来源：RX/RY（XInput 手柄）或 Z/RZ（DirectInput 兼容旧手柄） */
    private enum class RightStickSource { RX_RY, Z_RZ }

    /**
     * 每台手柄的右摇杆轴模式缓存（deviceId → 模式）。
     * 只在首次遇到该 deviceId 时解析一次；设备重连会得到新 deviceId，自动重新解析。
     */
    private val rightStickModeByDevice = mutableMapOf<Int, RightStickSource>()

    /**
     * 解析右摇杆轴模式。设备暂不可用（InputDevice.getDevice 返回 null）时：
     * - 有缓存 → 沿用缓存模式继续处理（事件本身带轴数据，不受影响）
     * - 无缓存 → 返回 null，调用方跳过本事件，避免误读扳机轴 / 误清零
     */
    private fun resolveRightStickSource(deviceId: Int): RightStickSource? {
        rightStickModeByDevice[deviceId]?.let { return it }
        val device = InputDevice.getDevice(deviceId) ?: return null
        val mode = if (device.getMotionRange(MotionEvent.AXIS_RX) != null ||
            device.getMotionRange(MotionEvent.AXIS_RY) != null
        ) RightStickSource.RX_RY else RightStickSource.Z_RZ
        rightStickModeByDevice[deviceId] = mode
        Log.d(
            TAG,
            "手柄 deviceId=$deviceId 右摇杆轴模式=" +
                (if (mode == RightStickSource.RX_RY) "RX/RY" else "Z/RZ（DirectInput 兼容）")
        )
        return mode
    }

    private fun updateDpadFromHat(hatX: Float, hatY: Float) {
        val bitLeft = 1 shl 14
        val bitRight = 1 shl 15
        val bitUp = 1 shl 12
        val bitDown = 1 shl 13
        var mask = buttonMask.get()
        mask = if (hatX <= -0.5f) mask or bitLeft else mask and bitLeft.inv()
        mask = if (hatX >= 0.5f) mask or bitRight else mask and bitRight.inv()
        mask = if (hatY <= -0.5f) mask or bitUp else mask and bitUp.inv()
        mask = if (hatY >= 0.5f) mask or bitDown else mask and bitDown.inv()
        buttonMask.set(mask)
    }

    /** 读取轴值并套用死区（死区内视为 0，避免摇杆中心抖动） */
    private fun axisValue(event: MotionEvent, axis: Int): Float {
        val raw = event.getAxisValue(axis)
        return if (abs(raw) < deadZone) 0f else raw
    }

    private fun updateControllerName(deviceId: Int) {
        if (deviceId == lastDeviceId) return
        lastDeviceId = deviceId
        _controllerName.value = InputDevice.getDevice(deviceId)?.name ?: "未知手柄"
        Log.d(TAG, "检测到手柄 deviceId=$deviceId name=${_controllerName.value}")
    }

    /** 组装并发送手柄报表；默认带 8ms 节流与去重，force=true 时强制发送。 */
    private fun send(force: Boolean = false) {
        val snapshot = GamepadSnapshot(
            buttonMask = buttonMask.get(),
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
        if (!force && !isReset && now - lastSendAt < minSendIntervalMs) return
        lastSendAt = now
        lastSent = snapshot
        _state.value = snapshot
        // WiFi 桥接出口：同一份快照也转发给 UDP（若已启用）
        udpBridge?.update(snapshot)
        // 注意：此处不再逐条 Log.d——摇杆高频上报（最高 125Hz）下 UI 线程日志会拖慢事件捕获
        btManager.sendGamepadReport(
            snapshot.buttonMask,
            snapshot.leftX,
            snapshot.leftY,
            snapshot.rightX,
            snapshot.rightY
        )
    }
}
