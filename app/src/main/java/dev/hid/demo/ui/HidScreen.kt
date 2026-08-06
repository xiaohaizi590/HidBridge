package dev.hid.demo.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.hid.demo.bluetooth.BluetoothKeyboardManager
import dev.hid.demo.bluetooth.BluetoothState
import dev.hid.demo.input.InputBridge
import dev.hid.demo.input.UdpBridge
import dev.hid.demo.wifi.WifiCommandBridge
import dev.hid.demo.wifi.WifiCommandHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 最小可运行 UI：用于验证蓝牙 HID 方案
 *
 * 区块：
 *   1. 服务状态
 *   2. 设备（扫描 / 已配对 / 连接 / 断开）
 *   3. 手柄测试（按键位图 + 摇杆方向）
 *   4. 外部手柄输入（USB-C 拉伸手柄桥接，可进入黑屏模式）
 */
@Composable
fun HidScreen(
    btManager: BluetoothKeyboardManager,
    inputBridge: InputBridge,
    udpBridge: UdpBridge,
    commandBridge: WifiCommandBridge,
    commandHandler: WifiCommandHandler,
    onEnterBlackScreen: () -> Unit,
    onPushInstaller: () -> Unit
) {
    val serviceState by btManager.serviceState.collectAsState()
    val statusMessage by btManager.statusMessage.collectAsState()
    val bondedDevices by btManager.bondedDevices.collectAsState()
    val scannedDevices by btManager.scannedDevices.collectAsState()
    val isScanning by btManager.isScanning.collectAsState()
    val connectedDevice by btManager.connectedDevice.collectAsState()

    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { StatusCard(serviceState, statusMessage, connectedDevice, btManager) }

        item { SectionTitle("设备连接") }
        item {
            Row {
                OutlinedButton(onClick = { btManager.startScanning() }) {
                    Text(if (isScanning) "扫描中..." else "扫描设备")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { btManager.stopScanning() }, enabled = isScanning) {
                    Text("停止")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { btManager.disconnectDevice() }, enabled = connectedDevice != null) {
                    Text("断开")
                }
            }
        }
        item { SectionTitle("已配对设备（点击连接）") }
        if (bondedDevices.isEmpty()) {
            item { Text("（无已配对设备）", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(bondedDevices.size) { i ->
                val device = bondedDevices[i]
                DeviceRow(device = device, isConnected = connectedDevice?.address == device.address, btManager = btManager) {
                    btManager.connectDevice(device)
                }
            }
        }
        if (scannedDevices.isNotEmpty()) {
            item { SectionTitle("扫描结果（点击连接）") }
            items(scannedDevices.size) { i ->
                val device = scannedDevices[i]
                DeviceRow(device = device, isConnected = connectedDevice?.address == device.address, btManager = btManager) {
                    btManager.connectDevice(device)
                }
            }
        }

        item { SectionTitle("手柄测试") }
        item {
            GamepadTester(btManager, scope)
        }

        item { SectionTitle("外部手柄输入（USB-C 拉伸手柄）") }
        item {
            ExternalGamepadCard(inputBridge, onEnterBlackScreen)
        }

        item { SectionTitle("WiFi 桥接（局域网 UDP → 电脑虚拟 Xbox 手柄）") }
        item {
            WifiBridgeCard(udpBridge, commandBridge, commandHandler, inputBridge, onPushInstaller, scope)
        }

        item { SectionTitle("回报率") }
        item {
            RateSliderCard(inputBridge, udpBridge)
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun StatusCard(
    serviceState: BluetoothState,
    statusMessage: String,
    connectedDevice: BluetoothDevice?,
    btManager: BluetoothKeyboardManager
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("服务状态: ${serviceStateLabel(serviceState)}", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(statusMessage, style = MaterialTheme.typography.bodyMedium)
            if (connectedDevice != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "已连接: ${btManager.deviceDisplayName(connectedDevice) ?: connectedDevice.address}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** 将蓝牙状态映射为中文显示文本 */
private fun serviceStateLabel(state: BluetoothState): String = when (state) {
    BluetoothState.Unsupported -> "设备不支持蓝牙"
    BluetoothState.PermissionRequired -> "缺少蓝牙权限"
    BluetoothState.BluetoothOff -> "蓝牙已关闭"
    BluetoothState.ProfileNotSupported -> "不支持 HID 协议"
    BluetoothState.ReadyDisconnected -> "未连接"
    is BluetoothState.PairingMode -> "配对模式（${state.name}）"
    is BluetoothState.Connected -> "已连接（${state.deviceName}）"
}

@Composable
private fun DeviceRow(
    device: BluetoothDevice,
    isConnected: Boolean,
    btManager: BluetoothKeyboardManager,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                btManager.deviceDisplayName(device) ?: "(未命名)",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1
            )
            Text(
                device.address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
        if (isConnected) {
            Text("已连接", color = MaterialTheme.colorScheme.primary)
        }
        TextButton(onClick = onClick) { Text(if (isConnected) "已连接" else "连接") }
    }
    HorizontalDivider()
}

// ---------------- 手柄 ----------------

/** 18 个按键位：bit0=A(2), bit1=B(3), bit2=X(1), bit3=Y(4), bit4=LB(5), bit5=RB(6), bit6=LT(7), bit7=RT(8), bit8=Select(9), bit9=Start(10), bit10=L3(11), bit11=R3(12), bit12=DPadU(13), bit13=DPadD(14), bit14=DPadL(15), bit15=DPadR(16) */
@Composable
private fun GamepadTester(btManager: BluetoothKeyboardManager, scope: kotlinx.coroutines.CoroutineScope) {
    Column {
        Row {
            Button(onClick = { scope.launch { tapGamepadButton(btManager, 0x0001) } }) { Text("A") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { tapGamepadButton(btManager, 0x0002) } }) { Text("B") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { tapGamepadButton(btManager, 0x0004) } }) { Text("X") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { tapGamepadButton(btManager, 0x0008) } }) { Text("Y") }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = { scope.launch { holdGamepadStick(btManager, "left", 0f, -1f) } }) { Text("左摇杆↑") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { holdGamepadStick(btManager, "left", 0f, 1f) } }) { Text("左摇杆↓") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { holdGamepadStick(btManager, "right", 1f, 0f) } }) { Text("右摇杆→") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { holdGamepadStick(btManager, "right", -1f, 0f) } }) { Text("右摇杆←") }
        }
    }
}

