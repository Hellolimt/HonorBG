# HonorBG

> 荣耀 MagicOS 设置页的「液态玻璃」主题模块 · 基于 LSPosed / libxposed

**作者：ZOQOO** · GitHub：[@Hellolimt](https://github.com/Hellolimt) · 版本：1.4.6

---

## 这是什么

HonorBG 是一个 Xposed 模块，把自定义壁纸铺到荣耀 MagicOS 的「设置」页面背后，并把一级、二级菜单里零散的系统卡片渲染成**连续整块的液态玻璃卡片**——深浅色自适应、18dp 圆角、14dp 左右边距、8dp 行距、1dp 渐变描边。

它源自 [HyperBackground 1.4.5-beta2](https://github.com/search?q=HyperBackground)（小米 HyperOS 版）的移植想法，但渲染层、分组算法、设置界面已完全重写，以适配荣耀的 Settings UI。

### 主要特性

| 特性 | 说明 |
|---|---|
| 整块玻璃分组 | 不按 `resource-id` 逐块找卡片，而是按可见子 View 的宽/高/间距在整表范围内计算 Block，二级菜单风格统一 |
| 滚动不分裂 | 每行一片 `RowGlass`，分组结果在每帧 draw 之前（pre-draw）固化到所有行，慢速滑动也不会「先分裂、闪一下合并」 |
| 静止不白块 | 针对窗口零重绘、首帧像素冻结做了 layout 后 settle 与重绘保险 |
| 16 项可调参数 | 玻璃总开关、不透明度、反射/高光、折射光带、描边开关/强度/粗细、分隔线开关/自定义颜色/透明度/线宽/内缩、圆角/左右边距/上下边距 |
| 即时生效 | 参数改动后切回「设置」即重绘，无需重启设备、无常驻轮询 |
| 完美版可回滚 | `baseline/beta25-perfect/` 保存了用户实测确认的完美版源码与校验清单 |

默认参数与 beta25 完美版**完全一致**，不调任何滑杆就不会改变画面。

---

## 环境要求

| 项目 | 要求 |
|---|---|
| 设备 | 荣耀 MagicOS；已在 WKL-AN20 / Android 17 (SDK 37) / 1272×2800 @3.0x 实测 |
| 框架 | LSPosed（libxposed API 101），需 root |
| 模块包名 | `com.ciallo.hyperbackground`（保持原包名以便覆盖安装与复用 LSPosed scope） |
| 构建环境 | Windows + PowerShell 5.1+，JDK 21，Android build-tools r36，android-35 SDK，libxposed api-101 jar |

> 展示名是 **HonorBG**，但 APK 包名与 Java 源码包名（`com.honor.hb`）是两回事。源码包名是桥接代码的命名空间，模块包名沿用上游，详见 `docs/ARCHITECTURE.md`。

---

## 安装

1. 按 [`docs/BUILD.md`](docs/BUILD.md) 构建出 `out/HonorBG-1.4.6.apk`。
2. 安装并授权：
   ```bash
   adb install -r -g out/HonorBG-1.4.6.apk
   ```
3. 在 LSPosed 中启用模块，并勾选以下 **8 个作用域**（缺一个对应页面就不会被注入，且不会有任何报错）：
   ```
   com.android.settings
   com.hihonor.systemmanager
   com.hihonor.controlcenter
   com.hihonor.assistant
   com.hihonor.id
   com.hihonor.wallet
   com.hihonor.parentcontrol
   com.ciallo.hyperbackground
   ```
   最后一行是**模块自身**：精简设置页运行在模块进程里，缺了它打开模块图标看到的仍是上游原版 Compose UI。
4. 首次改动 scope 后重启一次设备，让 LSPosed daemon 重新加载作用域表。
5. 点模块桌面图标进入精简设置页，在「背景」卡选一张壁纸。

> 验证渲染效果请打开系统「设置」；验证配置界面请点**模块自己的图标**（`MainActivity`），两者不是同一个进程。

---

## 设置项

设置页共 5 张卡：背景 / 液态玻璃 / 组内分隔线 / 几何 / 关于。

| 卡片 | 控件 | 默认值 |
|---|---|---|
| 背景 | 启用背景、背景图片、清除背景、**背景不透明度** | 开 / — / — / **100%（默认）** |
| 液态玻璃 | 总开关、玻璃不透明度、反射 / 高光、折射光带 | 开 / 60% / 50% / 0% |
| 液态玻璃 | 轮廓描边、描边强度、描边粗细 | 开 / 100% / 1dp |
| 组内分隔线 | 显示分隔线、自定义颜色、颜色、颜色不透明度、线宽、左右内缩 | 开 / 关 / 跟随系统 / 20% / 1px / 16dp |
| 几何 | 圆角半径、左右边距、上下边距 | 18dp / 14dp / 4dp |

完整参数键、取值范围与生效链路见 [`docs/CONFIG.md`](docs/CONFIG.md)。

> ⚠ **「背景不透明度」控制的是壁纸本身，不是玻璃。** 它会同时作用于壁纸 ImageView 和窗口背景，实际浓度是刻度的平方（调到 20% 时壁纸只剩约 36%，剩下的黑底看起来像蒙了一层透明黑）。保持 **100%** 即为默认外观；想调玻璃通透度请用「液态玻璃」卡里的「玻璃不透明度」。

---

## 目录结构

```
HonorBG/
├── README.md                    本文件
├── LICENSE                     MIT（仅覆盖本仓库源码）
├── CHANGELOG.md                版本摘要
├── .gitignore
├── build.ps1                   构建 / 签名 / 归档脚本
├── src/com/honor/hb/           全部桥接源码（10 个 .java）
│   ├── HonorHookEntry.java     模块入口、按包名分流、三套 hook 链
│   ├── HonorRenderer.java      核心渲染器、pre-draw 分组守卫、页面重绘
│   ├── GlassConfig.java        参数唯一来源、默认值、版本与脏标记
│   ├── GlassStyle.java         绘制中心（填充/描边/折射/分隔线）
│   ├── GlassBlocks.java        整表几何分组判定
│   ├── RowGlass.java           每行一片玻璃 + pinBlock 固化
│   ├── SurfaceGlass.java       自由卡片玻璃
│   ├── AppearanceState.java    壁纸/深浅色只读状态
│   ├── Prefs.java              远程偏好统一读取器
│   └── HonorSettingsUi.java    精简设置页（5 张卡、16 项控件）
├── docs/
│   ├── BUILD.md                构建环境、工具链、环境变量、常见错误
│   ├── ARCHITECTURE.md         逐文件架构与数据流
│   ├── CONFIG.md               参数表与远程偏好链路
│   ├── TROUBLESHOOTING.md      已知问题与排查
│   └── CHANGELOG-full.md       完整修复时间线（含历次 SHA-256）
├── baseline/beta25-perfect/    完美版基线源码 + SHA256SUMS.txt
└── .github/ISSUE_TEMPLATE/     Bug 反馈模板
```

---

## 架构速览

```
模块进程 MainActivity
  └─ HonorSettingsUi → pr editor → LSPosed 远程偏好 (backgrounds)
                                      │
设置进程 com.android.settings ◄────────┘
  └─ AppearanceState.read() → GlassConfig.refresh() → 快照
  └─ HonorRenderer (OnPreDrawListener) → GlassBlocks.compute() → pinRowGlass()
  └─ 每帧 draw：RowGlass / SurfaceGlass → GlassStyle 绘制
```

关键设计：`RecyclerView` 滚动时容器 display list 不重录，因此整块背景必须由**每一行自己的 Drawable** 绘制；分组结果必须在每帧 draw **之前**统一计算并固化，否则会出现「错一帧再纠正」的闪烁。详见 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

---

## 已知问题

常见问题（作用域、远程偏好、背景不透明度平方衰减、总开关实时切换等）见 [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md)。

---

## 许可与来源

- 本仓库**新增的桥接源码与文档**采用 [MIT License](LICENSE)，Copyright © 2026 ZOQOO。
- 本项目是基于上游 **HyperBackground 1.4.5-beta2** APK 的**增量增强**：构建脚本会把编译出的 `classes2.dex` 与 Xposed 元数据写进用户自备的原 APK，不修改其原有 `classes.dex`、资源与界面。
- **本仓库不分发上游 APK**，也不包含任何第三方二进制。使用者需自行准备原版 APK；上游应用的一切权利归其原作者所有。
- 本仓库亦不分发签名密钥（`*.jks` 已忽略）。构建脚本会在本地自动生成调试密钥；发布用途请自备密钥。

---

## 致谢

感谢 HyperBackground 原作者提供的思路与基础，以及 LSPosed / libxposed 项目。
