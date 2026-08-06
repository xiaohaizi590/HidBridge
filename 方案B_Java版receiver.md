# 方案B：用 C 重写电脑端接收程序（receiver）

> 目标：把现有 `pc_receiver/receiver.py`（Python + vgamepad）用 **C (C11)** 重写，
> 保持与手机 App 的 **UDP 协议 100% 兼容**（改动任何字节都会导致手机端对不上）。
> 交付物：绿色 exe（静态链接 MSVC 运行时，电脑无需装任何环境），体积约 1~5 MB。

---

## 1. 总体架构

```
手机 App（Kotlin/Android）                    电脑（C 接收端 receiver.exe）
┌─────────────────────────┐                 ┌──────────────────────────────────┐
│ UdpBridge 发送 24 字节包 │ ──UDP 47808──▶ │ UdpReceiver 解析 → 写入虚拟手柄    │
│ (250Hz, 小端)            │                 │ 每帧回 4 字节 ACK（小端 seq）      │
│ 显示"电脑已确认"          │ ◀──ACK 4字节─── │                                    │
└─────────────────────────┘                 │ ViGEmClient.dll (直接 C API)     │
                                           │ → ViGEmBus 驱动 → 虚拟 Xbox 360   │
                                           └──────────────────────────────────┘
```

协议与现有 Python 版完全一致，**以下字段一个都不能改**（手机端已经按此实现）。

---

## 2. 协议规范（兼容红线）

### 2.1 UDP 数据包（手机 → 电脑）

- 端口：`47808`（`UDP_PORT`）
- 包长：**24 字节**，小端序
- 布局：

| 偏移 | 类型 | 字段 | 说明 |
|------|------|------|------|
| 0 | uint32 | seq | 自增序号，用于 ACK 回显 |
| 4 | uint32 | mask | 18 位按键位图 |
| 8 | float | leftX | 左摇杆 X，-1.0 ~ 1.0 |
| 12 | float | leftY | 左摇杆 Y（Android 上推为 -1） |
| 16 | float | rightX | 右摇杆 X |
| 20 | float | rightY | 右摇杆 Y |

### 2.2 按键位图（mask，bit0 起）

| bit | 含义 | XInput 映射 |
|-----|------|-------------|
| 0 | A | XUSB_GAMEPAD_A (0x1000) |
| 1 | B | XUSB_GAMEPAD_B (0x2000) |
| 2 | X | XUSB_GAMEPAD_X (0x4000) |
| 3 | Y | XUSB_GAMEPAD_Y (0x8000) |
| 4 | LB | LEFT_SHOULDER (0x0100) |
| 5 | RB | RIGHT_SHOULDER (0x0200) |
| 6 | LT | 模拟扳机（按=255/满行程，松=0） |
| 7 | RT | 模拟扳机（按=255/满行程，松=0） |
| 8 | Select/Back | XUSB_GAMEPAD_BACK (0x0020) |
| 9 | Start | XUSB_GAMEPAD_START (0x0010) |
| 10 | L3 | LEFT_THUMB (0x0040) |
| 11 | R3 | RIGHT_THUMB (0x0080) |
| 12 | DPadUp | DPAD_UP (0x0001) |
| 13 | DPadDown | DPAD_DOWN (0x0002) |
| 14 | DPadLeft | DPAD_LEFT (0x0004) |
| 15 | DPadRight | DPAD_RIGHT (0x0008) |
| 16 | C | 忽略（XInput 无对应） |
| 17 | Z | 忽略（XInput 无对应） |

### 2.3 ACK（电脑 → 手机）

- 4 字节，小端 uint32 = 最近一包的 seq，回给**包的源地址**（`SOCKADDR_IN` 直接 `sendto`）

### 2.4 摇杆转换

- 浮点 -1.0~1.0 → short -32768~32767（`clamp(v, -1, 1) * 32767` 取整）
- **Y 轴取反**：`sThumbY = -toAxis(y)`（Android 上推 -1，XInput 上推为 +）

### 2.5 卡键保护

- 超过 **2 秒**未收到任何包 → 释放所有按键/扳机/摇杆回中（`resetAll`）

---

## 3. 关键技术选型