private suspend fun tapGamepadButton(btManager: BluetoothKeyboardManager, mask: Int) {
    btManager.sendGamepadReport(mask, 0f, 0f, 0f, 0f)
    delay(60)
    btManager.sendGamepadReport(0, 0f, 0f, 0f, 0f)
}

private suspend fun holdGamepadStick(btManager: BluetoothKeyboardManager, stick: String, x: Float, y: Float) {
    // 持续 1 秒推杆，模拟按住方向
    val end = System.currentTimeMillis() + 1000
    while (System.currentTimeMillis() < end) {
        if (stick == "left") {
            btManager.sendGamepadReport(0, x, y, 0f, 0f)
        } else {
            btManager.sendGamepadReport(0, 0f, 0f, x, y)
        }
        delay(16)
    }
    btManager.sendGamepadReport(0, 0f, 0f, 0f, 0f)
}

// ---------------- 外部手柄（USB-C 拉伸手柄） ----------------

/** 18 个按键的显示标签与 bit 位（与 InputBridge 映射一致） */
private val gamepadButtonLabels = listOf(
    "A" to 0, "B" to 1, "X" to 2, "Y" to 3, "LB" to 4, "RB" to 5,
    "LT" to 6, "RT" to 7, "Sel" to 8, "Start" to 9, "L3" to 10, "R3" to 11,
    "↑" to 12, "↓" to 13, "←" to 14, "→" to 15, "C" to 16, "Z" to 17
)

/**
 * 外部手柄输入卡片：桥接开关 + 已连接手柄名 + 实时按键 / 摇杆状态。
 * 手柄必须通过 USB-C / OTG 连接（蓝牙拉伸手柄与 HID Device 角色冲突，不可行）。
 */
@Composable
private fun ExternalGamepadCard(bridge: InputBridge, onEnterBlackScreen: () -> Unit) {
    val snapshot by bridge.state.collectAsState()
    val controllerName by bridge.controllerName.collectAsState()
    var enabled by remember { mutableStateOf(bridge.isEnabled) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("手柄桥接（转发到手柄报表）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (controllerName != null) "已连接: $controllerName" else "未检测到手柄，请通过 USB-C / OTG 连接拉伸手柄",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (controllerName != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    bridge.setEnabled(it)
                })
            }
            Spacer(Modifier.height(8.dp))
            GamepadButtonRow(snapshot.buttonMask)
            Spacer(Modifier.height(4.dp))
            Text(
                "左摇杆 (${fmtAxis(snapshot.leftX)}, ${fmtAxis(snapshot.leftY)})  " +
                    "右摇杆 (${fmtAxis(snapshot.rightX)}, ${fmtAxis(snapshot.rightY)})  " +
                    "按键 0x${String.format("%06X", snapshot.buttonMask)}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onEnterBlackScreen,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (enabled) "进入黑屏模式（桥接持续转发，长按屏幕退出）"
                    else "请先开启手柄桥接"
                )
            }
        }
    }
}

