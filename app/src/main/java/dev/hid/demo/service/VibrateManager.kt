package dev.hid.demo.service

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 游戏震动回传：手机端震动执行器
 *
 * 数据流（双通道汇入）：
 *  - UDP 震动通道：PC → 手机 47810 端口 {"cmd":"vibrate","l":..,"s":..} → UdpBridge → [onVibration]
 *  - RFCOMM 命令通道：{"cmd":"vibrate","l":..,"s":..} → WifiCommandHandler → [onVibration]
 *
 * 震动语义（与《震动回传协议 v1.0》一致）：
 *  - l = 大马达 0-255（低频重震，如碰撞）
 *  - s = 小马达 0-255（高频细震，如摩擦）
 *  - Android 只能控制振幅无法控制频率，因此振幅取 max(l, s)，
 *    用 l/s 不同权重体现轻重差异；l==0 且 s==0 时停止震动。
 *  - 兜底停震：连续 [FALLBACK_STOP_MS] 未收到新震动包，自动停止（与 PC 侧 500ms 双保险）
 *
 * 开关：[enabled] 决定是否执行手机震动。实体手柄有马达时建议关闭
 * （手柄自己震），模拟手柄 / 无马达手柄时开启。
 */
class VibrateManager(private val context: Context) {

    companion object {
        private const val TAG = "VibrateManager"

        /** 单次震动持续时长（毫秒），重复值去重后不重复触发 */
        private const val VIBRATION_DURATION_MS = 50L

        /** 兜底停震阈值：连续该时长未收到新震动包即自动停震（PC 侧为 500ms，手机侧略宽松做双保险） */
        private const val FALLBACK_STOP_MS = 1000L

        /** 兜底停震检查周期 */
        private const val WATCHDOG_INTERVAL_MS = 250L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchdogJob: Job? = null

    /** 最近一次收到非零震动包的时间（uptimeMillis），用于兜底停震 */
    private var lastVibrationTime = 0L

    /**
     * 实体手柄震动出口：收到震动命令时优先尝试驱动实体拉伸手柄（USB SetReport）。
     * 由 MainActivity 接线到 [UsbHaptics.setVibration]，不受手机震动开关控制，
     * 手柄支持震动时它会自己震；不支持 / 未连接时上层继续手机震动兜底。
     */
    var physicalHaptics: ((l: Int, s: Int) -> Unit)? = null

    @Volatile
    var enabled = false
        set(value) {
            if (!value) stop()
            field = value
        }

    @Suppress("DEPRECATION")
    private val vibrator: Vibrator?
        get() = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private var lastL = -1
    private var lastS = -1

    /** 收到游戏震动命令：l / s ∈ 0..255，l==0 且 s==0 停止 */
    fun onVibration(l: Int, s: Int) {
        val ll = l.coerceIn(0, 255)
        val ss = s.coerceIn(0, 255)
        // 与上次相同则忽略（PC 端高频上报时避免反复触发）
        if (ll == lastL && ss == lastS) return
        lastL = ll
        lastS = ss
        Log.d(TAG, "震动命令 l=$ll s=$ss enabled=$enabled")

        // 实体手柄震动（尽力而为，不受手机震动开关控制）
        physicalHaptics?.invoke(ll, ss)

        if (!enabled) return
        if (ll == 0 && ss == 0) {
            stop()
            return
        }
        lastVibrationTime = SystemClock.uptimeMillis()
        ensureWatchdog()
        val v = vibrator ?: return
        val amplitude = maxOf(ll, ss).coerceIn(1, 255)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(
                    VibrationEffect.createWaveform(
                        longArrayOf(0, VIBRATION_DURATION_MS),
                        intArrayOf(amplitude, amplitude),
                        0 // 持续震动直到 cancel
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(VIBRATION_DURATION_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "手机震动执行失败", e)
        }
    }

    /** 兜底停震看门狗：PC 停止上报（包丢失 / exe 退出）时手机也能自动停震 */
    private fun ensureWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val vibrating = lastL > 0 || lastS > 0
                if (enabled && vibrating && SystemClock.uptimeMillis() - lastVibrationTime >= FALLBACK_STOP_MS) {
                    Log.d(TAG, "兜底停震：${FALLBACK_STOP_MS}ms 无新震动包")
                    stop()
                }
            }
        }
    }

    /** 停止手机震动 */
    fun stop() {
        lastL = -1
        lastS = -1
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.w(TAG, "取消震动失败", e)
        }
    }

    /** 释放内部协程作用域（App 退出时调用） */
    fun close() {
        watchdogJob?.cancel()
        watchdogJob = null
        scope.cancel()
        stop()
    }
}
