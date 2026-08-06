# Bluetooth HID Demo

把 Android 手机变成一只"无线键盘 + 鼠标 + 游戏手柄"组合设备的最小实现。

核心代码提取自 [Bluke](https://github.com/arnav-kr/Bluke)，仅保留蓝牙 HID 方案本身，并用一个极简 Compose 界面验证方案。完整设计见 [方案说明.md](方案说明.md)。

## 目录结构

```
BluetoothHID-Demo/
├── 方案说明.md                          # 蓝牙 HID 方案设计文档
├── settings.gradle.kts / build.gradle.kts / gradle.properties
├── gradle/
│   ├── wrapper/                         # Gradle 9.5.1 Wrapper（随项目移动可独立构建）
│   └── libs.versions.toml
└── app/src/main/
    ├── AndroidManifest.xml
    └── java/dev/hid/demo/
        ├── MainActivity.kt              # 入口：权限 + 引擎初始化
        ├── bluetooth/
        │   └── BluetoothKeyboardManager.kt   # ★ 核心引擎（HID 全流程）
        └── ui/
            └── HidScreen.kt             # 最小验证 UI
```

## 构建

前置：Android SDK 36（`local.properties` 或 `ANDROID_HOME` 环境变量）。

```bash
./gradlew assembleDebug
```

或用 Android Studio 直接打开本目录运行。

## 使用步骤

1. 安装并打开 App，授予蓝牙/位置权限。
2. 电脑打开蓝牙，在"添加设备"中搜索并连接手机（手机表现为 `Bluke`，描述 `Wireless Controller Combo`）。
   - 也可在 App 里"扫描设备"并主动连接电脑。
3. 连接成功后：
   - **键盘测试**：输入文本点"发送文本"，或点"Ctrl+A / 回车 / 退格"、切换 Shift/Ctrl/Alt 修饰键。
   - **鼠标测试**：方向键移动光标，左/右/中键点击，滚轮。
   - **手柄测试**：A/B/X/Y 按键，左/右摇杆方向。

> 提示：键盘报表为 6 键无冲突，连续按超过 6 个键会丢键（HID 键盘标准上限）。
> 若注册失败提示 `ProfileNotSupported`，说明该机型蓝牙栈不支持 HID Device 角色。

## 技术栈

- Kotlin 2.2 / Jetpack Compose (Material3) / AGP 9.2
- minSdk 28（Android 9，`BluetoothHidDevice` 引入版本）/ targetSdk 36
- 无第三方业务库，仅官方 AndroidX 依赖
