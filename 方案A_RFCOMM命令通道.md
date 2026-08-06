# WiFi 桥接方案（RFCOMM 命令通道 + UDP 数据通道）

## 架构

```
手机端                                        PC 端 (receiver.py 升级版)
┌─────────────────────┐                      ┌──────────────────────────────┐
│ BluetoothHidDevice  │ ── HID 报表 ──────▶ │ Windows 内置 HID 驱动        │
│ (纯手柄，现有)      │                      │                              │
│                     │                      │                              │
│ BluetoothServer     │ ── RFCOMM ────────▶ │ RFCOMM 客户端（新）────────  │
│ Socket (新增)       │   控制命令通道       │  接收命令 / 返回状态          │
│   发命令：          │                      │  ├ "wifi_info": 收到手机SSID  │
│   - wifi_info       │                      │  │   对比自己SSID → 返回同网 │
│   - install_driver  │                      │  ├ "install_driver": 弹出    │
│   - start_udp       │                      │  │   安装确认 → pnputil装驱动│
│   - ping (心跳)     │                      │  └ "ping": 心跳应答          │
│                     │                      │                              │
│ UdpBridge (现有)    │ ── UDP 报表 ──────▶ │ ViGEmBus → 虚拟 Xbox 手柄   │
│ 250Hz 手柄数据      │        WiFi         │                              │
└─────────────────────┘                      └──────────────────────────────┘
```

## 流程

### 阶段1：蓝牙 HID 连接（现有，不变）
- 手机端现有蓝牙 HID 配对 + 连接流程照旧
- 连接成功后 HID 描述符声明为纯手柄，电脑可直接用（DirectInput）

### 阶段2：建立 RFCOMM 控制通道 
- 手机端启动 BluetoothServerSocket（RFCOMM，固定 UUID）→ `WifiCommandBridge.kt`
- PC receiver 作为 RFCOMM 客户端主动连接手机（`--phone-addr` 参数，SDP 找通道）
- 连接成功后双方通过文本行协议交换命令/响应
- 通道断开自动重新监听；UI 显示"命令通道：监听中/已连接/已断开"
这里改成了项目自带的安装包，不需要额外安装，这个安装包会通过蓝牙推送到电脑，电脑直接安装运行就可以监听 RFCOMM 通道。

### 阶段3：WiFi 同网确认 + IP 交换
1. 手机通过 RFCOMM 发：`wifi_info {"ssid":"MyWiFi","ip":"192.168.1.5"}`
2. PC receiver 收到后对比自己当前 WiFi SSID
3. 同网 → 回复：`wifi_ok {"ssid":"MyWiFi","pc_ip":"192.168.1.100"}`
4. 不同网 → 回复：`wifi_mismatch {"my_ssid":"OtherWiFi"}` → 手机提示用户
5. 手机没开 WiFi → 发 `wifi_info {"ip":"192.168.1.5"}` 不带 ssid → PC 收到后尝试 ping 手机 IP，通就确认

### 阶段4：检测驱动 + 引导安装（落地选型：方案 B，已实现基础设施）

> 决策（2026-08-06）：采用 **方案 B——手机蓝牙推安装包**。电脑端装好后保留，下次直接连接。

- **手机端**：App"WiFi 桥接"卡片新增"推送安装包到电脑"按钮 → 系统文件选择器选 receiver.exe → ACTION_SEND 蓝牙/其他方式分享给电脑（Windows 自带蓝牙文件接收）
- **电脑端**：receiver.exe 双击运行 `--install`
  1. 检测 ViGEmBus 驱动 → 未装则弹一次 UAC（用户点确认）→ `vgamepad.install_driver()` 静默装
  2. 复制自身到 `%LOCALAPPDATA%\HidReceiver\receiver.exe`（固定目录，不删）
  3. 注册 HKCU 开机自启 → 下次开机/手动运行直接可用
- `--uninstall`：移除自启 + 删除安装目录
- 打包：`build_exe.bat`（pyinstaller --onefile，电脑无需装 Python）

原阶段4 的"RFCOMM 发 install_driver 命令"逻辑保留为可选（当 receiver 已运行时手机可远程触发），但**首次安装走方案 B 蓝牙推包**。

### 阶段5：启动 UDP 数据通道
1. 手机收到 `driver_ok` 或 `driver_installed_ok` → 自动填入 PC IP 并启动 UdpBridge
2. PC receiver 启动 ViGEmBus 虚拟手柄 + UDP 监听
3. 手机发 `start_udp` → PC 回复 `udp_ready` → 发送手柄报表
4. 手机 App UI 显示"WiFi 桥接已自动启用（250Hz UDP）"

### 阶段6：WiFi 断流自动回退
- RFCOMM 通道持续心跳：手机每 2 秒发 `ping` → PC 回复 `pong`
- UdpBridge 每次 send 记录时间戳
- 连续 3 秒未收到 UDP 帧 → 判定 WiFi 断流
- 自动回退到蓝牙 HID 通道，App 状态提示："WiFi 断流，已切换为蓝牙 HID"
- WiFi 恢复后自动切回 UDP

---

## 回报率滑动模块

### 档位与默认值

