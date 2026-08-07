# 手柄桥 (HidBridge)

把 Android 手机变成一只"无线游戏手柄"，通过蓝牙 HID + WiFi UDP 桥接，实现低延迟的 PC 游戏体验。

---

## 目录

- [架构概览](#架构概览)
- [协议规范](#协议规范)
- [回报率档位](#回报率档位)
- [手机端架构](#手机端架构)
- [PC 端架构](#pc-端架构)
- [部署与使用](#部署与使用)
- [构建说明](#构建说明)
- [技术栈](#技术栈)
- [风险与边界](#风险与边界)
- [待办清单](#待办清单)

---

## 架构概览

```
┌─────────────────── 手机 Android ───────────────────┐     ┌─────────────────── 电脑 Windows ───────────────────┐
│                                                     │     │                                                     │
│  ① BluetoothHidDevice                               │     │  Windows 内置 HID 驱动                              │
│     注册为 HID 设备（键盘+鼠标+手柄组合）              │────▶│  即插即用，无需任何安装                               │
│     发送 HID Report（11 字节手柄）                    │     │                                                     │
│                                                     │     │                                                     │
│  ② WifiCommandBridge (RFCOMM 服务端)                 │     │  receiver.exe (C)                                    │
│     BluetoothServerSocket 监听                        │◀────│     ③ RFCOMM 客户端连接                               │
│     文本行协议：JSON 命令 ←── 状态机                   │     │     解析命令 / 回传 IP / 心跳                          │
│                                                     │     │                                                     │
│  ③ UdpBridge                                        │     │  receiver.exe (C)                                    │
│     可变回报率发送 24 字节手柄包                      │────▶│     ④ UDP 接收 → 解析 → ViGEmBus → 虚拟 Xbox 360       │
│     等待 4 字节 ACK 确认                              │◀────│     每帧回 ACK                                        │
│                                                     │     │                                                     │
└─────────────────────────────────────────────────────┘     └─────────────────────────────────────────────────────┘
```

### 数据流向

1. **手机 → 蓝牙 HID**：按键/摇杆数据通过蓝牙 HID Report 发送，电脑识别为标准 HID 设备（无需驱动）
2. **手机 → WiFi UDP**：高频手柄数据通过局域网 UDP 发送到 PC 端 receiver.exe，实现低延迟
3. **RFCOMM 控制通道**：蓝牙 SPP 通道，用于 WiFi 同网检测、IP 交换、驱动状态确认

---

## 协议规范

### RFCOMM 命令通道

文本行协议，UTF-8，`\n` 分隔。

| 方向 | 命令 | 说明 |
|------|------|------|
| 手机→PC | `{"cmd":"wifi_info","ssid":"MyWiFi","ip":"192.168.1.5"}` | WiFi 同网检测 |
| PC→手机 | `{"cmd":"wifi_ok","pc_ip":"192.168.1.100"}` | 同网 + 回传 PC IP |
| PC→手机 | `{"cmd":"wifi_mismatch","my_ssid":"OtherWiFi"}` | 不同网 |
| 手机→PC | `{"cmd":"install_driver"}` | 请求检查/安装驱动 |
| PC→手机 | `{"cmd":"driver_ok"}` | 驱动已就绪 |
| PC→手机 | `{"cmd":"driver_installed_ok"}` | 驱动安装完成 |
| 手机→PC | `{"cmd":"start_udp"}` | 启动 UDP 数据通道 |
| PC→手机 | `{"cmd":"udp_ready"}` | UDP 通道就绪 |
| 手机→PC | `ping` | 心跳（每 2 秒） |
| PC→手机 | `pong` | 心跳应答 |
| 手机→PC | `{"cmd":"set_rate","hz":500}` | 切换回报率 |

### UDP 数据包（手机 → 电脑）

- **端口**：47808
- **包长**：24 字节，小端序

| 偏移 | 类型 | 字段 | 说明 |
|------|------|------|------|
| 0 | uint32 | seq | 自增序号 |
| 4 | uint32 | mask | 18 位按键位图 |
| 8 | float | leftX | 左摇杆 X，-1.0 ~ 1.0 |
| 12 | float | leftY | 左摇杆 Y（Android 上推为 -1） |
| 16 | float | rightX | 右摇杆 X |
| 20 | float | rightY | 右摇杆 Y |

### 按键位图（mask）

| bit | 含义 | XInput 映射 |
|-----|------|-------------|
| 0 | A | 0x1000 |
| 1 | B | 0x2000 |
| 2 | X | 0x4000 |
| 3 | Y | 0x8000 |
| 4 | LB | 0x0100 |
| 5 | RB | 0x0200 |
| 6 | LT | 模拟扳机（按=255） |
| 7 | RT | 模拟扳机（按=255） |
| 8 | Select/Back | 0x0020 |
| 9 | Start | 0x0010 |
| 10 | L3 | 0x0040 |
| 11 | R3 | 0x0080 |
| 12 | DPadUp | 0x0001 |
| 13 | DPadDown | 0x0002 |
| 14 | DPadLeft | 0x0004 |
| 15 | DPadRight | 0x0008 |
| 16 | C | 忽略 |
| 17 | Z | 忽略 |

### ACK（电脑 → 手机）

4 字节，小端 uint32 = 最近一包的 seq，回给包的源地址。

### 摇杆转换

- float -1.0~1.0 → short -32768~32767
- Y 轴取反：`sThumbY = -toAxis(y)`（Android 上推 -1，XInput 上推 +）

### 卡键保护

超过 2 秒未收到数据 → 释放所有按键回中。

---

## 回报率档位

| 档位 | 回报率 | 间隔 | 场景 | 耗电 |
|------|--------|------|------|------|
| 省电 | 125Hz | 8ms | 蓝牙默认 | 低 |
| 均衡 | 250Hz | 4ms | 日常 | 较低 |
| 电竞 | 500Hz | 2ms | WiFi 默认 | 较高 |
| 极致 | 750Hz | ~1.3ms | 电竞 | 高 |

---

## 手机端架构

### 核心文件

| 文件 | 说明 |
|------|------|
| `bluetooth/BluetoothKeyboardManager.kt` | 核心引擎：HID 注册、配对、连接、键盘/鼠标/手柄报表发送 |
| `input/InputBridge.kt` | 外部手柄输入桥接：截获物理手柄事件，映射为 HID 报表 |
| `input/UdpBridge.kt` | UDP 发送端：可变回报率、ACK 确认、PC IP 自动发现 |
| `wifi/WifiCommandBridge.kt` | RFCOMM 通道管理（服务端监听、文本行收发） |
| `wifi/WifiCommandHandler.kt` | 命令解析 + 状态机 + 心跳 + WiFi 同网检测 |
| `ui/HidScreen.kt` | 主界面：蓝牙连接、WiFi 桥接、回报率滑块、虚拟手柄测试 |
| `service/HidKeepAliveService.kt` | 前台保活服务，防止系统杀进程 |
| `MainActivity.kt` | 入口：权限申请 + 组件初始化 |

### HID 报表

设备注册为 **键盘 + 鼠标 + 手柄组合设备**（SDP 子类 `SUBCLASS1_COMBO`）。

| Report ID | 类型 | 长度 | 说明 |
|-----------|------|------|------|
| 1 | 键盘 | 8 字节 | 修饰键位图 + 6 键 NKRO |
| 2 | 鼠标 | 4 字节 | 按键 + X/Y 相对位移 + 滚轮 |
| 3 | 手柄 | 11 字节 | 18 按键位图 + 左/右摇杆 |

### 连接状态机

```
Idle → WaitingWifi → WifiOk(pcIp) → Ready → UDP 数据通道运行
                  ↘ WifiMismatch → 提示用户切换 WiFi
```

### 外部手柄输入

支持 USB-C 拉伸手柄（OTG），蓝牙手柄不可行（与 HID Device 角色射频冲突）。

处理流程：`dispatchKeyEvent` / `dispatchGenericMotionEvent` → `InputBridge` → `BluetoothKeyboardManager.sendGamepadReport`

---

## PC 端架构

### receiver.exe（C 语言，静态链接）

单文件绿色 exe，零 DLL 依赖，体积 1~5 MB。

| 需求 | 方案 |
|------|------|
| UDP 通信 | `winsock2.h` 原生 API |
| 虚拟手柄 | ViGEmClient C API（静态链接进 exe） |
| 驱动安装 | 资源嵌入 `ViGEmBus_Setup.exe`，`/S` 静默安装 |
| UAC 提权 | `ShellExecuteW("runas", ...)` |
| 开机自启 | `RegSetValueExW` 写 `HKCU\...\Run` |
| 后台运行 | `ShowWindow(GetConsoleWindow(), SW_HIDE)` 隐藏控制台 |
| RFCOMM（可选） | `ws2bth.h` + `AF_BTH` |

### XUSB_REPORT（虚拟手柄结构）

```c
#pragma pack(push, 1)
typedef struct {
    unsigned short wButtons;
    unsigned char  bLeftTrigger;
    unsigned char  bRightTrigger;
    short          sThumbLX;
    short          sThumbLY;
    short          sThumbRX;
    short          sThumbRY;
} XUSB_REPORT;
#pragma pack(pop)
```

### UDP 主循环

```
WSAStartup → socket(AF_INET, SOCK_DGRAM, 0) → bind 0.0.0.0:47808
创建 ViGEmClient + 虚拟 Xbox 360 手柄
循环：
    recvfrom() 收包
    解析 24 字节 → GamepadPacket
    sendto(4 字节小端 seq) ACK
    按键位图 → XInput 按钮
    摇杆 float→short + Y 轴取反
    vigem_target_x360_update() 写入虚拟手柄
    2 秒无数据 → 卡键保护，释放所有按键
```

### 构建

```batch
cl /O2 /MT /W3 /D_WIN32_WINNT=0x0A00 ^
   /Fe:receiver.exe ^
   src\main.c src\protocol.c src\vigem_wrapper.c ^
   src\udp_receiver.c src\button_mapper.c src\installer.c ^
   src\ip_discovery.c src\rfcomm_channel.c ^
   vigem_client.cpp ^
   /I include ^
   ws2_32.lib ole32.lib shell32.lib advapi32.lib wlanapi.lib
```

---

## 部署与使用

### 硬件要求

| 项目 | 要求 |
|------|------|
| 手机 | Android 9+（API 28+），蓝牙支持 HID Device 角色 |
| 电脑 | Windows 10/11，蓝牙适配器 |
| 拉伸手柄 | USB-C 连接（蓝牙手柄与 HID 冲突） |
| WiFi | 手机和电脑同一局域网（WiFi 桥接需要） |

### 首次安装（PC 端）

1. 手机 App → "推送安装包到电脑" → 蓝牙分享 `receiver.exe`
2. 电脑右键以管理员身份运行 `receiver.exe --install`
3. 程序自动完成：安装 ViGEmBus 驱动 → 复制到 `%LOCALAPPDATA%\HidReceiver\` → 注册开机自启 → 显示托盘图标

### 手机 App 安装

```bash
./gradlew assembleRelease
# adb install app-release.apk
```

首次打开需授予蓝牙、位置、附近设备权限。

### 日常使用流程

```
1. 手机 + 电脑蓝牙配对
   ↓
2. 手机打开 App → 自动注册 HID 设备
   ↓
3. RFCOMM 自动连接 → WiFi 同网检测
   ↓
4. 自动发现 PC IP → 启动 UDP 数据通道
   ↓
5. 手柄数据通过 WiFi 低延迟传输
   ↓
6. 关闭 App → 自动回退蓝牙 HID
```

### 三种使用模式

| 模式 | 数据路径 | 延迟 | 适用场景 |
|------|----------|------|----------|
| 纯蓝牙 HID | 手机→蓝牙→电脑 | 20-50ms | 省电，无线自由 |
| WiFi 桥接 | 手机→WiFi UDP→电脑 | 2-5ms | 电竞，低延迟 |
| 混合模式 | 手机同时发蓝牙+WiFi | 自动切换 | WiFi 断流自动回退蓝牙 |

### 常见问题

| 问题 | 解决 |
|------|------|
| 扫描不到电脑 | 确认电脑蓝牙可发现 + 权限已授予 |
| 配对成功连不上 | 取消配对 → 重新配对；确认 Android ≥ 9 |
| 电脑没识别手柄 | 检查"控制面板→游戏控制器"；断开重连 |
| WiFi 桥接连不上 | 确认同 WiFi + receiver.exe 运行 + 防火墙例外 |
| 托盘图标消失 | 运行 `%LOCALAPPDATA%\HidReceiver\receiver.exe` |
| 驱动安装失败 | 确保管理员权限；手动安装 ViGEmBus |
| 按键卡顿 | 切换 WiFi 桥接 + 提高回报率到 500Hz+ |

---

## 构建说明

### Android 端

前置：Android SDK 36（`local.properties` 或 `ANDROID_HOME`）

```bash
./gradlew assembleRelease
```

APK 输出：`app/build/outputs/apk/release/app-release.apk`

### 目录结构

```
BluetoothHID-Demo/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/installer/
│   │   └── receiver.exe              # PC 端接收器，随 APK 打包
│   ├── java/dev/hid/demo/
│   │   ├── MainActivity.kt
│   │   ├── bluetooth/
│   │   │   └── BluetoothKeyboardManager.kt
│   │   ├── input/
│   │   │   ├── InputBridge.kt
│   │   │   └── UdpBridge.kt
│   │   ├── service/
│   │   │   └── HidKeepAliveService.kt
│   │   ├── ui/
│   │   │   ├── HidScreen.kt
│   │   │   └── BlackScreen.kt
│   │   └── wifi/
│   │       ├── WifiCommandBridge.kt
│   │       └── WifiCommandHandler.kt
│   └── res/
│       ├── drawable/ mipmap-anydpi-v26/ values/ xml/
├── gradle/
│   ├── wrapper/
│   └── libs.versions.toml
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 技术栈

| 层 | 技术 |
|----|------|
| 手机 | Kotlin 2.2 / Jetpack Compose (Material3) / AGP 9.2 |
| 手机 minSdk | 28（Android 9，`BluetoothHidDevice` 引入） |
| 手机 targetSdk | 36 |
| PC 端 | C (C11) / MSVC 静态链接 CRT |
| PC 端依赖 | ViGEmBus SDK（虚拟手柄）、ws2_32（UDP/RFCOMM） |

无第三方业务库，仅官方 AndroidX 依赖。

---

## 风险与边界

| 情况 | 处理 |
|------|------|
| 手机/PC 不同 WiFi | 提示用户切换，蓝牙 HID 继续可用 |
| 用户拒绝驱动安装 | 蓝牙 HID 模式继续可用 |
| RFCOMM 连接失败 | 手动输入 PC IP，蓝牙 HID 单独可用 |
| PC 未运行 receiver | 蓝牙 HID 正常，WiFi 部分不可用 |
| 手机锁屏/WiFi 休眠 | WifiLock 保活 + RFCOMM 心跳 |
| 手机不支持 HID Device | 极少数设备不支持，需 Android 9+ |
| 防火墙拦截 | 首次运行弹提示，需允许专用网络 |
| 内核驱动提权 | 首次安装需 UAC，安装后自启无需管理员 |

---

## 待办清单

- [x] 手机端 HID 注册 + 键盘/鼠标/手柄报表
- [x] 手机端外部手柄输入桥接（InputBridge.kt）
- [x] 手机端 RFCOMM 命令通道（WifiCommandBridge.kt）
- [x] 手机端命令状态机（WifiCommandHandler.kt）
- [x] 手机端 UDP 桥接（UdpBridge.kt）
- [x] 手机端回报率滑块 UI（125/250/500/750Hz）
- [x] 手机端自动 IP 发现
- [x] PC 端基础 UDP 接收 + ViGEmBus
- [x] PC 端 24 字节包解析 + ACK
- [x] PC 端按键映射 + 摇杆转换
- [x] PC 端卡键保护
- [x] PC 端驱动安装 + 开机自启
- [x] PC 端后台运行 + 托盘隐藏
- [x] PC 端 UDP 广播发现（端口 47809）
- [ ] PC 端 RFCOMM 客户端（连接手机命令通道）
- [ ] PC 端命令处理（wifi_info / start_udp 等）
- [ ] 端到端联调测试
- [ ] 低电量/高负载降级策略
- [ ] 按键延迟优化（合并节流层 + ByteBuffer 复用）