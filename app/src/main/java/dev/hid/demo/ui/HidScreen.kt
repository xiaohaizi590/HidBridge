package dev.hid.demo.ui

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
 * HidBridge 主界面：引导用户完成 蓝牙 HID → WiFi 桥接 → 虚拟手柄 的完整流程
 *
 * 流程步骤：
 *   ① 蓝牙连接  →  ② 推送安装包  →  ③ WiFi 桥接  →  ④ 开始游戏
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
        // 顶部状态卡片
        item { HeroCard(serviceState, statusMessage, connectedDevice, btManager) }

        // 步骤 1：蓝牙连接
        item { StepCard(step = 1, title = "蓝牙连接", icon = Icons.Default.Bluetooth, completed = connectedDevice != null) {
            BluetoothConnectionSection(
                isScanning = isScanning,
                bondedDevices = bondedDevices,
                scannedDevices = scannedDevices,
                connectedDevice = connectedDevice,
                btManager = btManager
            )
        }}

        // 步骤 2：推送安装包
        item { StepCard(step = 2, title = "电脑端安装", icon = Icons.Default.Download, completed = false) {
            InstallerSection(onPushInstaller = onPushInstaller)
        }}

        // 步骤 3：WiFi 桥接
        item { StepCard(step = 3, title = "WiFi 桥接", icon = Icons.Default.Wifi, completed = udpBridge.isEnabled()) {
            WifiBridgeSection(
                udpBridge = udpBridge,
                commandBridge = commandBridge,
                commandHandler = commandHandler,
                inputBridge = inputBridge,
                onPushInstaller = onPushInstaller,
                scope = scope
            )
        }}

        // 步骤 4：开始游戏
        item { StepCard(step = 4, title = "开始游戏", icon = Icons.Default.SportsEsports, completed = false) {
            GamepadSection(
                inputBridge = inputBridge,
                onEnterBlackScreen = onEnterBlackScreen,
                btManager = btManager,
                scope = scope
            )
        }}

        // 回报率设置
        item { RateSettingSection(inputBridge, udpBridge) }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ==================== 顶部 Hero 卡片 ====================