| 档位 | 回报率 | 发送间隔 | 适用连接 | 耗电等级 |
|---|---|---|---|---|
| 最低 | 125Hz | 8ms | 蓝牙（默认）| 低 |
| 中低 | 250Hz | 4ms | - | 较低 |
| 较高 | 500Hz | 2ms | WiFi（默认）| 较高 |
| 最高 | 750Hz | ~1.33ms | - | 高 |

### 默认规则（自动切换）

- **蓝牙连接** → 默认 125Hz（与蓝牙物理上限一致，[InputBridge] 现有 8ms 节流不动）
- **WiFi 连接** → 默认 500Hz（[UdpBridge] 现有 `SEND_INTERVAL_MS` 从 4L 改为 2L）
- 用户手动滑动调整后，档位偏好保存在 SharedPreferences，下次连接沿用

### 耗电提示

- 回报率越高，手机蓝牙/WiFi 射频唤醒越频繁，**耗电越高**（125Hz → 750Hz 预计耗电增加 30%~60%）
- 档位文字说明常驻显示（在滑块下方）：
  - 125Hz：省电模式，蓝牙链路
  - 250Hz：均衡，日常游戏够用
  - 500Hz：WiFi 推荐，电竞手感
  - 750Hz：极致刷新，耗电明显增加，仅 WiFi 建议使用

### UI 设计

- 位置：WiFi 桥接卡片下方（或设置页），标题"回报率"
- 组件：`Slider`（4 档，SnapToValue）+
  - 当前档位数值文字（"当前：500Hz"）
  - 耗电提示（"⚠ 越高越耗电：125Hz→750Hz 耗电增加约 30%~60%"）
  - 自动/手动标识（"已随连接自动切换" / "手动设置"）

### 代码落地位置

- `BluetoothKeyboardManager` / `InputBridge`：`minSendIntervalMs` 已存在（8ms=125Hz），作为蓝牙档位基准
- `UdpBridge`：`SEND_INTERVAL_MS` 常量改为运行时可变字段，档位映射：
  ```kotlin
  val RATE_TO_INTERVAL = mapOf(125 to 8L, 250 to 4L, 500 to 2L, 750 to 1L)
  ```
- 连接模式变更（蓝牙↔WiFi）时自动应用对应默认档位，若用户手动设过则沿用手动值


---

## 手机端新增文件

### `app/src/main/java/dev/hid/demo/wifi/WifiCommandBridge.kt`
- BluetoothServerSocket 监听 RFCOMM
- 命令编码/解析（文本行 JSON）
- 协程：接收命令响应 + 心跳
- StateFlow：连接状态 / WiFi 同网状态 / 驱动安装状态 / PC IP

### 流程状态机
- Idle → Connecting(RFCOMM) → Connected
- Connected 下子状态：WiFiChecking → WiFiOk → DriverChecking → Ready / NeedInstall
- NeedInstall 下子状态：WaitingUserConfirm → Installing → Installed / InstallFailed
- Ready 下：启动 UdpBridge，进入 UDP 模式
- 任何阶段的 UdpBridge 断流 → 自动回退蓝牙

---

## PC 端新增 / 修改文件

### `pc_receiver/receiver.py`（大改）
- 增加 RFCOMM 客户端连接手机（PyBluez / PyBluez3 库）
- 增加文本行协议处理（接收 `wifi_info` / `install_driver` / `start_udp` / `ping`）
- 增加 WiFi SSID 检测（`subprocess(["netsh", "wlan", "show", "interfaces"])` 解析）
- 增加驱动检测（查 ViGEmBus 注册表项）
- 增加 GUI 安装确认弹窗（Tkinter 或 Windows API MessageBox）
- 增加 `pnputil` 静默安装调用
- 原有 UDP 监听 + ViGEmBus 虚拟手柄逻辑保留

### `pc_receiver/install_driver.bat`（保留，备用手动方案）

### `pc_receiver/requirements.txt`
```
vgamepad>=0.1.0
pybluez3>=0.30   # 或 PyBluezWin10
```

---

## 风险与边界情况

| 情况 | 处理 |
|---|---|
| 手机/PC 不在同一 WiFi | RFCOMM 同网确认失败，提示用户切到同一 WiFi，蓝牙 HID 继续工作 |
| 用户拒绝安装驱动 | UI 显示"已取消"，蓝牙 HID 模式继续可用 |
| 驱动安装失败（权限/UAC拒绝）| 显示错误信息 + 手动安装指引 |
| RFCOMM 连接失败 | 降级为用户手动操作：在 App 里手填电脑 IP，蓝牙 HID 单独用 |
| PC 未运行 receiver.py | 蓝牙HID 正常连接，WiFi 部分不工作，用户手动启动 receiver 后可用 |
| 手机锁屏 / WiFi 休眠 | 现有 WifiLock 保活，RFCOMM 心跳 |
| 手机不支持 RFCOMM | Android 9+ 普遍支持；极个别设备降级为方案 B |

---

## 实现优先级

1. 手机端 WifiCommandBridge（RFCOMM + 状态机）← 核心新增
2. PC 端 receiver.py 升级（RFCOMM + 命令处理 + 驱动检测安装）
3. 手机端 UI：WiFi 桥接卡片升级（自动/手动模式切换 + 安装引导）
4. 自动回退逻辑（UDP 断流 → 蓝牙 HID）
5. 边缘情况处理 + 错误提示打磨
