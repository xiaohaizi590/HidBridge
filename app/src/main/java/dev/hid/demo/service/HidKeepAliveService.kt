package dev.hid.demo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.hid.demo.MainActivity
import dev.hid.demo.R

/**
 * 后台保活前台服务
 *
 * 作用：让 App 进程在切后台 / 锁屏后不被系统回收，保持蓝牙 HID 设备角色注册不失效、
 * 与电脑的连接不中断（键盘 / 鼠标 / 手柄持续可用）。
 *
 * 说明：
 * - 前台服务类型 `connectedDevice`（蓝牙外设场景），Android 14+ 需要声明
 *   FOREGROUND_SERVICE_CONNECTED_DEVICE 权限（已在 Manifest 中声明）。
 * - 持有 PARTIAL_WAKE_LOCK：锁屏后 CPU 不休眠，保证 HID 报表上报不因休眠而中断。
 * - START_STICKY：进程被系统杀死后会自动重启并重新进入前台服务状态。
 */
class HidKeepAliveService : Service() {

    companion object {
        private const val TAG = "HidKeepAliveService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "hid_keepalive"

        /** 启动保活服务（App 前台时调用，安全）。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, HidKeepAliveService::class.java)
            )
        }

        /** 停止保活服务（释放唤醒锁，蓝牙 HID 注册本身不受影响）。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, HidKeepAliveService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // 锁屏后保持 CPU 运行（HID 报表上报 / 主机 LED 回读依赖 CPU 在线）
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HidDemo::HidKeepAlive").apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "wake lock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        Log.d(TAG, "destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "蓝牙 HID 保活",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持蓝牙键盘 / 鼠标 / 手柄在后台持续可用"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("蓝牙 HID 服务运行中")
            .setContentText("后台保持连接，键盘 / 鼠标 / 手柄持续可用")
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }
}
