# 手柄桥 · 电脑端 UI 设计规范

> 本规范从手机 App（`HidScreen.kt`，Jetpack Compose Material 3）提取，供电脑端
> receiver.exe（Win32 自绘）界面开发对照使用，保证两端视觉风格一致。

---

## 1. 整体布局架构

滚动卡片流式布局（Material 3 卡片风格）：

```
┌─────────────────────────────────┐
│  HeroCard 顶部状态卡              │  ← primaryContainer 底色
│  · 状态指示灯 + 状态标题 + 状态描述  │
├─────────────────────────────────┤
│  StepCard ① 蓝牙连接              │  ← 圆形步骤序号 + 图标 + 标题
│  · 主操作按钮 / 设备列表           │
├─────────────────────────────────┤
│  StepCard ② 电脑端安装            │  ← 说明文字 + 主按钮
├─────────────────────────────────┤
│  StepCard ③ WiFi 桥接             │  ← Switch 开关 + 输入框 + 自动发现
├─────────────────────────────────┤
│  StepCard ④ 开始游戏              │  ← 手柄测试面板 + 桥接开关
├─────────────────────────────────┤
│  RateSettingCard 回报率设置        │  ← surfaceVariant 底色 + 滑块
└─────────────────────────────────┘
```

**窗口建议**：单列内容宽度约 360~420px，垂直方向可滚动；电脑端窗口建议
最小 480×640，内容居中，最大宽度不超过 560px。

---

## 2. 配色体系

### 2.1 Material 3 主题色（基线紫色，动态色关闭时）

| 语义色 | HEX | 用途 |
|--------|-----|------|
| primary | `#6750A4` | 主按钮、开关开启态、选中图标、滑块 |
| onPrimary | `#FFFFFF` | 主按钮文字、选中态图标 |
| primaryContainer | `#EADDFF` | Hero 卡、完成态卡片底 |
| onPrimaryContainer | `#21005D` | Hero 卡描述文字 |
| secondaryContainer | `#E8DEF8` | 未完成步骤序号圆底 |
| onSecondaryContainer | `#1D192B` | 步骤序号文字 |
| surface | `#FEF7FF` | 卡片默认底 |
| surfaceVariant | `#E7E0EC` | 设备行、回报率卡、次级面板底 |
| onSurfaceVariant | `#49454F` | 次要说明文字（灰色） |
| tertiaryContainer | `#FFD8E4` | LT/RT 扳机键底 |

### 2.2 状态语义色（硬编码）

| 语义 | HEX | 用途 |
|------|-----|------|
| 成功 / 已连接 | `#4CAF50` | Hero 状态灯、已连接徽章、B 键 |
| 就绪 / 等待 | `#FF9800` | Hero 状态灯（就绪） |
| 错误 / 关闭 | `#F44336` | Hero 状态灯（蓝牙关）、A 键 |
| 未知 / 禁用 | `#9E9E9E` | Hero 状态灯（默认） |
| X 键 | `#2196F3` | 手柄面键 |
| Y 键 | `#FFEB3B` | 手柄面键 |

**规则**：状态指示灯 12px 实心圆点；同一状态在任何卡片中颜色必须一致。

---

## 3. 圆角与形状

| 组件 | 圆角 | 备注 |
|------|------|------|
| 主卡片 | 默认（12dp 左右） | 卡片级圆角 |
| 设备行子卡 | 8px | 嵌套卡片 |
| 手柄按键（LB/RB/LT/RT） | 12px | 按压变主色 |
| 十字键 / Select / Start | 8px | |
| 按键状态网格单元 | 6px | |
| 面键 A/B/X/Y | 圆形 | 直径 44px |
| 步骤序号 | 圆形 | 直径 32px |
| 状态指示灯 | 圆形 | 直径 12px |
| 摇杆可视化 | 圆形 | 外圈 80px + 内点 28px |

---

## 4. 间距规范

| 场景 | 值 |
|------|-----|
| 窗口/页面边缘 | 16px |
| 卡片之间垂直间距 | 12px |
| 卡片内边距 | 16px |
| 行内图标与文字间距 | 8~12px |
| 按钮行内间距 | 8px |
| 段落之间 | 4~12px |
| 小节标题与内容 | 4px |

---

## 5. 字体与文字层级

| 层级 | 字号/字重 | 场景 |
|------|-----------|------|
| 状态标题 | 22px Bold | Hero 状态标题 |
| 卡片标题 | 16px SemiBold | 步骤卡标题 |
| 设备名/列表项 | 16px Regular | 设备行主文本 |
| 正文说明 | 14px / 12px Regular | 卡片内说明文字 |
| 小节标签 | 12px Regular | "已配对设备"、"回报率"等 |
| 地址/坐标 | 11px Monospace | 设备 MAC、摇杆数值 |

