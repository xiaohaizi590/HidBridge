package dev.hid.demo

import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import dev.hid.demo.bluetooth.BluetoothKeyboardManager
import dev.hid.demo.input.InputBridge
import dev.hid.demo.input.UdpBridge
import dev.hid.demo.input.VirtualGamepad
import dev.hid.demo.service.HidKeepAliveService
import dev.hid.demo.service.UsbHaptics
import dev.hid.demo.service.VibrateManager
import dev.hid.demo.ui.BlackScreen
import dev.hid.demo.ui.HidScreen
import dev.hid.demo.ui.VirtualGamepadScreen
import dev.hid.demo.wifi.WifiCommandBridge
import dev.hid.demo.wifi.WifiCommandHandler

class MainActivity : ComponentActivity() {
    companion object {
        @android.annotation.SuppressLint("StaticFieldLeak")
        private var btManagerInstance: BluetoothKeyboardManager? = null
    }

    private val btManager: BluetoothKeyboardManager
        get() = btManagerInstance!!

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 授权完成后才启动保活前台服务：Android 14+ 校验 connectedDevice 类型要求
        // 至少一项蓝牙权限已授予，否则 startForeground 抛 SecurityException 崩溃。
        // 已授权时回调立即触发，不影响后续启动。
        if (hasBluetoothPermission()) {
            HidKeepAliveService.start(this)
        }
        // Notify Bluetooth service to re-check status after user interaction
        btManagerInstance?.checkBluetoothCapabilities()
    }

    /** 是否持有启动 connectedDevice 前台服务所需的任一蓝牙权限（API 31 以下默认返回 true） */
    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return listOf(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_SCAN
        ).any {
            checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /** 外部手柄输入桥（USB-C 拉伸手柄 → 手柄报表），在 dispatch*Event 中截获事件 */
    private var inputBridge: InputBridge? = null

    /** 手机虚拟手柄输入源（屏幕虚拟摇杆 / 按键 → 手柄报表） */
    private var virtualGamepad: VirtualGamepad? = null

    /** WiFi 桥接（UDP → 电脑虚拟 Xbox 手柄） */
    private var udpBridge: UdpBridge? = null

    /** RFCOMM 命令通道（阶段2：手机作为服务端，电脑主动连接） */
    private var commandBridge: WifiCommandBridge? = null

    /** RFCOMM 命令处理器（阶段3-5：解析命令 + 状态机） */
    private var commandHandler: WifiCommandHandler? = null

    /** 游戏震动回传：手机震动执行器（模拟手柄 / 无马达手柄时启用，可开关） */
    private var vibrateManager: VibrateManager? = null

    /** 实体拉伸手柄震动（USB SetReport 驱动手柄马达，失败自动降级手机震动） */
    private var usbHaptics: UsbHaptics? = null

    /** 内置安装包文件名（位于 assets/installer/，由 build_exe.bat 打包后自动放入） */
    private val installerAssetPath = "installer/GamepadBridge.exe"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (btManagerInstance == null) {
            btManagerInstance = try {
                BluetoothKeyboardManager(applicationContext)
            } catch (e: Throwable) {
                android.util.Log.e("MainActivity", "Failed to initialize BluetoothKeyboardManager", e)
                null
            }
        }

        // Request Bluetooth and Location permissions dynamically
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_SCAN,
                // 与蓝牙权限同属「附近的设备」权限组，共用一个授权弹窗；用于读取 WiFi SSID/IP 做同网检测
                android.Manifest.permission.NEARBY_WIFI_DEVICES,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
        } else {
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        permissionLauncher.launch(permissions)

        enableEdgeToEdge()
        udpBridge = UdpBridge(applicationContext, lifecycleScope)
        // 启动 PC→手机 UDP 震动通道监听（端口 47810），App 启动即生效
        udpBridge?.startVibrationListener()
        inputBridge = InputBridge(btManager).also { it.udpBridge = udpBridge }
        virtualGamepad = VirtualGamepad(btManager).also { it.udpBridge = udpBridge }
        // RFCOMM 命令通道：手机作为服务端监听，等待电脑 GamepadBridge.exe 连入
        commandBridge = WifiCommandBridge(applicationContext, lifecycleScope).also {
            it.startListening()
        }
        // RFCOMM 命令处理器：解析 PC 命令 + 维护 WiFi 桥接状态机
        vibrateManager = VibrateManager(applicationContext)
        // 主界面「手机震动」开关：默认关（实体手柄有马达时由手柄自己震），无马达手柄可在主界面开启
        vibrateManager?.enabled = getSharedPreferences("gamepad_layout", MODE_PRIVATE)
            .getBoolean("main_vibration_enabled", false)
        // 实体拉伸手柄震动：识别 HID 手柄 + 请求 USB 权限，收到震动命令时优先驱动手柄马达
        usbHaptics = UsbHaptics(this).also { it.init() }
        vibrateManager?.physicalHaptics = { l, s -> usbHaptics?.setVibration(l, s) }
        // 游戏震动双通道：UDP 震动通道（主，低延迟）+ RFCOMM vibrate 命令（兜底）
        udpBridge?.onVibration = { l, s -> vibrateManager?.onVibration(l, s) }
        commandHandler = WifiCommandHandler(applicationContext, lifecycleScope, commandBridge!!, vibrateManager)
        setContent {
            // 黑屏模式：手柄桥接仍由 Activity 层 dispatch*Event 持续转发
            var blackScreen by rememberSaveable { mutableStateOf(false) }
            // 虚拟手柄模式：进入时暂停实体桥接，退出时恢复
            var virtualGamepadOpen by rememberSaveable { mutableStateOf(false) }
            var inputBridgeWasEnabled by rememberSaveable { mutableStateOf(false) }

            // 屏幕状态：0=黑屏，1=虚拟手柄，2=主界面
            val screenState = when {
                blackScreen -> 0
                virtualGamepadOpen -> 1
                else -> 2
            }

            AnimatedContent(
                targetState = screenState,
                transitionSpec = {
                    if (targetState == 1) {
                        // 模拟手柄：从底部滑入，主界面淡出
                        (slideInVertically(tween(320)) { it / 3 } + fadeIn(tween(320)))
                            .togetherWith(fadeOut(tween(200)))
                    } else {
                        // 黑屏 / 返回主界面：纯淡入淡出
                        fadeIn(tween(300)) togetherWith fadeOut(tween(200))
                    }
                },
                label = "screenTransition"
            ) { state ->
                when (state) {
                    0 -> BlackScreen(onExit = { blackScreen = false })
                    1 -> VirtualGamepadScreen(
                        virtualGamepad = virtualGamepad!!,
                        vibrateManager = vibrateManager,
                        onExit = {
                            virtualGamepad?.releaseAll()
                            // 恢复进入前的实体手柄桥接状态
                            inputBridge?.setEnabled(inputBridgeWasEnabled)
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            virtualGamepadOpen = false
                        }
                    )
                    else -> HidScreen(
                        btManager = btManager,
                        inputBridge = inputBridge!!,
                        udpBridge = udpBridge!!,
                        commandBridge = commandBridge!!,
                        commandHandler = commandHandler!!,
                        vibrateManager = vibrateManager,
                        onEnterBlackScreen = { blackScreen = true },
                        onEnterVirtualGamepad = {
                            // 暂停实体手柄桥接，避免实体输入与虚拟输入混入
                            inputBridgeWasEnabled = inputBridge?.isEnabled ?: false
                            inputBridge?.setEnabled(false)
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            virtualGamepadOpen = true
                        },
                        onPushInstaller = { pushInstaller() }
                    )
                }
            }
        }
    }

    /**
     * 截获物理手柄按键事件，映射为手柄报表转发给电脑。
     * 仅在桥接开启且来源为手柄时消费；键盘 / 鼠标 / 其它来源原样透传。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val bridge = inputBridge
        if (bridge != null && bridge.handleKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** 截获物理手柄摇杆 / 扳机等模拟事件，映射为手柄报表转发给电脑。 */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val bridge = inputBridge
        if (bridge != null && bridge.handleMotionEvent(event)) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        super.onPause()
        // 页面不可见时清空按键状态，避免电脑端卡键
        inputBridge?.releaseAll()
        virtualGamepad?.releaseAll()
    }

    /**
     * 推送内置安装包：把 assets/installer/GamepadBridge.exe 拷贝到外部存储目录，
     * 通过 FileProvider 生成可分享 URI，再用系统分享（蓝牙等）发给电脑。
     * 使用 FLAG_GRANT_PERSISTABLE_URI_PERMISSION 确保蓝牙进程在传输期间稳定读取。
     */
    private fun pushInstaller() {
        // 使用内部存储 filesDir（MIUI/ColorOS 等定制 ROM 对外部存储 URI 读取限制极严，
        // 内部存储经 FileProvider 暴露后跨进程访问最稳定）
        val destDir = filesDir
        val destFile = java.io.File(destDir, "GamepadBridge.exe")

        val copied = try {
            assets.open(installerAssetPath).use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "内置安装包不存在或拷贝失败: $installerAssetPath", e)
            false
        }
        if (!copied) {
            Toast.makeText(
                this,
                "内置安装包缺失：请确认 GamepadBridge.exe 已放入 assets/installer/",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", destFile)

        // 仅加 FLAG_GRANT_READ_URI_PERMISSION：
        // ACTION_SEND 场景下 PERSISTABLE 多余，且在部分 MIUI 版本会被系统拦截
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, "GamepadBridge 安装包")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "GamepadBridge 安装包", uri)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, "发送 GamepadBridge 安装包给电脑"))
        }.onFailure {
            android.util.Log.e("MainActivity", "启动分享失败", it)
            Toast.makeText(this, "启动分享失败: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        commandHandler?.close()
        commandHandler = null
        vibrateManager?.close()
        vibrateManager = null
        usbHaptics?.close()
        usbHaptics = null
        commandBridge?.stop()
        commandBridge = null
        udpBridge?.close()
        udpBridge = null
        inputBridge = null
        virtualGamepad = null
        btManagerInstance?.close()
    }
}