| 需求 | 方案 | 理由 |
|------|------|------|
| UDP | `winsock2.h` 标准 `socket()` / `recvfrom()` / `sendto()` | 原生 API，250Hz 毫无压力 |
| 虚拟手柄 | **直接链接 `ViGEmClient.lib`，调用 C API** | ViGEmBus 官方 C 客户端库，原生支持，无需 JNA/JNI |
| 驱动安装 | 捆绑官方 `ViGEmBus_Setup.exe`，`--install` 时 UAC 提权 + `/S` 静默安装 | 与 vgamepad 的 `install_driver()` 同思路 |
| UAC 提权 | `ShellExecuteW("runas", ...)` 或 `CreateProcess` 重启动自身 | Windows 原生 API，无需 PowerShell |
| 开机自启 | `RegSetValueExW` 写 `HKCU\...\Run`（HKCU 无需管理员） | Windows 注册表 API |
| 绿色打包 | **MSVC 静态链接 CRT（`/MT`）**，`Release` 配置编译 | 输出单个 exe，无运行时依赖 |
| RFCOMM 命令通道（可选） | `ws2bth.h` + `WSAStartup` + `AF_BTH` | Winsock Bluetooth 原生支持 |
| 日志输出 | `OutputDebugStringW` + `printf` 控制台双模式 | 调试用 DebugView，发布用控制台 |

### 依赖清单（头文件 + 链接库）

**Windows SDK 自带：**
- `winsock2.h` / `ws2tcpip.h` — UDP 通信
- `ws2bth.h` — RFCOMM 蓝牙（可选）
- `windows.h` — UAC、注册表、Shell
- `iphlpapi.h` — WiFi SSID 检测（可选）

**外部依赖（ViGEmBus SDK）：**
- `include/vigem-client.h` — ViGEmClient API 头文件
- `ViGEmClient.cpp` — 静态链接进 exe（直接编译，无需 DLL）
- 通过 `DeviceIoControl` 与 ViGEmBus 内核驱动通信，**不需要任何运行时 DLL**