等宽字体建议：`Consolas` 或 `Courier New`。

---

## 6. 交互组件风格

### 6.1 按钮

| 类型 | 样式 |
|------|------|
| 主按钮 | 实心 primary 底色 + 白色文字 + 前置图标（全宽或自适应） |
| 次按钮 | 描边按钮（2px primary 描边）+ primary 文字 |
| 文字按钮 | 无边框 primary 文字（如"连接"） |

### 6.2 状态徽章（胶囊）

- 底色：primary（成功/已确认）或 secondaryContainer（等待中）
- 圆角：胶囊形（高度一半）
- 成功态：前置 ✓ 图标
- 等待态：前置 16px 转圈动画
- 文字颜色与底色对应用例见配色表

### 6.3 开关（Switch）

- Track 宽约 52px，Thumb 圆形
- 开启：Track 主色、Thumb 白色、右侧对齐
- 关闭：Track 灰、Thumb 偏灰

### 6.4 输入框

- 描边（2px）+ 圆角 8px + 左侧图标（如电脑图标）
- 提示文字浅灰（onSurfaceVariant）
- 禁用时（桥接开启中）置灰不可编辑

### 6.5 进度反馈

| 场景 | 样式 |
|------|------|
| 扫描中 | 顶部横向进度条（Linear） |
| 等待电脑 ACK | 16px 圆形转圈 |
| 连接建立 | 状态文字 + 徽章 |

### 6.6 摇杆实时可视化

- 外圈 80px 圆形 surfaceVariant 底色
- 内点 28px 圆形 primary 色，按轴值偏移（±25px）
- 下方等宽字体显示坐标 `+0.35, -0.82`

---

## 7. 文案风格

- 每张卡片顶部有简短标题（名词短语："蓝牙连接"、"WiFi 桥接"）
- 每个操作区有 1 行灰色说明文字
- 关键操作附操作提示（如"电脑运行 receiver.exe 后，点击自动发现…"）
- 状态反馈分级：标题（状态）+ 正文（详情）+ 胶囊（结果）

---

## 8. Win32 落地对照表

| 手机端（Compose） | 电脑端（Win32 自绘） |
|------------------|----------------------|
| Card + 圆角背景 | `GDI+ RoundRect` / `Path` + `FillPath` |
| primaryContainer 淡紫 | 直接填充 `RGB(0xEA,0xDD,0xFF)` |
| AssistChip 胶囊 | `RoundRect` 画胶囊 |
| Switch | 自绘 Track（圆角矩形）+ Thumb（圆） |
| 圆形摇杆 | `Ellipse` 画外圈与内点 |
| Monospace | `CreateFont` 指定 Consolas |
| 图标 | 内嵌矢量/图标资源或绘制 |
| 滚动 | `WS_VSCROLL` 或自绘滚动区 |

---

## 9. 关键 RGB 速查

```c
// 主色
PRIMARY             = RGB(0x67, 0x50, 0xA4)   // 紫
PRIMARY_CONTAINER   = RGB(0xEA, 0xDD, 0xFF)   // 淡紫
SECONDARY_CONTAINER = RGB(0xE8, 0xDE, 0xF8)   // 浅紫灰
SURFACE             = RGB(0xFE, 0xF7, 0xFF)   // 近白
SURFACE_VARIANT     = RGB(0xE7, 0xE0, 0xEC)   // 浅灰
ON_SURFACE_VARIANT  = RGB(0x49, 0x45, 0x4F)   // 灰文字
TERTIARY_CONTAINER  = RGB(0xFF, 0xD8, 0xE4)   // 淡粉（扳机键）

// 状态色
STATE_OK       = RGB(0x4C, 0xAF, 0x50)   // 绿
STATE_WAIT     = RGB(0xFF, 0x98, 0x00)   // 橙
STATE_ERROR    = RGB(0xF4, 0x43, 0x36)   // 红
STATE_UNKNOWN  = RGB(0x9E, 0x9E, 0x9E)   // 灰

// 面键
FACE_A = RGB(0xF4, 0x43, 0x36)   // 红
FACE_B = RGB(0x4C, 0xAF, 0x50)   // 绿
FACE_X = RGB(0x21, 0x96, 0xF3)   // 蓝
FACE_Y = RGB(0xFF, 0xEB, 0x3B)   // 黄
```

> 主色若希望跟随手机动态色，电脑端可固定使用基线紫色 `#6750A4` 保持一致。