@Composable
private fun GamepadButtonRow(buttonMask: Int) {
    Row(Modifier.fillMaxWidth()) {
        gamepadButtonLabels.forEach { (label, bit) ->
            val pressed = (buttonMask and (1 shl bit)) != 0
            Text(
                label,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .background(
                        if (pressed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (pressed) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/** 轴值格式化：0 显示 0.00，其余带符号两位小数 */
private fun fmtAxis(v: Float): String =
    if (v == 0f) "0.00" else String.format("%+.2f", v)

// ---------------- WiFi 桥接（UDP → 电脑虚拟 Xbox） ----------------

/**
 * WiFi 桥接卡片：RFCOMM 命令通道状态 + UDP 开关 + 电脑 IP + 实时状态。
 * 手机与电脑需在同一局域网；电脑端需运行 receiver.exe（含 ViGEmBus 驱动）。
 * RFCOMM 命令通道自动发现 PC IP，无需手动输入。
 */
@Composable
private fun WifiBridgeCard(
    udpBridge: UdpBridge,
    commandBridge: WifiCommandBridge,
    commandHandler: WifiCommandHandler,
    inputBridge: InputBridge,
    onPushInstaller: () -> Unit,
    scope: CoroutineScope
) {
    val status by udpBridge.status.collectAsState()
    val ackStatus by udpBridge.ackStatus.collectAsState()
    val channelState by commandBridge.channelState.collectAsState()
    val bridgeState by commandHandler.state.collectAsState()
    val autoPcIp by commandHandler.pcIp.collectAsState()

    var enabled by remember { mutableStateOf(udpBridge.isEnabled()) }
    var manualHost by rememberSaveable { mutableStateOf("") }
    var showOfflineDialog by remember { mutableStateOf(false) }

    // 自动 IP 优先：RFCOMM 发现的 IP > 用户手动输入 > 默认
    val effectiveHost = when {
        autoPcIp != null -> autoPcIp!!
        manualHost.isNotBlank() -> manualHost
        else -> "192.168.1.100"
    }
    val isAutoIp = autoPcIp != null

    // 开启 WiFi 桥接后若电脑未回包（receiver 未运行），自动询问是否推送安装包
    DisposableEffect(udpBridge) {
        udpBridge.onComputerOffline = { showOfflineDialog = true }
        onDispose { udpBridge.onComputerOffline = null }
    }

    // 当桥接就绪且有自动 IP 时，自动启动 UDP
    DisposableEffect(bridgeState) {
        if (bridgeState is WifiCommandHandler.BridgeState.Ready && autoPcIp != null && !udpBridge.isEnabled()) {
            udpBridge.setEnabled(true, autoPcIp!!)
            enabled = true
        }
        onDispose {}
    }

    if (showOfflineDialog) {
        AlertDialog(
            onDismissRequest = { showOfflineDialog = false },
            title = { Text("电脑未就绪") },
            text = {
                Text(
                    "开启 WiFi 桥接后未收到电脑回包，说明接收程序 receiver.exe 未在电脑上运行。\n\n" +
                        "是否现在把安装包推送到电脑？（电脑收到后双击运行 receiver.exe --install 即可）"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showOfflineDialog = false
                    onPushInstaller()
                }) { Text("推送安装包") }
            },
            dismissButton = {
                TextButton(onClick = { showOfflineDialog = false }) { Text("稍后再说") }
            }
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("WiFi 桥接（UDP → 电脑虚拟 Xbox 手柄）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "电脑端运行 receiver.exe 后，开启此开关即可转发",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    if (it) {
                        udpBridge.setEnabled(true, effectiveHost)
                        inputBridge.setRate(InputBridge.RATE_500)
                    } else {
                        udpBridge.setEnabled(false, effectiveHost)
                        inputBridge.setRate(InputBridge.RATE_125)
                    }
                })
            }
            Spacer(Modifier.height(8.dp))

            // RFCOMM 状态
            Text(
                "命令通道：${channelStateLabel(channelState)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            // 桥接状态详情
            when (bridgeState) {
                is WifiCommandHandler.BridgeState.WaitingWifi -> {
                    Text("正在检测 WiFi 是否同网...", style = MaterialTheme.typography.bodySmall)
                }
                is WifiCommandHandler.BridgeState.WifiOk -> {
                    Text("WiFi 同网 ✓", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                is WifiCommandHandler.BridgeState.WifiMismatch -> {
                    Text("⚠ WiFi 不同网！请确保手机和电脑连接同一 WiFi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                is WifiCommandHandler.BridgeState.Ready -> {
                    Text("桥接就绪 ✓", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                is WifiCommandHandler.BridgeState.Error -> {
                    Text("错误: ${(bridgeState as WifiCommandHandler.BridgeState.Error).msg}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                else -> {}
            }

            Spacer(Modifier.height(8.dp))

            // PC IP 显示
            OutlinedTextField(
                value = if (isAutoIp) effectiveHost else manualHost,
                onValueChange = { manualHost = it },
                label = { Text(if (isAutoIp) "电脑 IP（自动发现）" else "电脑 IP") },
                singleLine = true,
                enabled = !enabled,
                modifier = Modifier.fillMaxWidth(),
                supportingText = if (isAutoIp) ({ Text("通过 RFCOMM 命令通道自动发现，无需手动输入") }) else null,
                trailingIcon = if (isAutoIp) ({ Text("✓", color = MaterialTheme.colorScheme.primary) }) else null
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val ips = udpBridge.discoverPcIps()
                        if (ips.isNotEmpty()) {
                            manualHost = ips.first()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !enabled
            ) {
                Text("🔍 自动发现 PC IP")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onPushInstaller,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("推送安装包到电脑（receiver.exe）")
            }
            Text(
                "安装包已内置在 App 中，点此直接推送到电脑（蓝牙）。电脑收到后双击运行 receiver.exe --install 即可完成安装",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "确认回包：$ackStatus",
                style = MaterialTheme.typography.bodySmall,
                color = if (ackStatus.startsWith("电脑已确认")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun channelStateLabel(state: WifiCommandBridge.ChannelState): String = when (state) {
    is WifiCommandBridge.ChannelState.Idle -> "未启动"
    is WifiCommandBridge.ChannelState.Listening -> "监听中，等待电脑连接…"
    is WifiCommandBridge.ChannelState.Connected -> "已连接：${state.deviceName}"
    is WifiCommandBridge.ChannelState.Disconnected -> "已断开，重新等待连接…"
}

// ---------------- 回报率滑块 ----------------

private val RATE_OPTIONS = listOf(125, 250, 500, 750)
private val RATE_LABELS = mapOf(
    125 to "省电",
    250 to "均衡",
    500 to "电竞",
    750 to "极致"
)
private val RATE_DESCRIPTIONS = mapOf(
    125 to "省电模式，蓝牙链路",
    250 to "均衡，日常游戏够用",
    500 to "WiFi 推荐，电竞手感",
    750 to "极致刷新，耗电明显增加"
)

/**
 * 回报率滑块卡片：4 档切换，随连接模式自动调整默认值。
 * - 蓝牙连接 → 默认 125Hz
 * - WiFi 连接 → 默认 500Hz
 * - 用户手动选择 → 覆盖自动值
 */
@Composable
@Suppress("UNUSED_CHANGED_VALUE")
private fun RateSliderCard(
    inputBridge: InputBridge,
    udpBridge: UdpBridge
) {
    val currentRate = inputBridge.getCurrentRate()
    var selectedIndex by remember {
        mutableStateOf(RATE_OPTIONS.indexOf(currentRate).coerceAtLeast(0))
    }
    val enabled = udpBridge.isEnabled()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "回报率：${RATE_OPTIONS[selectedIndex]}Hz（${RATE_LABELS[RATE_OPTIONS[selectedIndex]]}）",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Spacer(Modifier.height(8.dp))

            Slider(
                value = selectedIndex.toFloat(),
                onValueChange = { idx ->
                    selectedIndex = idx.toInt().coerceIn(0, 3)
                },
                onValueChangeFinished = {
                    val hz = RATE_OPTIONS[selectedIndex]
                    inputBridge.setRate(hz)
                    if (udpBridge.isEnabled()) {
                        udpBridge.setRate(hz)
                    }
                },
                steps = 3,
                valueRange = 0f..3f,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                RATE_OPTIONS.forEach { hz ->
                    Text(
                        text = "${hz}Hz",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hz == RATE_OPTIONS[selectedIndex]) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                RATE_DESCRIPTIONS[RATE_OPTIONS[selectedIndex]] ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (enabled) {
                    "当前使用 WiFi 桥接，${RATE_OPTIONS[selectedIndex]}Hz"
                } else {
                    "当前使用蓝牙 HID，${RATE_OPTIONS[selectedIndex]}Hz（WiFi 开启后将自动切换到对应档位）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