@Composable
private fun HeroCard(
    serviceState: BluetoothState,
    statusMessage: String,
    connectedDevice: BluetoothDevice?,
    btManager: BluetoothKeyboardManager
) {
    val stateColor = when (serviceState) {
        is BluetoothState.Connected -> Color(0xFF4CAF50)
        BluetoothState.ReadyDisconnected -> Color(0xFFFF9800)
        BluetoothState.BluetoothOff -> Color(0xFFF44336)
        else -> Color(0xFF9E9E9E)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(stateColor, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = serviceStateLabel(serviceState),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            if (connectedDevice != null) {
                Spacer(Modifier.height(8.dp))
                AssistChip(
                    onClick = {},
                    label = { Text("已连接: ${btManager.deviceDisplayName(connectedDevice) ?: connectedDevice.address}") },
                    leadingIcon = { Icon(Icons.Default.CheckCircle, null) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        labelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }
    }
}

// ==================== 步骤卡片 ====================

@Composable
private fun StepCard(
    step: Int,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    completed: Boolean,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (completed) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            if (completed) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.secondaryContainer,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (completed) {
                        Icon(
                            Icons.Default.CheckCircle,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text(
                            "$step",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Icon(icon, null, tint = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

// ==================== 步骤 1：蓝牙连接 ====================

@Composable
private fun BluetoothConnectionSection(
    isScanning: Boolean,
    bondedDevices: List<BluetoothDevice>,
    scannedDevices: List<BluetoothDevice>,
    connectedDevice: BluetoothDevice?,
    btManager: BluetoothKeyboardManager
) {
    Column {
        // 操作按钮
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { btManager.startScanning() },
                modifier = Modifier.weight(1f),
                enabled = !isScanning
            ) {
                Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (isScanning) "扫描中..." else "扫描电脑")
            }
            OutlinedButton(
                onClick = { btManager.stopScanning() },
                enabled = isScanning
            ) { Text("停止") }
        }

        if (isScanning) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(12.dp))

        // 已配对设备
        if (bondedDevices.isNotEmpty()) {
            Text("已配对设备", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            bondedDevices.forEach { device ->
                DeviceRow(
                    device = device,
                    isConnected = connectedDevice?.address == device.address,
                    btManager = btManager,
                    onClick = { btManager.connectDevice(device) }
                )
            }
        }

        // 扫描结果
        if (scannedDevices.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("扫描到的设备", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            scannedDevices.forEach { device ->
                DeviceRow(
                    device = device,
                    isConnected = connectedDevice?.address == device.address,
                    btManager = btManager,
                    onClick = { btManager.connectDevice(device) }
                )
            }
        }

        if (bondedDevices.isEmpty() && scannedDevices.isEmpty() && !isScanning) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "点击「扫描电脑」开始查找附近的蓝牙设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (connectedDevice != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { btManager.disconnectDevice() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("断开连接") }
        }
    }
}

@Composable
private fun DeviceRow(
    device: BluetoothDevice,
    isConnected: Boolean,
    btManager: BluetoothKeyboardManager,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Computer,
                null,
                tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = btManager.deviceDisplayName(device) ?: "(未命名设备)",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isConnected) {
                AssistChip(
                    onClick = {},
                    label = { Text("已连接") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        labelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            } else {
                TextButton(onClick = onClick) { Text("连接") }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

// ==================== 步骤 2：推送安装包 ====================

@Composable
private fun InstallerSection(onPushInstaller: () -> Unit) {
    Column {
        Text(
            text = "电脑端需要安装接收程序 receiver.exe，用于接收手柄数据并转发到虚拟 Xbox 手柄。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onPushInstaller,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("推送安装包到电脑")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "电脑收到后双击 receiver.exe 即可完成安装（需要管理员权限），请保持 receiver.exe在前台运行中",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== 步骤 3：WiFi 桥接 ====================

@Composable
private fun WifiBridgeSection(
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

    val effectiveHost = when {
        autoPcIp != null -> autoPcIp!!
        manualHost.isNotBlank() -> manualHost
        else -> "192.168.1.100"
    }
    val isAutoIp = autoPcIp != null

    DisposableEffect(udpBridge) {
        udpBridge.onComputerOffline = { showOfflineDialog = true }
        onDispose { udpBridge.onComputerOffline = null }
    }

    if (showOfflineDialog) {
        AlertDialog(
            onDismissRequest = { showOfflineDialog = false },
            title = { Text("电脑未就绪") },
            text = {
                Text(
                    "未收到电脑接收程序的响应，请确认 receiver.exe 正在电脑上运行。\n\n" +
                        "是否现在重新推送安装包？"
                )
            },
            confirmButton = {
                TextButton(onClick = { showOfflineDialog = false; onPushInstaller() }) { Text("推送安装包") }
            },
            dismissButton = {
                TextButton(onClick = { showOfflineDialog = false }) { Text("稍后再说") }
            }
        )
    }

    Column {
        // 桥接开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "WiFi 桥接",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "通过 WiFi 传输手柄数据，延迟更低（需同局域网）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    if (it) {
                        udpBridge.setEnabled(true, effectiveHost)
                        inputBridge.setRate(InputBridge.RATE_500)
                    } else {
                        udpBridge.setEnabled(false, effectiveHost)
                        inputBridge.setRate(InputBridge.RATE_125)
                    }
                }
            )
        }

        if (enabled) {
            Spacer(Modifier.height(8.dp))
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        if (ackStatus.startsWith("电脑已确认")) "连接正常" else "等待电脑响应...",
                        color = if (ackStatus.startsWith("电脑已确认")) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                },
                leadingIcon = {
                    if (ackStatus.startsWith("电脑已确认")) {
                        Icon(Icons.Default.CheckCircle, null)
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (ackStatus.startsWith("电脑已确认")) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondaryContainer
                )
            )
        }

        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(8.dp))

        // PC IP 输入
        Text(
            text = "电脑 IP 地址",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = if (isAutoIp) effectiveHost else manualHost,
            onValueChange = { manualHost = it },
            label = { Text("例: 192.168.1.100") },
            singleLine = true,
            enabled = !enabled,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.Computer, null) }
        )

        Spacer(Modifier.height(8.dp))

        // 自动发现按钮
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
            Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("自动发现电脑 IP")
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "提示：电脑运行 receiver.exe 后，请点击「自动发现电脑 IP」按钮，再开启桥接开关。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== 步骤 4：开始游戏 ====================

@Composable
private fun GamepadSection(
    inputBridge: InputBridge,
    onEnterBlackScreen: () -> Unit,
    btManager: BluetoothKeyboardManager,
    scope: CoroutineScope
) {
    val snapshot by inputBridge.state.collectAsState()
    val controllerName by inputBridge.controllerName.collectAsState()
    var bridgeEnabled by remember { mutableStateOf(inputBridge.isEnabled) }

    Column {
        // 手柄状态
        if (controllerName != null) {
            AssistChip(
                onClick = {},
                label = { Text("检测到手柄: $controllerName") },
                leadingIcon = { Icon(Icons.Default.SportsEsports, null) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        } else {
            Text(
                text = "请通过 USB-C 连接拉伸手柄",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(16.dp))

        // 虚拟手柄布局
        Text(
            text = "点击按钮测试蓝牙 HID 输出",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        GamepadTestPad(btManager, scope)

        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(12.dp))

        // 外部手柄桥接
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("手柄桥接", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = bridgeEnabled,
                onCheckedChange = {
                    bridgeEnabled = it
                    inputBridge.setEnabled(it)
                }
            )
        }

        if (bridgeEnabled) {
            Spacer(Modifier.height(8.dp))

            // 实时状态可视化
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // 左摇杆
                JoystickVisualizer(
                    x = snapshot.leftX,
                    y = snapshot.leftY,
                    label = "左摇杆"
                )
                // 右摇杆
                JoystickVisualizer(
                    x = snapshot.rightX,
                    y = snapshot.rightY,
                    label = "右摇杆"
                )
            }

            Spacer(Modifier.height(12.dp))

            // 按键状态
            Text(
                text = "按键状态",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            ButtonGrid(snapshot.buttonMask)

            Spacer(Modifier.height(16.dp))

            // 进入游戏按钮
            Button(
                onClick = onEnterBlackScreen,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.SportsEsports, null)
                Spacer(Modifier.width(8.dp))
                Text("进入游戏模式（黑屏）")
            }
            Text(
                text = "黑屏模式下持续转发手柄数据，长按屏幕退出",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==================== 虚拟手柄测试面板 ====================

@Composable
private fun GamepadTestPad(
    btManager: BluetoothKeyboardManager,
    scope: CoroutineScope
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 肩按键 LB / RB
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ShoulderButton("LB", 0x0010, btManager, scope)
            Spacer(Modifier.weight(1f))
            ShoulderButton("RB", 0x0020, btManager, scope)
        }

        Spacer(Modifier.height(12.dp))

        // 十字键 + 功能按键
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：十字键
            DpadPad(btManager, scope)

            // 中间：Select / Start
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SmallActionButton("Select") {
                    scope.launch { tapGamepadButton(btManager, 0x0100) }
                }
                Spacer(Modifier.height(8.dp))
                SmallActionButton("Start") {
                    scope.launch { tapGamepadButton(btManager, 0x0200) }
                }
            }

            // 右侧：A/B/X/Y 菱形排列
            DiamondButtons(btManager, scope)
        }

        Spacer(Modifier.height(16.dp))

        // LT / RT 扳机
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ShoulderButton("LT", 0x0040, btManager, scope, isTrigger = true)
            Spacer(Modifier.weight(1f))
            ShoulderButton("RT", 0x0080, btManager, scope, isTrigger = true)
        }
    }
}

@Composable
private fun ShoulderButton(
    label: String,
    mask: Int,
    btManager: BluetoothKeyboardManager,
    scope: CoroutineScope,
    isTrigger: Boolean = false
) {
    var pressed by remember { mutableStateOf(false) }
    val color = when {
        pressed -> MaterialTheme.colorScheme.primary
        isTrigger -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }

    Box(
        modifier = Modifier
            .size(if (isTrigger) 56.dp else 48.dp)
            .background(color, RoundedCornerShape(12.dp))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                pressed = true
                scope.launch {
                    btManager.sendGamepadReport(mask, 0f, 0f, 0f, 0f)
                    delay(100)
                    btManager.sendGamepadReport(0, 0f, 0f, 0f, 0f)
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (pressed) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun DpadPad(
    btManager: BluetoothKeyboardManager,
    scope: CoroutineScope
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // 上
        DpadButton("↑") { scope.launch { tapGamepadButton(btManager, 0x1000) } }
        Row {
            // 左
            DpadButton("←") { scope.launch { tapGamepadButton(btManager, 0x4000) } }
            // 中
            Box(modifier = Modifier.size(40.dp))
            // 右
            DpadButton("→") { scope.launch { tapGamepadButton(btManager, 0x8000) } }
        }
        // 下
        DpadButton("↓") { scope.launch { tapGamepadButton(btManager, 0x2000) } }
    }
}

@Composable
private fun DpadButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DiamondButtons(
    btManager: BluetoothKeyboardManager,
    scope: CoroutineScope
) {
    val buttonSize = 44.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // Y
        GamepadFaceButton("Y", 0x0008, buttonSize, Color(0xFFFFEB3B), btManager, scope)
        Row {
            // X
            GamepadFaceButton("X", 0x0004, buttonSize, Color(0xFF2196F3), btManager, scope)
            Spacer(Modifier.width(44.dp))
            // B
            GamepadFaceButton("B", 0x0002, buttonSize, Color(0xFF4CAF50), btManager, scope)
        }
        // A
        GamepadFaceButton("A", 0x0001, buttonSize, Color(0xFFF44336), btManager, scope)
    }
}

@Composable
private fun GamepadFaceButton(
    label: String,
    mask: Int,
    size: androidx.compose.ui.unit.Dp,
    baseColor: Color,
    btManager: BluetoothKeyboardManager,
    scope: CoroutineScope
) {
    var pressed by remember { mutableStateOf(false) }
    val bgColor = if (pressed) baseColor.copy(alpha = 0.5f) else baseColor

    Box(
        modifier = Modifier
            .size(size)
            .background(bgColor, CircleShape)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                pressed = true
                scope.launch {
                    btManager.sendGamepadReport(mask, 0f, 0f, 0f, 0f)
                    delay(80)
                    btManager.sendGamepadReport(0, 0f, 0f, 0f, 0f)
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
private fun SmallActionButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp, 24.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(12.dp)
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== 摇杆可视化 ====================

@Composable
private fun JoystickVisualizer(x: Float, y: Float, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // 摇杆圆点
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .offset(x = Dp(x * 25f), y = Dp(y * 25f))
                    .background(
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${String.format("%+.2f", x)}, ${String.format("%+.2f", y)}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ==================== 按键网格可视化 ====================

private val GAMEPAD_BUTTON_LABELS = listOf(
    "A" to 0, "B" to 1, "X" to 2, "Y" to 3,
    "LB" to 4, "RB" to 5, "LT" to 6, "RT" to 7,
    "Sel" to 8, "Start" to 9, "L3" to 10, "R3" to 11,
    "↑" to 12, "↓" to 13, "←" to 14, "→" to 15
)

@Composable
private fun ButtonGrid(buttonMask: Int) {
    val rows = GAMEPAD_BUTTON_LABELS.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { (label, bit) ->
                    val pressed = (buttonMask and (1 shl bit)) != 0
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (pressed) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pressed) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}


// ==================== 回报率设置 ====================

private val RATE_OPTIONS = listOf(125, 250, 500, 750)
private val RATE_LABELS = mapOf(125 to "省电", 250 to "均衡", 500 to "电竞", 750 to "极致")
private val RATE_DESCRIPTIONS = mapOf(
    125 to "省电模式，适合长时间使用",
    250 to "日常游戏，性价比高",
    500 to "电竞推荐，响应灵敏",
    750 to "职业级刷新，耗电增加"
)

@Composable
private fun RateSettingSection(
    inputBridge: InputBridge,
    udpBridge: UdpBridge
) {
    val currentRate = inputBridge.getCurrentRate()
    var selectedIndex by remember { mutableStateOf(RATE_OPTIONS.indexOf(currentRate).coerceAtLeast(0)) }
    val wifiEnabled = udpBridge.isEnabled()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SportsEsports, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "回报率设置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                AssistChip(
                    onClick = {},
                    label = { Text("${RATE_OPTIONS[selectedIndex]}Hz · ${RATE_LABELS[RATE_OPTIONS[selectedIndex]]}") }
                )
            }

            Spacer(Modifier.height(12.dp))

            Slider(
                value = selectedIndex.toFloat(),
                onValueChange = { selectedIndex = it.toInt().coerceIn(0, 3) },
                onValueChangeFinished = {
                    val hz = RATE_OPTIONS[selectedIndex]
                    inputBridge.setRate(hz)
                    if (udpBridge.isEnabled()) udpBridge.setRate(hz)
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
                text = RATE_DESCRIPTIONS[RATE_OPTIONS[selectedIndex]] ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==================== 辅助函数 ====================

private fun serviceStateLabel(state: BluetoothState): String = when (state) {
    BluetoothState.Unsupported -> "设备不支持蓝牙 HID"
    BluetoothState.PermissionRequired -> "请授予蓝牙权限"
    BluetoothState.BluetoothOff -> "请开启手机蓝牙"
    BluetoothState.ProfileNotSupported -> "设备不支持 HID 协议"
    BluetoothState.ReadyDisconnected -> "就绪，等待连接"
    is BluetoothState.PairingMode -> "配对模式"
    is BluetoothState.Connected -> "已连接到 ${state.deviceName}"
}

private suspend fun tapGamepadButton(btManager: BluetoothKeyboardManager, mask: Int) {
    btManager.sendGamepadReport(mask, 0f, 0f, 0f, 0f)
    delay(60)
    btManager.sendGamepadReport(0, 0f, 0f, 0f, 0f)
}


