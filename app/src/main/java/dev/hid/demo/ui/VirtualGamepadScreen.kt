package dev.hid.demo.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import dev.hid.demo.input.VirtualGamepad
import dev.hid.demo.service.VibrateManager

/**
 * 手机模拟手柄界面（全屏横屏）
 *
 * 内部直接使用 [GamepadView]（Xbox Series / PlayStation 5 双布局、3D 立体按键、
 * 可编辑布局、震动反馈），把它的按键 / 摇杆回调桥接到 [VirtualGamepad]，
 * 由 [VirtualGamepad] 通过蓝牙 HID + WiFi UDP 双通道转发给电脑。
 *
 * 横屏方向由 MainActivity 控制；本界面负责屏幕常亮与隐藏系统栏。
 *
 * 退出方式：返回键 / GamepadView 顶栏 Close 按钮。
 */
@Composable
fun VirtualGamepadScreen(
    virtualGamepad: VirtualGamepad,
    vibrateManager: VibrateManager?,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current

    // 手机震动开关：模拟手柄界面首次进入默认开，退出时恢复默认关
    val prefs = context.getSharedPreferences("gamepad_layout", Context.MODE_PRIVATE)
    if (!prefs.contains("phone_vibration_enabled")) {
        prefs.edit().putBoolean("phone_vibration_enabled", true).apply()
    }
    val initialPhoneVibration = prefs.getBoolean("phone_vibration_enabled", true)
    vibrateManager?.enabled = initialPhoneVibration

    // 屏幕常亮 + 隐藏系统栏（全屏沉浸）
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val decorView = window?.decorView
        @Suppress("DEPRECATION")
        val oldVisibility = decorView?.systemUiVisibility
        view.keepScreenOn = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window?.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            decorView?.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN
        }
        onDispose {
            view.keepScreenOn = false
            // 退出模拟手柄界面：恢复主界面震动开关状态，停止当前震动
            vibrateManager?.enabled = prefs.getBoolean("main_vibration_enabled", false)
            vibrateManager?.stop()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window?.insetsController?.show(android.view.WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                decorView?.systemUiVisibility = oldVisibility ?: View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    BackHandler(onBack = onExit)

    // 布局位置 / 缩放等编辑状态按手柄布局独立持久化
    val sharedPrefs = context.getSharedPreferences("gamepad_layout", Context.MODE_PRIVATE)

    GamepadView(
        onClose = onExit,
        sharedPrefs = sharedPrefs,
        onButtonEvent = { mappingId, pressed -> onMappingButton(virtualGamepad, mappingId, pressed) },
        onStickMove = { stick, x, y ->
            virtualGamepad.setStick(
                if (stick == 0) VirtualGamepad.Stick.LEFT else VirtualGamepad.Stick.RIGHT,
                x,
                y
            )
        },
        onPhoneVibrationChange = { enabled -> vibrateManager?.enabled = enabled },
        // 震动测试：模拟一次游戏震动（大马达 200 / 小马达 100），验证手机震动链路
        onTestVibration = { vibrateManager?.onVibration(200, 100) }
    )
}

/** GamepadView 的 mappingId（0..17，18=触控板忽略）→ HID 按键位 */
private val MAPPING_ID_TO_BIT = intArrayOf(
    VirtualGamepad.BIT_A, VirtualGamepad.BIT_B, VirtualGamepad.BIT_X, VirtualGamepad.BIT_Y,
    VirtualGamepad.BIT_LB, VirtualGamepad.BIT_RB, VirtualGamepad.BIT_LT, VirtualGamepad.BIT_RT,
    VirtualGamepad.BIT_SELECT, VirtualGamepad.BIT_START,
    VirtualGamepad.BIT_L3, VirtualGamepad.BIT_R3,
    VirtualGamepad.BIT_DPAD_UP, VirtualGamepad.BIT_DPAD_DOWN, VirtualGamepad.BIT_DPAD_LEFT, VirtualGamepad.BIT_DPAD_RIGHT,
    VirtualGamepad.BIT_C, VirtualGamepad.BIT_Z
)

private fun onMappingButton(virtualGamepad: VirtualGamepad, mappingId: Int, pressed: Boolean) {
    if (mappingId < 0 || mappingId >= MAPPING_ID_TO_BIT.size) return
    virtualGamepad.setButton(MAPPING_ID_TO_BIT[mappingId], pressed)
}