**外部资源（运行时）：**
- `ViGEmBus_Setup.exe` — 官方驱动安装器（从 [ViGEmBus Releases](https://github.com/nefarius/ViGEmBus/releases) 下载，打包进资源用于首次安装）

---

## 4. 项目结构与文件设计

```
pc_receiver_c/
├── build.bat                 # 一键构建（见第 8 节）
├── CMakeLists.txt            # 或用 VS 工程 / 直接 cl 编译
├── include/
│   └── vigem-client.h        # ViGEmClient API 头文件（从 SDK 复制）
├── vigem_client.cpp          # ViGEmClient 实现（静态链接，DeviceIoControl 通信）
├── res/
│   └── ViGEmBus_Setup.exe    # 仅安装模式使用（从官方下载，嵌入资源）
└── src/
    ├── main.c                # 入口 + 命令行参数（--install/--uninstall）
    ├── receiver.h            # 公共类型和函数声明
    ├── protocol.c            # 24 字节包解析（小端）
    ├── vigem_wrapper.c       # ViGEmClient 封装（alloc/connect/add/update/remove）
    ├── udp_receiver.c        # 主循环：收包→解析→写手柄→回 ACK→卡键检测
    ├── button_mapper.c       # 18 bit → XUSB_BUTTON 常量映射
    ├── installer.c           # --install/--uninstall（UAC+驱动+自启）
    └── rfcomm_channel.c      # 可选：RFCOMM 命令通道客户端
```

### 职责要点

- **main.c**：解析参数；`--install` → `installer_install()`；`--uninstall` → `installer_uninstall()`；否则启动 UDP 主循环。

- **vigem_wrapper.c** 封装以下 C 函数（`vigem-client.h`）：

```c
// 完整 API 签名
PVIGEM_CLIENT    vigem_alloc(void);
VIGEM_ERROR      vigem_connect(PVIGEM_CLIENT);              // 0 = VIGEM_SUCCESS
void             vigem_free(PVIGEM_CLIENT);
VIGEM_ERROR      vigem_disconnect(PVIGEM_CLIENT);
PVIGEM_TARGET    vigem_target_x360_alloc(void);
void             vigem_target_free(PVIGEM_TARGET);
VIGEM_ERROR      vigem_target_add(PVIGEM_CLIENT, PVIGEM_TARGET);
VIGEM_ERROR      vigem_target_remove(PVIGEM_CLIENT, PVIGEM_TARGET);
VIGEM_ERROR      vigem_target_x360_update(PVIGEM_CLIENT, PVIGEM_TARGET, XUSB_REPORT);
```

- **XUSB_REPORT**（`#pragma pack(1)`，共 12 字节无 padding）：

```c
#pragma pack(push, 1)
typedef struct {
    unsigned short wButtons;       // 位或组合，见 2.2 表
    unsigned char  bLeftTrigger;   // 0~255
    unsigned char  bRightTrigger;  // 0~255
    short          sThumbLX;
    short          sThumbLY;
    short          sThumbRX;
    short          sThumbRY;
} XUSB_REPORT;
#pragma pack(pop)
```

- **protocol.c** 数据包解析（小端）：

```c
#pragma pack(push, 1)
typedef struct {
    uint32_t seq;
    uint32_t mask;
    float    leftX;
    float    leftY;
    float    rightX;
    float    rightY;
} GamepadPacket;
#pragma pack(pop)

// 直接 memcpy 到结构体即可（网络字节序已是小端）
void parse_packet(const uint8_t* buf, GamepadPacket* pkt) {
    memcpy(pkt, buf, 24);
}
```

---

## 5. 核心逻辑骨架（供实现参考）

### 5.1 UDP 主循环（伪代码）

```
WSAStartup → socket(AF_INET, SOCK_DGRAM, 0) → bind 0.0.0.0:47808
创建虚拟手柄（失败 → 提示先运行 --install，退出码 1）
lastMask = 0; lastRecv = now
循环：
    select() 或 recvfrom()（带 500ms 超时）
    若超时：
        if now - lastRecv > 2000ms && lastMask != 0: resetAll(); lastMask = 0
        continue
    if packet_len < 24: continue
    seq, mask, lx, ly, rx, ry = 小端解析
    sendto(4字节小端 seq 给源地址)              // ACK
    // 按键：只对变化位 press/release
    changed = mask ^ lastMask
    for bit in 0..17:
        if changed & (1<<bit):
            report.wButtons |= BTN[bit] 或 &= ~BTN[bit]
    // LT/RT 扳机
    report.bLeftTrigger  = (mask & (1<<6)) != 0 ? 255 : 0
    report.bRightTrigger = (mask & (1<<7)) != 0 ? 255 : 0
    // 摇杆
    report.sThumbLX = toAxis(lx); report.sThumbLY = toAxis(-ly)
    report.sThumbRX = toAxis(rx); report.sThumbRY = toAxis(-ry)
    vigem_target_x360_update(client, target, report)
    每秒打印一次 "接收 N 帧/秒 | 来源 ip | 最新 seq=N"
```

### 5.2 VirtualGamepad 生命周期

```c
PVIGEM_CLIENT client = vigem_alloc();
vigem_connect(client);
PVIGEM_TARGET target = vigem_target_x360_alloc();
vigem_target_add(client, target);

// 循环: vigem_target_x360_update(client, target, report);

vigem_target_remove(client, target);
vigem_target_free(target);
vigem_disconnect(client);
vigem_free(client);
```

### 5.3 installer_install()

1. 先测驱动：`vigem_connect` + 创建手柄能成功即已装好，跳过 2。
2. 未装驱动：检测是否管理员（`IsUserAnAdmin()`），非管理员 → `ShellExecuteW(NULL, L"runas", exe_path, L"--install", NULL, SW_SHOWNORMAL)` 提权重启自身（弹一次 UAC）。
3. 管理员下：释放内置 `ViGEmBus_Setup.exe` 到临时目录（`WriteFile` 写入资源），`ShellExecuteW(NULL, L"open", temp_setup, L"/S", NULL, SW_HIDE)` 静默运行，`WaitForSingleObject` 等待结束。
4. 复制自身 exe 到 `%LOCALAPPDATA%\HidReceiver\receiver.exe`（`CopyFileW`）。
5. `RegCreateKeyExW` + `RegSetValueExW` 写 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`，值名 `HidReceiver`。
6. 打印"安装完成，已注册开机自启"。

### 5.4 installer_uninstall()

1. `IsUserAnAdmin()` 检测，非管理员则 `ShellExecuteW` 提权。
2. `RegDeleteValueW` 删自启注册表值（不存在则忽略，`ERROR_FILE_NOT_FOUND` 静默）。
3. `RemoveDirectoryW` 删 `%LOCALAPPDATA%\HidReceiver` 目录。
4. 提示驱动如需彻底移除请到设备管理器（可能被其他程序占用）。

---

## 6. 控制台输出（中文，供排障）

```
[receiver] 监听 UDP 端口 47808，等待手机连接...
[receiver] 手机 App → WiFi 桥接卡片 → 填入本机 IP 并打开开关
[receiver] 接收 250 帧/秒 | 来源 192.168.1.5 | 最新 seq=12345
[receiver] 超过 2 秒未收到数据（手机断开？），已释放所有按键
```

---

## 7. RFCOMM 命令通道（可选模块，可最后做）

- 现状：Windows 蓝牙 SPP 通道，用 Winsock2 原生 API 实现。
- 实现步骤：
  1. `WSAStartup` 初始化（AF_BTH 需要）。
  2. `WSALookupServiceBeginW` 按 SPP UUID `00001101-0000-1000-8000-00805F9B34FB` 发现手机的通道号。
  3. `socket(AF_BTH=32, SOCK_STREAM, BTHPROTO_RFCOMM=3)` → `connect` 到 `SOCKADDR_BTH`。
  4. 收发文本行协议（UTF-8，`\n` 分隔）。
- **复杂度和风险**：Winsock 蓝牙 API 的 SDP 查询在 Windows 上较繁琐，建议单独抽一个 `.c` 文件，失败不影响 UDP 数据通道。若成本过高，可暂缓实现并在 README 说明（当前阶段 UDP 已够用）。

```c
// RFCOMM 核心代码预览
SOCKET s = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
SOCKADDR_BTH addr = {0};
addr.btAddr = phone_bt_addr;
addr.serviceClassId = RFCOMM_SPP_UUID;
addr.port = channel_number;  // 从 SDP 查询获得
connect(s, (SOCKADDR*)&addr, sizeof(addr));
// 之后用 send()/recv() 收发文本命令
```

---

## 8. 构建与交付

### 方式 A：MSVC 命令行（推荐）

`build.bat` 流程：

```batch
@echo off
REM 检查 Visual Studio 环境
where cl >nul 2>nul
if errorlevel 1 (
    echo 请在 "x64 Native Tools Command Prompt for VS" 中运行此脚本
    pause
    exit /b 1
)

REM 创建输出目录
if not exist build mkdir build

REM 编译 Release 配置（静态链接 CRT，单文件 exe）
cl /O2 /MT /W3 ^
   /D_WIN32_WINNT=0x0A00 ^
   /Fe:build\receiver.exe ^
   src\main.c ^
   src\protocol.c ^
   src\vigem_wrapper.c ^
   src\udp_receiver.c ^
   src\button_mapper.c ^
   src\installer.c ^
   vigem_client.cpp ^
   /I include ^
   ViGEmClient.lib ws2_32.lib ole32.lib shell32.lib advapi32.lib

if errorlevel 1 (
    echo 编译失败
    pause
    exit /b 1
)

REM 复制安装资源（嵌入资源时不需要）
copy res\ViGEmBus_Setup.exe build\ 2>nul

echo.
echo 构建完成：build\receiver.exe
echo 预估体积：1~5 MB（零 DLL 依赖，单文件即可分发）
pause
```

### 方式 B：CMake（跨平台可选）

```cmake
cmake_minimum_required(VERSION 3.15)
project(receiver C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# 静态链接 CRT
if(MSVC)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")
    add_compile_definitions(_WIN32_WINNT=0x0A00)
endif()

add_executable(receiver
    src/main.c
    src/protocol.c
    src/vigem_wrapper.c
    src/udp_receiver.c
    src/button_mapper.c
    src/installer.c
    src/rfcomm_channel.c   # 可选
    vigem_client.cpp       # ViGEmClient 静态链接进 exe
)

target_include_directories(receiver PRIVATE include)
target_link_libraries(receiver PRIVATE
    ws2_32.lib
    ole32.lib
    shell32.lib
    advapi32.lib
)
```

### 编译要求

- Visual Studio 2019/2022（社区版免费），使用 **x64 Native Tools Command Prompt**
- Windows SDK 10+（提供 `ws2bth.h`、`windows.h` 等）
- 预估体积：**1~5 MB**（比 Python 方案小 10~100 倍）

---

## 9. 必须保持不变的兼容清单（改了就断连）

- [ ] UDP 端口 47808
- [ ] 24 字节小端包布局（seq/mask/4×float）
- [ ] ACK 4 字节小端 seq，回源地址
- [ ] 按键位图 bit0~17 映射表（含 bit16/17 忽略）
- [ ] 摇杆 -32768~32767 换算 + Y 轴取反
- [ ] LT/RT 用模拟扳机（0 或 255），不是按键位
- [ ] 2 秒卡键保护
- [ ] 安装目录 `%LOCALAPPDATA%\HidReceiver\receiver.exe` + 自启项 `HidReceiver`

---

## 10. 风险与注意

1. **ViGEmBus 驱动安装**：`ViGEmBus_Setup.exe` 是内核驱动安装器，首次使用时需要 UAC 提权。建议通过资源嵌入方式打包进 `receiver.exe`，`--install` 时自动释放并静默安装。
2. **防火墙**：首次运行 Windows 弹防火墙提示，需允许（专用网络）——C 程序同样会弹，不是 bug。
3. **提权**：驱动安装必须过一次 UAC，无法完全无感（内核驱动要求），安装后自启无需管理员。
4. **单线程足够**：250Hz UDP + ViGEmBus 更新在同一条线程即可，无需多线程（与摇杆卡顿问题同理，不要引入并发发送队列）。
5. **`#pragma pack`**：所有协议结构体必须用 `#pragma pack(push, 1)` 保证无 padding，否则字段偏移错位。
6. **小端假设**：x86/x64 本身就是小端，直接 `memcpy` 即可；如需跨平台（ARM），加 `_byteswap_ulong` 处理。
7. **资源嵌入**：`ViGEmBus_Setup.exe` 可通过 Windows `.rc` 资源文件嵌入 exe 内部，运行时用 `FindResourceW` / `LockResource` 释放到临时目录再执行，实现单文件分发。
