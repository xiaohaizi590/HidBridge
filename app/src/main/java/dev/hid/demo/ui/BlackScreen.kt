package dev.hid.demo.ui

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView

/**
 * 全黑屏模式：开启外部手柄桥接后，进入纯黑全屏界面当作游戏手柄使用。
 * 纯黑背景在 OLED 屏幕上像素不发光，最省电。
 *
 * 关键点：
 * - 手柄桥接工作在 Activity 层（dispatchKeyEvent / dispatchGenericMotionEvent），
 *   与本界面无关，进入黑屏后手柄输入会持续转发给电脑。
 * - 屏幕保持常亮：黑屏只是不显示画面，屏幕一旦休眠，事件分发就会中断。
 * - 隐藏系统栏，实现真正全黑沉浸。
 *
 * 退出方式：长按屏幕任意位置，或按返回键。
 */
@Composable
fun BlackScreen(onExit: () -> Unit) {
    val view = LocalView.current

    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val decorView = window?.decorView
        @Suppress("DEPRECATION")
        val oldVisibility = decorView?.systemUiVisibility

        // 屏幕常亮，避免休眠中断手柄事件
        view.keepScreenOn = true

        // 隐藏系统栏
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window?.insetsController?.show(android.view.WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                decorView?.systemUiVisibility = oldVisibility ?: View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    // 返回键退出
    BackHandler(onBack = onExit)

    // 纯黑背景，长按任意位置退出（普通点击不响应，避免误触）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onExit() }) }
    )
}
