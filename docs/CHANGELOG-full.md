# CHANGELOG — 从 1.4.5-beta2 起的修复时间线

> 基线：**HyperBackground 1.4.5-beta2**（小米 HyperOS 原版）。本项目是它的荣耀 MagicOS 移植。
> 哈希为 APK 的 SHA-256；「…」表示只记录了前缀（完整哈希未留存，标注待确认）。
> 构建时间均为 2026 年，Asia/Shanghai。

## 2026-10-30 ~ 10-31 — 移植起步 + 白闪修复

- **移植骨架**：`HonorHookEntry`（入口）、`HonorRenderer`（渲染）成型；`HonorHookEntry.java` 于 10/30 22:40 定稿后**未再改动**。
- **修复「进入设置 / 二级页白色闪屏」**（2026-10-31 深夜，笔记脚本 `add_readme.py`）：
  - **根因**：SystemUI 的起始窗口 `windowBackground` 是白色；对照实验（移除模块后白闪依旧）确认是起始窗口，不是模块本身。
  - **修法**：模块 hook `com.android.systemui`，跳过目标为 settings 的起始窗口（`HonorHookEntry.installStartingWindowHooks`）。
  - **验证**：screenrecord 30fps 白像素统计 —— 修复前 0.6s 100% 纯白，修复后首页/显示亮度页 ≤5%。
  - ⚠ **依赖**：需要 LSPosed 作用域勾上 `com.android.systemui`（早期笔记称「安装包已内置，settings 与 systemui 两项都要勾选」）；但**当前 `build.ps1` 的 `scope.list` 只写 settings + module**，两者冲突，见 KNOWN-ISSUES.md「待确认」。

## 2026-11-01 — 卡片玻璃化两连修

- **修复「二级菜单卡片先白后变玻璃」**（笔记脚本 `add_readme2.py`）：
  - **根因**：页面数据晚加载，行在布局阶段才加入；原节流（120ms + 1s 兜底）导致新行要等下一轮才玻璃化，出现「先白后玻璃」闪烁。
  - **修法**：布局回调改为**本帧绘制前立即处理，不节流**（`watchLayout` 挂 `onPreDraw`）。
  - **验证**：白像素 ≤11%（修复前 50~100%）；gfxinfo 50th 9ms / 90th 12ms / Janky 2.33%。
- **修复「蓝牙 / 二级菜单卡片不统一玻璃」**（笔记脚本 `append_notes3.py`）：
  - **根因**：反射读 ItemDecoration，发现荣耀 `SettingsRecyclerView` 挂了 **三个**装饰器 `gm.a` / `l$d` / `gm.b`，其中 `gm.a` 给整个偏好分组画白色圆角底，覆盖了玻璃效果。
  - **修法**：反射删掉 `mItemDecorations` 里的 `gm.a`（保留 `l$d` / `gm.b`）。
  - **验证**：首页行区均值 218、蓝牙页 179（白像素 2%，修复前 84~96%）。

## 2026-11-06 — 精简设置页 + 玻璃样式重写（一次晚间构建会话）

当天晚上做了一次集中的构建迭代。源文件 mtime 给出确定性线索：

| 时间 | 事件 |
|---|---|
| 19:58 | `AppearanceState.java` 定稿 |
| 19:59 | `HonorSettingsUi.java` 定稿（模块进程精简设置页，替代原 Compose UI） |
| 22:45 | `build.ps1` 定稿（构建脚本） |
| 23:04:03 | `GlassStyle.java` 新建（抽出玻璃样式中心） |
| 23:04:24 | `HonorRenderer.java` 定稿（63362 B，当前版本） |

构建产物（`out\archive\`，文件名含时间戳 + SHA-256 前 8 位）：

| 构建时间 | SHA-256 | 说明 |
|---|---|---|
| 21:20:52 | （未记录，待确认） | 会话内早期迭代 |
| 21:45:10 | `F830F20C…`（仅前缀，待确认） | 迭代中 |
| 21:59:41 | `C88E1449…`（仅前缀，待确认） | 迭代中 |
| 22:10:40 | `E39572E367B1010976180656AEE7BAB8D163CDB58F80881DE46C7B4A7179562D` | **回退版**（APK 留存于 `out\archive`） |
| 23:04:58 | `105C2C05979CD2003ACCFD3965B29595B2C00903A4E8FEE4ECF84861713CBC3F` | **最终版**（APK 留存，已放入 `apk\`） |

> 21:20 / 21:45 / 21:59 三个中间构建各自具体改了什么，没有逐版笔记留存（**待确认**）；下文只陈述能由源码 mtime + 备份文件确定的 **22:10 → 23:04 差异**。

### 22:10 回退版 vs 23:04 最终版 的区别

**22:10 版（E39572E3）** 是「回退版」—— 把某个引起问题（即 KNOWN-ISSUES 里的「首页双层烘焙」）的改动回退掉后的中间稳定态。它的玻璃绘制还是**旧 `glassify` 半分辨率烘焙**（见源码备份 `HonorRenderer.java.glassbak` 里的 `int w = v.getWidth() / 2; int h = v.getHeight() / 2;`）。

**23:04 版（105C2C05）** 是最终版，相对 22:10 版做了三件确定性的事：

1. **抽出 `GlassStyle.java`**（23:04:03）—— 把散在 `HonorRenderer` 里的玻璃常量（圆角/内缩/描边/mask/层 alpha/scrim）和 fallback 卡构建器集中到一个类，供渲染器和设置页共享。
2. **`HonorRenderer.java` 重写**（23:04:24，63362 B）—— 从旧 `glassify` 半分辨率烘焙改为**全分辨率烘焙**（旧半分辨率烘焙用 `c.scale(0.5f)`，会在卡片后留下重影文字，即「双层烘焙」的根因之一）。
3. **HnCard 改为单层玻璃卡替换** —— 见下「双层烘焙」：荣耀 `HnCardDrawable` 把磨砂区+内层胶囊烘焙进同一张位图，渲染成「双层卡 + 双描边」；23:04 版在 `walkStrip` 里遇到 `HnCard`（非列表行）时整体替换为单层 `glassCard`（单层填充 + 单条描边），并新增 `glassScrim`（护眼页）与关于页 hero（`device_name_layout` / `device_storage_container`）的 scrim 逻辑。

> **关于「回退」与「双层烘焙」的关系**（推断，建议接手者复核）：`HonorRenderer.java.glassbak`（10/30 23:08）证明旧实现是「半分辨率烘焙」。23:04 的最终修复是「全分辨率 + 单层玻璃卡替换 HnCard」。22:10 的「回退」很可能是：先尝试了某个玻璃改动后出现回归，回退到 21:59 附近的稳定态；随后 23:04 才落地正确修法。**22:10 回退版具体回退了哪个改动，需对比 22:10 与 21:59 的 APK 反编译才能确认（待确认）。**

## 2026-11-10 — 首帧闪烁 + 一级菜单分组整块

产物：`20261110-133740-2A995575.apk`，SHA-256
`2A9955752F28E53147C4E825734B471E2C140B80AFC9F22D1621857B70877A67`。

### 一、进入设置页首帧闪烁（白闪 / 灰帧 / 错位）

**实测定位**（screenrecord 60fps 逐帧统计，首页 → 显示和亮度）：

- 转场期间新页面连续约 15 帧（~250ms）背景是近白色，行是**白色不透明卡**；稳定后同一页是玻璃卡 + 壁纸。白像素从 3.5% 断崖到 0.9% 再回升，说明这一窗口内既没有壁纸层、也没有玻璃化。
- 真因**不是**起始窗口：整个过程 logcat 没有任何 `addStartingWindow` / `Splash Screen` / `HnAwareStartingSurface` 与 `com.android.settings` 相关的记录（同为应用内 task 跳转，系统不建起始窗口）；也不是旧笔记里认为的 systemui 白底。

**真因（两处，都在本模块）**：

1. **壁纸层是在 `onPreDraw` 里 `addView` 的**。`addView` 发生在该帧 `performLayout` 之后，新加的 ImageView 当帧尺寸仍是 0×0 → **首帧画不出壁纸**；同时 `addView` 触发 `requestLayout`，把这一帧的布局又推后一次。窗口背景此时已被置空，于是露出主题白底 → 「白闪/灰帧」。
2. **首帧被重活阻塞**。同一帧里做了：全树遍历 + 每个有背景的 view 都 `sampleColor`（8×8 位图分配 + drawable 渲染）+ 逐行构建 `LayerDrawable/InsetDrawable` 玻璃卡 + `dumpTree` 全树日志；随后还有 8 次 600ms 的补扫（每次 600ms 间隔 + dumpTree）。主线程被占住几百毫秒，正是「1 帧到 450ms」的上限。

**修法**：

- **背景层提前到 `onCreate` 安装**：`apply()` 从 `Instrumentation.callActivityOnCreate` 之后调用，此时 decor 已存在但尚未 measure。`addView(layer, 0, MATCH_PARENT)` 让壁纸层**参与第一次布局**，首帧即有位图；不再依赖 `onPreDraw`。
- **窗口背景 = 壁纸本身**（`WallpaperDrawable`，center-crop）。即使还有任何一帧走到「层未绘制」，看到的也是壁纸而不是白底。这是兜底，不是主要手段。
- **壁纸解码移到后台线程**，并在进程加载时预热：先解 720px 预览（几毫秒，够首帧用），再解全分辨率替换（`syncLayer`，同尺寸换图，不触发 relayout）。
- **剥离首帧的重活**：不再逐行建 drawable（改为一块一绘）、不再 `sampleColor` 每一行、不再 `dumpTree`、删掉 1s 起的 8 次补扫和 `postDelayed(450ms)` 的 blur outline 修正。
- `deferUntilLaidOut()` 的覆盖范围复核结论：它只覆盖「`apply()` 时 decor 还是 0×0」这一种情况，**不覆盖**「行比 decor 晚到」的页面（那是 `watchLayout` 的 120ms 节流在兜底，会漏帧），而且它把重活放在 preDraw 里做，本身就是闪烁源。现已改为：`armFirstStrip()` 只在 decor 未测量时兜一次（且只做轻量 strip），行晚到由 `OnGlobalLayout` + `RecyclerView.OnChildAttachStateChangeListener` 触发 —— **没有一处是按时间硬等的**。

### 二、一级菜单按系统分组整合成整块

**做法**：不再逐行套卡。`GlassBlocks` 用几何规则把列表切成「系统分组」，`GlassBlockDecoration`（RecyclerView ItemDecoration）在**列表画布上、行之前**把每组画成**一块**圆角玻璃。

- **分组判定（纯几何，不看 id）**：可见子 view 且宽度 ≥ 容器 55%、高度 ∈ [44dp, 容器 62%] 记为「行」；行间空隙以容器内最小空隙的 1.5 倍（下限 20dp）为界 —— 大于它即新分组。实测首页组内 49px(16.3dp)、组间 98–112px(33–37dp)，判据自动选取 73px 阈值，WLAN→更多连接 / 荣耀AI&YOYO→散热风扇 / 声音→情景模式 全部落对。非「行」的子 view（分组标题、主题缩略图、hero）一律当边界。
- **一块 = 单层玻璃**：填充 + 对角高光 + **一条** 1dp 渐变描边，18dp 圆角、左右 14dp、上下各 4dp（相邻两块 8dp）。行内**只有一条 1px 分隔线**（居中在行间空隙、左右各内缩 16dp）。行自身背景被清掉 → 不可能出现双层卡/双描边。
- **不位移**：清行背景时先记 4 个 padding，`setBackground(null)` 后把 padding 原样写回（原生卡是带 padding 的），所以文字/图标一个像素都不动。
- **不递归套底**：`walk` 命中 RecyclerView / 可滚动行栈即 `return`，不再往下套；已玻璃化的容器（`BlockBackground`）在 `clearRow` / `clearCover` 里显式跳过。
- **滚动时圆角处理**：分组被视口截断的一侧改为直角（用 `getChildAdapterPosition` + `getItemCount` 判断），否则滚动中会在屏幕中间露出一个「卡片圆角」。
- **兜底路径**：非 RecyclerView 的二级页（ScrollView 下的竖排容器）用同一个 `GlassBlocks` 分块，画在容器的 `BlockBackground` 上。
- 仍然保留的 id 用法只有两类：appbar/搜索/系统栏等**黑名单例外**（不碰），以及 `content_frame` / `list` 两个「系统自己铺的不透明底」强制清底（历史修法，几何规则看不见这种非纯色面板）。

**遗留**：`gm.a` 仍是按类名删（它画的正是「每组一张白卡」，必须删掉否则我们的半透明块会叠在白色上发灰）。荣耀改混淆名会失效，见 KNOWN-ISSUES。

### 构建脚本

`build.ps1` 新增编译期 stub 步骤：`stubs/androidx/recyclerview/widget/RecyclerView.java` 只用于 `javac`（`-cp` 里带上 `build\stubclasses`），**不参与 d8**，APK 里没有 androidx 任何类。d8 用两次 `--classpath`（`api.jar` 与 `build\stubclasses`），不能写成 `a;b` 单参数 —— PowerShell 传给原生 exe 时会被拆开，d8 把目录当程序输入报 `Unsupported source file type`。



- 签名：`apksigner`，keystore `honorbg.jks`（alias `honorbg`，CN=`HonorBackgroundPort`），v1 关 / v2 开 / v3 开。
- 证书 SHA-256 digest = `127ca9e189cf67157ca16912ea8b4b5dd6ab64370b9418613e2e017b06c774cf`。
- 最终产物 APK：`out\HyperBackground-Honor-1.4.5-beta2.apk`；归档名 `20261106-230458-105C2C05.apk`（已放入 `apk\`）。

## 2026-11-10（二次修正：装饰器方案作废、分组判据重写）

第一次装机后 `logcat` 立刻暴露一个架构性错误，并推翻了原来的分组阈值假设，两处都已改掉。

### 一、`RecyclerView.ItemDecoration` 在 libxposed 下不可用

```
block decoration unavailable: java.lang.NoClassDefFoundError:
  Failed resolution of: Landroidx/recyclerview/widget/RecyclerView$ItemDecoration;
```

`com.android.settings` **是**带 `androidx.recyclerview` 的（`dashboard_container` 的类名就是 `androidx.recyclerview.widget.RecyclerView`），但 libxposed 把模块 dex 装在自己的 class loader 里，宿主 app 的类**不在**它的父 classpath 上 —— 模块代码只引用了 `android.*` 和反射，一旦 subclass 了宿主里才有的类，加载即失败。

- 删除 `GlassBlockDecoration.java` 与 `stubs/`（编译期 stub 的思路本身是错的），`build.ps1` 去掉 stub 编译步骤，`javac` / `d8` 回到 `android.jar + api.jar` 两件套。
- 改由 `HonorRenderer.BlockBackground`（`android.graphics.drawable.Drawable`）挂在容器 `setBackground` 上绘制整块：`View.draw()` 先画背景再画子 view，z 序正好是「块在行下面」；滚动时 RecyclerView 会 `invalidate()` 整个 view，背景随之重画，行坐标天然跟随。
- 一级菜单与二级页共用这一条路径，`stripList` 与 `stripStack` 都只是「算块 → 清行卡 → 装背景」。

### 二、分组判据：只认「是否紧邻」

清掉行卡片后，实测几何与改造前完全不同（`uiautomator` 首页实测）：

```
tile1  808-1134 h=326
tile2 1197-1379 gap=63      ← 组间 21dp
tile3 1379-1561 gap=0       ← 组内 0（卡片的 16dp 内缩已不存在）
```

原来的「最小空隙 ×1.5 自适应」会把 63 也当成组内间距，首页 13 行被并成 1 块（`blocks: [1,239-565][12,628-2938]`）；二级页各行等间距，同样被整页并成一块（`[1,331-1212][9,1275-2843]`）。改为：**行与行之间只要出现 > 4dp 的间隙就是新块**，紧邻才合并。

- 首页实测结果：`blocks: [1,239-565][5,628-1538][5,1601-2511][2,2574-2938] rows=13`，即 搜索 / ZOQOO / WLAN→更多连接 / 荣耀绿洲…
- 滚动后确认：荣耀绿洲护眼→散热风扇 一块、声音和触感→情景模式 一块、生物识别和密码→隐私和安全 一块，与系统分组一一对应。
- `MIN_ROW_WIDTH_FRAC` / `MAX_ROW_HEIGHT_FRAC` 仍用于排除 hero、分组标题、2 列缩略图（宽度不到容器 55%），这些非行 view 同样是分组边界。

### 三、不能滥删原生 ItemDecoration（踩过的坑）

二级页的 `tm.b` / `tm.c` 会画全宽分隔线，一度想全删；实测**全删会让首页分组全部塌成一块**——它们的 `getItemOffsets` 正是组间 20dp 的来源。最终只保留原先的 `gm.a` 删除（它画的是「每组一张白卡」）。

### 四、首帧验证（Intent 直开二级页，n=3 次）

```
t=250ms  white=7.3%  mean=187.5
t=450ms  white=7.4%  mean=187.7
稳定态    white=7.4%  mean=187.7
```

首帧亮度与稳定态一致，无白闪、无灰帧。`FATAL EXCEPTION` / `ANR in com.android.settings` 计数 = 0。

### 产物

- APK：`out\HyperBackground-Honor-1.4.5-beta2.apk`
- SHA-256：`F3911516B75889455C67547F6A591CF06DF22AFF048E36D539344894F6FFF0E6`
- 归档：`out\archive\20261110-142540-F3911516.apk`
- 设备上 `pm path` + `sha256sum` 复核一致（`/data/app/~~3cGrEyzC3ED_zNXpHpL9wQ==/.../base.apk`）
- 装机：`adb push` + `pm install -r -g -t`（**流式安装在本机 USB 通道上会中途断链把 adbd 打掉，改用这条路径**）

## 2026-11-10（三次修正：滚动白块残留 + 关于手机白卡）

用户实测反馈两个小 BUG，根因都在同一处架构选择上。

### 一、「急速上下滑动时有些白色块残留」= 容器级玻璃面板在滚动中冻结

先加帧计数日志确认现象：滚动过程中 `guardFrame`（preDraw）每帧都在跑，`kids`/`top0` 也在变，但 `BlockBackground.draw` 的计数**停在 5 不动**。

原因：RecyclerView 滚动是 `offsetChildrenVertical` 移动**每一行自己的 render node**，容器的 display list 并不重新录制。画在容器背景上的整块玻璃于是停在最后一次录制的位置 —— 滚动时看到的就是那块"残留的白色/半透明块"，而新滚入的行完全没有玻璃（视频帧里非常明显）。

改为 **每行一个 `RowGlass`**（`src/com/honor/hb/RowGlass.java`）：

- 首行：上圆角；末行：下圆角；中间行：不圆角；
- 每行画自己的左/右两条竖描边，横向接缝推到行边界之外再 `clipRect` 裁掉 → 合并后仍是**一整块、一条连续描边**，不会出现双线；
- 非首行在行顶画 1px 分隔线；
- 首末行是否圆角由 `openTop/openBottom`（行相对视口的位置）决定，滚动中不会在屏幕中间冒出"卡片端头"。

`GlassStyle` 相应拆成 `drawBlockFill()`（填充+sheen）与 `drawBlockStroke()`（单条渐变描边），`GlassBlocks` 只保留几何判定 `isMember()` 与 `compute()`（后者仅用于日志）。

### 二、「关于手机里有些白卡」= 2 列宫格是嵌套 `HwCardView`

设备名称 / 处理器 / 运行内存三块宽 517/587px（占容器 40%–46%），不满足「行宽 ≥55%」，被判成非行；而且它们嵌在一个 1146px 宽的容器里，是**孙子节点**，列表遍历根本进不去。

新增 `sweepGridCards()`：在块的子树里（深度 ≤3）按几何+类型找宫格卡 —— 宽 28%–62%、高 ≥32dp、背景是 `CardView` 或纯色填充；**同一块里命中 ≥2 个才动手**（避免误伤搜索框，它只有一张且宽 ~85%）。清掉后由背后那一行（该容器本身是块成员）的玻璃接管。

### 三、顺带修掉的坑：不要在 preDraw 里对列表 `setBackground`

第一版把容器原生 pane 用 `setBackground(null)` 清掉，结果 `SettingsRecyclerView` 在 `onLayout` 里又把它设回来 → preDraw 再清 → 每帧一次 `requestLayout`，列表直接卡住不滚。

改为 `hidePane()`：`Drawable.mutate().setAlpha(0)`，只 invalidate 不触发布局。

### 四、验证

- 首页 fling 录屏抽帧（418 帧）：滑动中玻璃与行同步移动，无残留块（对比修正前同场景：整页无玻璃 + 顶部一条残留白带）。
- 关于手机：三张白卡消失，顶部区域与下方各块观感一致。
- 深色模式：深色玻璃 + 浅色文字，正常。
- 首帧：`t=250ms white=6.6% mean=184.2` vs 稳定态 `6.8% / 184.3` —— 无白闪。
- `FATAL EXCEPTION` / `ANR` = 0。

### 产物

- APK：`out\HyperBackground-Honor-1.4.5-beta2.apk`（5,385,885 字节）
- SHA-256：`965AE07E59D68216DAAED8BFE4BA018BD0175A3F7151AEC49D84F48E0996B2EE`
- 归档：`out\archive\20261110-150528-965AE07E.apk`（哈希一致）
- 设备上 `pm path` + `sha256sum` 复核 = 同一哈希

## 2026-11-10（四次修正：分组被切碎 + 块列基线）

用户反馈：「块的大小位置有错位，还有些圆角也掉了」，「还是不要把这些块分开来，要整体看」。
结论：**没有改设计**（仍按系统原有分组各成一块），改的是三处会破坏这个设计的实现缺陷。

### 一、真正的主因：细分隔子 View 把同一个系统组切成两块

- **现象**：首页下滑后，`WLAN/蓝牙/移动网络` 一块、`荣耀互联/更多连接` 另一块，中间露出一条壁纸；每块各自带一圈圆角，看起来就是「块被分开了、圆角乱了」。位置随滚动变化，因此是「有些」块。
- **根因**：分组判据「紧邻」以外，还要求「邻居必须是块成员」。系统 RecyclerView 在某些行之间会插一个**高度极小（0～2px）的分隔/占位子 View**：它自己不是块成员（高度 < 44dp），于是上一行判定为「末行」（画下圆角）、下一行判定为「首行」（画上圆角 + 4dp 上内缩）→ 一块变两块，中间还多出约 10px 空隙。
- **修法**：`GlassBlocks.neighbour()` 与 `GlassBlocks.compute()` 把高度 < `STUB_MAX_DP`(8dp) 的子 View 一律当作「胶水」跳过，不算成员也不算组边界。真正的组间 63px（18dp）占位远高于 8dp，判据不受影响。
- **验证**：同一滑动位置复现前后对比：修前 `移动网络/荣耀互联` 之间是两块（上块平底、下块圆角顶），修后是同一块内的 1px 分隔线。

### 二、`关于手机`：一个块内部出现 63px 横向台阶

- **现象**：`设备名称/处理器/运行内存` 那一块的左右边比下面的 `电池` 块窄一截，接缝处有台阶，台阶处看起来就像「圆角断了」。
- **根因**：块的水平边原先由**每行自己的宽度**减 14dp 得出。该页首行（MagicOS 大图标 hero）宽 1272px，第二行（信息宫格容器）只有 1146px 且位于 x=63。于是同一块的两行分别画在 `49..1223` 和 `112..1167`。
- **修法**：块的水平基线改为**容器级**（`HonorRenderer.updateColumn`）：`max(14dp, max(row.left)) .. min(w-14dp, min(row.right))`，每行只在自己的 bounds 内裁剪这条基线。同页所有块共用一条列基线；某行已经内缩更多时，基线整体内移而不是让那一行错位。外扩上限 6dp，避免个别异常边距的行把整页玻璃列拽偏。
- **验证**：`关于手机` 三个块实测左边 63 / 右边 1209，完全一致，块连接处竖边连续、只剩 1px 分隔线。

### 三、圆角在刚开始滚动时「消失」

- `openTop/openBottom` 原先用 `row.getTop() < 0` 判断「块延伸到视口外」，只要行的顶边越过 0 一点就立刻砍掉圆角。改为 `row.getTop() < -radius`（圆角完全滚出屏幕才砍），滚动初段的圆角不再突然变直角。

### 四、复核方式（这次用像素对拍，不再靠肉眼）

- 用模块自己的几何日志 + `uiautomator` 导出真实布局，再对 **关闭模块的原生页面** 截图做逐行/逐列差值：
  - 首页：原生与当前版本的横线位置（602/730/822/1124/1212/1383/1565/1747/1929/2098/2184/2356/2538/2720）**完全一致**；块左右竖边同为 46/1217、153/1219。
  - `显示和亮度`：横线位置（346/414/723/1056/1194/1202/1290/1439/1447/1621/1629/1696/1720/1803/1811/1971/1979/2153/2161/2321/2329/2489/2497/2657/2665）与原生**逐条吻合**。
  - 结论：几何不再是偏差来源，剩下的只是「一块被切成两块」这一个 bug，已修。
- 滑动 + 反向滑动后截图：无白块残留，块随行同步。
- `FATAL EXCEPTION` / `ANR in com.android.settings` = 0，`com.android.settings` 进程存活。

### 产物

- APK：`out\HyperBackground-Honor-1.4.5-beta2.apk`（5,385,885 字节）
- SHA-256：`A5D44A6631672EEF93A2EEC9AFD8B8426F6BC809987F9DDE8358602CEE035FE8`
- 归档：`out\archive\20261110-154637-A5D44A66.apk`；设备 `pm path` + `sha256sum` 复核一致
- 改动文件：`RowGlass.java`、`GlassBlocks.java`、`GlassStyle.java`、`HonorRenderer.java`

## 2026-11-10（五次修正：块首圆角丢失 + 描边逐行重启）

用户在同一张对照图上标出两处：「顶部底部块的圆角消失了」，「左侧块与块之间有些高亮的线看着像是块的错位」。
两个都是真 bug，都已定位到具体行。

### 一、块的**首行**被误判成中间行 → 顶角变直角、且多画一条分隔线

- **现象**：块的第一行上方出现一条横线，左上/右上角是直角；块顶比原生卡片高出 4dp（14px）。只出现在「前一个兄弟是细碎占位 View」的分组上，因此是「有些块的圆角没了」。
- **根因**：`GlassBlocks.neighbour(container, from, density)` 用 `from` 的正负来推断方向：
  ```java
  int step = from < 0 ? -1 : 1;
  ```
  `RowGlass` 查前一个兄弟时传的是 `idx - 1`，只要 `idx >= 1` 这个值就是非负的 → `step` 取 **+1**，函数从 `idx-1` **向后（向列表尾）**扫。当前一个兄弟是 `height < 8dp` 的占位/分隔 View 时，它跳过该 stub 后返回的其实是**这一行自己**，于是
  `v.getTop() - prev.getBottom() == -h < gapLimit` → `first = false`。
  结果：`rTop = 0`（直角）、`top = -stroke`（不吃 4dp 内缩）、并额外画一条 `!first` 分隔线在行顶。
- **修法**：方向改成显式参数 `neighbour(container, from, dir, density)`，前向传 `-1`、后向传 `+1`，不再从下标推断。
- **验证**（一次性高对比调试绘制，画完即删）：首页三处块顶 `y=821 / 1210 / 2183` 的左角轮廓实测为
  `92→80→73→67→63→59→56→53→52→50→49→48`，收敛到列基线 48，即 63px 圆弧；块底 `1119 / 2092` 同样为
  `88→78→71→66→62→58→55→53→51→50→49→48`。修复前同一位置在行顶就有竖边（x=48 从块顶一直到底），属直角。

### 二、描边渐变按**每行**重新计算 → 侧边变成一节一节的高亮线

- **现象**：块左右侧边在每行交界处由暗突然回亮，像「块被切成几段、彼此错位」。用户标的三条长竖条就是这个。
- **根因**：`RowGlass` 每行画自己的份额，但 `drawBlockStroke` 的渐变端点用的是**该行自己的 top/bottom**：
  `new LinearGradient(0, top, 0, bottom, STROKE_TOP, STROKE_BOTTOM, CLAMP)`。
  于是每一行都是「顶部亮白(35%) → 底部暗黑(8%)」，一个 4 行的块出现 4 个明暗循环。光泽（sheen）同理，且原先只有首行画，块内亮度也有台阶。
- **修法**：新增 `GlassBlocks.blockExtent(container, idx, density, out)`，从当前行向两侧按同一「紧邻 + 成员」判据扩展出整块上下界；`RowGlass` 把渐变端点换成**块坐标**（`ext[0] - v.getTop()` … `ext[1] - v.getTop()`），每行只画这条整块渐变落在自己范围内的那一段。`LinearGradient` 本就 CLAMP 延伸，因此不需要把端点裁到 path 内（裁了反而会把渐变重新压进单行）。sheen 也改为每行都画，公式不变。
- **验证**：首页左侧竖边在块内（y=1650…2032）亮度增量由 `114` 单调平滑降到 `63`，修复前在 y=1744、1930 处各有一次 `+45` 的突跳。

### 三、复核

- `显示和亮度`：块 `344..1197`、`1288..屏外`，左边缘 x=48 全程连续，仅在真实组间空隙处断开；顶/底角圆弧与首页一致。
- `关于手机`：块 `344..1670`、`1761..2244`、`2335..屏外`，列基线 63/1209，与原生卡片同列。
- 设置首页滚到底：最后一块下边缘实测 y≈2699（= 末行 2716 − 4dp 内缩），带完整下圆角。
- 急速上下滑动后截图：无白块残留，玻璃与行同步。
- `FATAL EXCEPTION` / `ANR in com.android.settings` = 0，模块自身异常 = 0，`com.android.settings` 进程存活。

### 产物

- APK：`out\HyperBackground-Honor-1.4.5-beta2.apk`（5,385,885 字节）
- SHA-256：`9DFB1BFBD76C1313E7AC1F3860BDF4B2C7FD0F2CFE94EDCFA82B4ED239358B70`
- 归档：`out\archive\20261110-160358-9DFB1BFB.apk`；设备 `pm path` + `sha256sum` 复核一致
- 改动文件：`GlassBlocks.java`（neighbour 方向参数 + blockExtent）、`RowGlass.java`（取邻居传方向、整块渐变）

## 2026-11-10 晚 — 二级菜单统一风格（12 个入口）+ 一批残留白卡

起因是用户报的第二组问题：账号中心、蓝牙、荣耀互联、荣耀 AI & YOYO、绿洲护眼、声音触感、通知和状态栏、应用、电池、隐私和安全、健康使用手机、荣耀钱包「二级菜单没有整合统一风格」，以及若干页圆角/白卡。**最后查下来，主因根本不在渲染代码里。**

### 一、主因：LSPosed 作用域只有 `com.android.settings`

- **现象**：8 个入口（账号中心 / 荣耀互联 / AI & YOYO / 通知和状态栏 / 电池 / 隐私和安全 / 健康使用手机 / 荣耀钱包）整页原生白卡，一丝玻璃都没有；`com.android.settings` 里的页面（蓝牙、声音、应用、护眼）正常。
- **定位**：`HonorRenderer` 里 `TARGET_PACKAGES` 明明写了 7 个包，但 LSPosed 日志里 `hb build` **只有 `(com.android.settings)` 一条来源**；把 `/data/adb/lspd/config/modules_config.db` 拉下来查 `scope` 表，只有 `com.android.settings` 一行。
- **修法**：补齐 6 行 scope（`systemmanager` / `controlcenter` / `assistant` / `id` / `wallet` / `parentcontrol`，`user_id=0`），推回设备后重启生效。日志随即出现各包的 `hb build`。
- **教训**：模块内的包名清单和 LSPosed 的作用域是**两个开关**。日志里没有某包的 `hb build`，先查 scope，别改渲染代码。

### 二、「账号安全」卡始终纯白（本轮最绕的一个）

- **现象**：账号中心一行两张卡，`个人信息` 透明、`账号安全` 纯白；`dumpsys` 显示两者结构完全一致（`FrameLayout 542x428`，子 `LinearLayout 444x428`）。
- **试错（都无效）**：清 background、清 foreground、递归清子层近白背景、给子层调 `setWillNotDraw`、静音 `ItemDecoration.onDraw/onDrawOver`、清 `ViewOverlay`。
- **定性实验**：把 tile 设 `INVISIBLE` → 白消失；把 tile 设 `alpha=0` → 白也消失（alpha 只影响绘制、不影响布局）。⇒ 白确实由 tile 子树画出。
- **真正根因**：`sweepNestedCards` 每次扫描都用**几何判据重新识别** tile（要求背景非空、可渲染为纯白）。第一轮把两张卡都清了，第二轮再扫时它们已经没有背景 → 认不出来 → 命中数 <2 → `sNestedCards` 被**空列表覆盖** → `handleCardAssignment` 里「已清扫过的 tile 拒绝再赋值」的分支失效 → 页面把白卡设回来，且此后再也不会被清。
  - 为什么只有一张白：第二轮里只有一张卡被页面重新赋值（另一张保持了已清状态）。
- **修法**：`sweepNestedCards` 的 `found` 列表**从已知 tile 起算**（`parent != null` 的保留），新扫到的再追加，不再用本轮扫描结果整体覆盖。
- **验证**：`账号安全` 纯白占比从整卡 100% 降到 0；整页纯白占比 `0.721 → 0.025`。

### 三、应用页底部整块白（内嵌 RecyclerView）

- `应用` 把「应用设置」整段做成一行的内容，里面是**第二个** `HwRecyclerView`（`#external_app_settings_list`）。列表遍历在「行」这一层就停了，永远看不到内层列表的行 → 内层各行把自己的白底画在玻璃上。
- 新增 `stripNestedLists` / `findNestedList`：在行内（≤4 层）找高度 ≥140dp 的 RecyclerView，注册进 `sManaged` 并交给 `stripList` 递归处理。内层各 app 行于是并入一个玻璃块。
- 验证：`0.342 → 0.023`。

### 四、绿洲护眼「护眼统计」卡纯白（行内 90% 宽的卡）

- 该卡是 `LinearLayout#eye_protection_statistics_container`，宽 1146/1272 = 90%、高 38%，**比宫格 tile 宽得多**，宫格判据（28%–62%）根本不看它。
- 把 `scrubTileSurface` 参数化为 `scrubSurface(v, minWpct, minHpct)`：宫格用 `75/70`，块内行用 `72/28`，并在 `applyRowGlass` 的成员行上调用。
- 验证：`0.153 → 0.032`。

### 五、荣耀钱包「快捷方式」白块（自绘卡）

- 两张卡是 `HnListCardLayout`（`#pay_password_card` 等），卡是 `onDraw` 自绘的，清背景无效；且它们嵌在 ScrollView 行的两层之下，行级遍历到不了。
- `clearRow` 现在对类名含 `ListCardLayout` 的视图补 `setWillNotDraw(true)`（声音页三张卡用同一条规则），并新增 `clearListCardDeep` 在 `stripStack` 里向下 3 层处理。
- 验证：`0.605 → 0.025`。

### 六、装饰器绘制静音 + 前景 hook（保留能力）

- `muteDecorationDraw`：对保留下来的 `ItemDecoration`，反射 hook 其 3 参数 `onDraw` / `onDrawOver` 使其空转，**保留 `getItemOffsets`**（组间 20dp 间距来自它，整体丢弃会把全页并成一块）。
- `hookCardForeground`：拦截 `View.setForeground`，对已清扫 tile 拒绝近白前景（ripple/mask 常是白的）。
- 这两条在本轮最终根因之外，属于「同类白源」的封堵，保留。

### 七、回归结果

12 个入口逐页截图，纯白像素占比（>253 阈值，越低越好）：

| 入口 | 修复前 | 修复后 |
|---|---|---|
| 账号中心 | 0.721 | **0.025** |
| 蓝牙 | 0.020 | 0.021 |
| 荣耀互联 | 0.484 | **0.022** |
| 荣耀 AI & YOYO | 0.533 | **0.026** |
| 绿洲护眼 | 0.153 | **0.032** |
| 声音和触感 | 0.025 | 0.025 |
| 通知和状态栏 | 0.601 | **0.016** |
| 应用 | 0.342 | **0.023** |
| 电池 | 0.623 | **0.026** |
| 隐私和安全 | 0.543 | **0.024** |
| 健康使用手机 | 0.455 | **0.020** |
| 荣耀钱包 | 0.605 | **0.025** |

- 圆角专项：`显示和亮度` 滚到底、`辅助功能` 滚到底、`关于手机` 首帧——下圆角均为完整圆弧，无直角、无需再拉动刷新。
- 急速滑动（6 次下 + 6 次上，120ms/1300px）后立刻截图：`应用 0.023`、`通知和状态栏 0.015`、`显示和亮度 0.021`，无白卡残留。
- `FATAL` / `ANR in` 在模块日志中 = 0；`com.android.settings` / `com.hihonor.id` / `com.hihonor.wallet` 进程均存活。crash buffer 只有一条无关的第三方 `li.songe.gkd` 崩溃（18:11 开机时）。

### 八、产物

- APK：`out\HyperBackground-Honor-1.4.5-beta2.apk`（5,389,981 字节，`BUILD_TAG = 1.4.5-beta10`）
- SHA-256：`A4FB761C80BB8E16A23C099DF4EBB4155EB05821EFC895629089FBB58FCE5BFB`
- 归档：`out\archive\20261110-190416-A4FB761C.apk`；设备 `pm path` + `sha256sum` 复核一致
- 改动文件：`HonorRenderer.java`（scope 之外的全部修复）、`build.ps1`（`scope.list` 补 6 个包）、`GlassBlocks.java` / `RowGlass.java` / `SurfaceGlass.java` 未改
- 设备侧改动：LSPosed `modules_config.db` 的 `scope` 表补 6 个包
- 验收脚本：`_an/pages.py`（12 入口逐页 + 纯白占比，已改为点击「最近的可点击祖先」）、`_an/slide.py`（急速滑动）、`_an/probe.py`（3/8/14 秒三帧）、`_an/dump_page.py`（导出 `dumpsys activity top`）

## 九、1.4.5-beta14（同日晚间）——顶栏白块 + 返回后块分裂

用户报两个小问题：① 二级菜单顶部静止时仍有一条白色块（上划时它变成模糊渐隐，要求保留渐隐）——出现在通知和状态栏、账号中心；② 二级菜单返回一级菜单时偶发「整体块被分开」，上下滑动后恢复。

### 1. 顶部白块：`clearTopChrome`

诊断日志确认白块的来源，且不是工具栏本身，而是 MagicOS 的「渐隐顶栏」组件链：

- 通知和状态栏：`HnGradientBlurTopContainer`(1272x590, InsetDrawable) → `HnBlurTopPattern` → `HwToolbar#blur_toolbar`(1272x196, 后到的 `ColorDrawable #fff2f3f6`)
- 账号中心：`HwAppBarLayout#appbar_layout`(1272x434) 同链路

它既不是分组卡也不是行，`GlassBlocks` 分块规则与 `clearCover` 都够不着，所以整页玻璃化后顶部仍留一条白。处理（`clearTopChrome`，对 `HnBlurTopPattern` / `HnGradientBlurTopContainer` / `HwAppBarLayout` / `HwToolbar` 四类生效）：

- 背景只 `setBackground(null)` **一次**（记入 `sTopChrome`），之后 ROM 在滚动中重设的背景只做 `setAlpha(0)`——`setBackground` 会 `requestLayout`，滚动中反复置空会造成 layout↔背景死循环
- `setWillNotDraw(true)` 静音容器自绘
- **不动 alpha / 不动滚动回调**：模糊渐隐由另一条渲染链（RenderEffect/模糊位图）完成，未受影响。实测静止时 y=50 透出壁纸 (187,177,196)，上划后 y=50 回到模糊色 (241,241,243)——渐隐保留

### 2. 返回后偶发块分裂：两处机制加固

自动化未能稳定复现（backdump/backprobe 多轮纯白占比 0.013–0.026），按可疑机制加固：

- **`handleCardAssignment` 放行未测量宿主**：fragment 重新挂载时行在宿主 `width<=0` 阶段就被重设卡背景，此时 `isMember` 对所有行返回 false → 旧行为 `clearRow` 把卡吞掉 → 行透明，块中间出现「洞」＝块被分开的外观。现在 `host.getWidth()<=0` 时直接放行（原生卡保留），首次布局后 strip/guard 会换成玻璃
- **`watch()` 的 layout 监听加补扫**：`onGlobalLayout` 里 `strip` 后再 `armSettleStrips(decor, 4)` + `scheduleLateSettle(decor)`（~500ms 合并去重一次），覆盖「返回动画结束后才重设卡/重挂装饰器」的迟到路径——这正是之前只有用户滑动（scroll 触发 strip）才能自愈的窗口

### 验证

- 12 入口全量回归：0.018–0.032，无回归；通知和状态栏 0.016→0.009
- 静止顶栏：通知/账号中心 y=50 均透壁纸；上划后模糊渐隐仍在
- 返回压测（显示和亮度 ×5 轮 + ×3 轮，快速 keyevent4）：worst 0.014，无白卡、无分裂带
- 模块日志 `FATAL`/`ANR` = 0

### 产物

- APK：`out\HyperBackground-Honor-1.4.5-beta2.apk`（5,389,981 字节，`BUILD_TAG = 1.4.5-beta14`）
- SHA-256：`617220EE3E0C5FAA3BA3D02AA14C8A7F6639D194C2CDC929D4E6B03DEEDA5385`
- 归档：`out\archive\20261110-194343-617220EE.apk`；设备 `sha256sum` 复核一致
- 改动文件：仅 `HonorRenderer.java`（clearTopChrome、handleCardAssignment 放行、scheduleLateSettle、sTopChrome/sLateSettlePending）
- 新增脚本：`_an/backtest.py`、`_an/backprobe.py`（返回时机抓帧）、`_an/backdump.py`（返回后带 uiautomator dump 判页）

## 十、1.4.5-beta22 —— 通知页应用开关成块 + 荣耀互联白块闪现

用户报两点：① 通知和状态栏页底部「应用通知管理」及其下所有应用通知开关应组成一个玻璃块；② 荣耀互联进入二级菜单有白色块闪一下、约 3-4 次出现一次残留。

### 1. 应用开关列表：`AbsListView` 也走列表规则

`NotificationMainFragmentListView#all_package_manager_listview` 是 **ListView 子类**，不是 RecyclerView，且父链里没有 ScrollView —— `walk()` 既不会交给 `stripList`，`stripStack` 也返回 -1，结果整段开关行只有逐行 `clearCover`，没有合并玻璃。

- `walk()` 增加 `adapterList` 判据（`AbsListView` + 宽度 >= 180dp + 至少 2 个已布局行），交给 `stripList`
- **必须继续递归进 ListView 子项**：通知页 ListView 里有多张 `HnListCardLayout`（card_capsule / card_notification_status_bar / card_control_center / card_notification_manager / card_more_notification_settings，均自绘白卡）。只 `return stripList(g)` 会让它们整片变白（实测 white 0.009 → 0.300）。改成 `stripList` 后继续 `clearCover` + 子项 walk，`glassStandaloneCard` 才能换成玻璃。RecyclerView 分支保持原样不动。
- `ensureChildListener` / `dropCardDecorations` 对非 RecyclerView 直接跳过（省掉必失败的反射）
- 效果：底部玻璃覆盖 0.04–0.18 → 0.40–0.49，顶部 white 回到 0.014

### 2. 荣耀互联首帧白块：三层时序加固

复现脚本 `_an/flicker.py`（每轮 force-stop → 冷启）抓到：白帧只在 **tap 后第一帧**，`0.25s` 后自动消失，约 1/12~3/12 轮出现，纯白区 y≈1200–1650 正好是 `GridLayout#grid_pref` 的第一排 `card_item`（573×381）。它是**异步填充的服务卡片**，晚于所有 settle strip 才拿到白卡背景。

- **hook 层首帧拦截**（唯一能覆盖 inflate 阶段的点）：页面进入 1.5s 内、view 位于设置列表内、赋值的 drawable 是卡片类（`CardDrawable` / 近白 / `StateList`/`Gradient`/`Ripple`/`Inset`/`Layer`）→ 拒绝赋值。注意**不能每次都 `clearRow`**：ROM 每个 layout 都会重设卡，`setBackground(null)` 触发 `requestLayout` 会形成循环，本身就是「残留」来源；只在旧白卡仍在时清一次
- **冷启解码不再阻塞去白**：`apply()` 在 `bitmap == null` 时原先直接 return（解码 0.2–0.5s 内整页原生白卡）。改为先 `install(..., null)`：占位层用深色 `0xff191a1f`，`sLayerKey = null`，解码完成后 `syncLayer` 走「换壁纸」分支补上真图
- **前 2 秒逐帧 scrub**：`guardFrame` 对 member 行每隔一帧 `scrubSurface(v, 35, 12)`。阈值是相对每一层的，宫格行 1230×1650 里 `card_item` 只占父宽 47%，旧的 72%/60% 判据在第二层就把它挡掉了；35/12 能进到两列卡片又不会碰图标底板
- 配套：`isTileCard` 接受半透明近白卡（原 `isFlatSurface` 要求 alpha=255）；`sweepNestedCards` 的 `maxW` 62%→80%；`cand >= 2`→`>= 1`（宫格卡是逐张就位的，首帧只有一张白卡时也必须清）；未发现任何 tile 时忽略 400ms 节流、每个 pre-draw 帧重扫

### 验证（用户反馈后不再加轮次）

- 荣耀互联冷启 **32 轮 + 同进程反复进出 8 轮**：首帧纯白占比全部 0.032–0.052，无一片白块（修复前约 1/12~3/12 轮出现 0.10–0.16）
- 通知页底部玻璃覆盖 0.40–0.49；12 入口全量回归 white 0.014–0.035，无回归
- 模块日志 `FATAL`=0、`ANR`=0

### 产物

- APK：`out\HyperBackground-Honor-1.4.5-beta2.apk`（5,389,981 字节，`BUILD_TAG = 1.4.5-beta22`）
- SHA-256：`357000134FE8D85050EE868D67B135B80BD5A153BE1FA00801C9F704576299D4`
- 归档：`out\archive\20261110-210417-35700013.apk`；设备 `sha256sum` 复核一致
- 改动文件：仅 `HonorRenderer.java`
- 新增脚本：`_an/bottom.py`（滚到底截图+dump）、`_an/flicker.py`（冷启多帧）、`_an/inout.py`（同进程反复进出）、`_an/backtest.py`、`_an/backprobe.py`、`_an/backdump.py`

## 1.4.5-beta23 — 静止画面「块被分开 / 白块残留」根因修复（2026-11-10 22:00）

### 诊断（用户保持手机不滑动，只做只读取证）

- `dumpsys activity top`：`dashboard_container` 的 14 个 `dashboard_tile` 组内**紧邻无缝**（247→429→611）、组间 63px、顺序正常，全部 visible / 1272×182 —— 按 `GlassBlocks.compute` 应合并成 4 块。
- 屏幕像素：每行都是独立 18dp 圆角卡、行间约 24px 透明缝 —— 正是 `RowGlass` 把每行判成 `first && last` 时上下各留 `V_INSET_DP=4` 的结果。
- `dumpsys gfxinfo`：`Total frames rendered: 704`，隔 2s 仍是 **704**；两张截图仅状态栏时钟不同 → 设置窗口**零重绘**。
- 结论：**布局数据是对的，屏幕像素是旧的**。最后一次 draw 发生在返回动画/首帧的过渡布局里，之后没有任何 invalidate，错误帧被冻结；滑动才触发重绘，所以「一划就好」。白块残留同源（清了 drawable 或拒绝赋值，但像素没刷）。
- 注意：日志里 `sBlockLog<40` / `sStripLog<60` 有配额，21:25:59 之后没有 blocks/strip 行**不代表** strip 没跑，别拿它当证据。

### 修复（只改 `HonorRenderer.java`）

- 新增 `invalidateView()` / `forcePageRedraw()`：后者在稳定点逐 View `invalidate()`（跳过壁纸层）。`invalidate()` 不 requestLayout，不会回到 `onGlobalLayout` 形成循环。
- `scheduleLateSettle()` 由单个 500ms 改为 **500ms + 1400ms 两级**（`postSettle` + `sFinalSettlePending`），覆盖「从二级菜单返回」——返回不新建 Activity，`install()` 的补扫不会重跑，宿主要在动画结束才重绑行。
- `install()` 的 800ms 兜底、两次 settle 都在 `strip()` 后追加 `forcePageRedraw(decor)`。
- `handleCardAssignment()` 四个分支补 `invalidateView(row)`：首帧拒绝赋值、nested 卡、已是 RowGlass 的 member、非 member 清卡。这些路径**没有改任何 drawable**，原先完全没有失效。
- 未改 `RowGlass` 的 first/last 动态判定（保留 RecyclerView 复用时的正确性），靠「稳定后重绘」让它读到最终布局。

### 验证（一次针对性抽验，不做多轮压测）

- 模块日志 `hb build 1.4.5-beta23`；`blocks: [1,239-565][5,628-1538][5,1601-2511][2,2574-2938] rows=13`（整块，无单行块）。
- 蓝牙二级页 → 返回 → 静止：WLAN/蓝牙/移动网络/荣耀互联/更多连接 为**一整块连续玻璃**，组内无缝、块端圆角正常；二级页顶部无白块（渐隐保留）。
- 静止后再等 3s：两张截图内容区差异 **0 像素**；`gfxinfo` 426 → 426，证明没有常驻定时器 / 无限重绘。
- `FATAL`=0、`ANR`=0（21:50 后无新崩溃；crash buffer 里 18:11 的是 GKD 应用历史记录）。

### 产物

- APK：`out\HyperBackground-Honor-1.4.5-beta2.apk`（5,394,077 字节，`BUILD_TAG = 1.4.5-beta23`）
- SHA-256：`1067E2CE14FBBF8152531FE8765A05EDF2387B7B9CA616F91CE321607936BF7F`
- 归档：`out\archive\20261110-215802-1067E2CE.apk`；设备 `sha256sum` 复核一致
- 安装：`adb push /data/local/tmp/hb23.apk` + `pm install -r -g -t` → `Success`；**未重启设备**，只 `am force-stop com.android.settings` 后重开
- 改动文件：仅 `HonorRenderer.java`

## 1.4.5-beta24 — 慢速滚动时的「块被分开」修复（2026-11-10 22:12）

### 用户反馈 / 取证

- beta23 装机后用户实测：**慢速往下滑仍出现分组被拆成独立圆角卡，来回滑动又变回一整块**。
- 保持画面不滑动取证：截图是**混合态**（生物识别和密码→存储 是整块，健康使用手机 / 荣耀钱包 / 辅助功能 三行各自独立圆角）；`uiautomator` 显示这三行组内紧邻（2060-2242 / 2242-2424 / 2424-2606）；`gfxinfo` 218→218 零重绘。
- 根因补漏：beta23 的 `forcePageRedraw` 只挂在 `onGlobalLayout`（进页面 / 返回），**滚动走 `onScrollChanged`，停止后没有任何兜底**；且 `RowGlass.draw()` 每次按当时瞬时子 View 顺序算 first/last，慢速滚动中新 attach 的行与已绘制的行结论不一致 → 混合态被冻结。

### 修复（`HonorRenderer.java` + `RowGlass.java`）

- `RowGlass.pinBlock(first, last, rampTop, rampBottom)`：把 strip pass 基于**整个列表**一次算好的分组结果固化到每行；draw 优先读固化值，未 pin 的（hook 直接装的）回退原动态逻辑。ramp 用相对 `v.getTop()` 的偏移，滚动同步平移不会失效；只有值变化才 `invalidateSelf()`。
- `stripList` 把 `GlassBlocks.compute()` 的 blocks 传给 `applyRowGlass`；新增 `pinRowGlass()` 按坐标容差 ±2 找所属块，给每行 pin first / last / extent。
- `watch().onScrollChanged` 增加 `scheduleScrollSettle(decor)`：160ms debounce（每次滚动 remove + post，连续滚动只排一次）→ `strip` + `forcePageRedraw` + 复用 `scheduleLateSettle` 的 500 / 1400ms 两级。不是常驻定时器。

### 验证（两轮，不多测）

- `hb build 1.4.5-beta24`；慢速下滑 1.5s 后静止：三组全为连续整块，无独立圆角卡 / 透明缝；再「上滑 + 下滑」来回一次后静止仍全为整块。
- 静止后 `gfxinfo` 194→194 / 1151→1151，证明滚动 settle 是一次性的、没有常驻重绘；模块日志无 `FATAL` / `ANR` / `strip failed`。
- 上半部仍有一层从白到透明的顶栏渐隐（此前要求保留的效果），不是白块，也不是分裂。

### 产物

- APK 5,394,077 B，`BUILD_TAG = 1.4.5-beta24`
- SHA-256：`8632F4C5028D7CEDE1868DB1513A2C1BF3D7FEC61E3FD5C54E360E41C7F6BFA3`
- 归档：`out\archive\20261110-221223-8632F4C5.apk`；设备 `sha256sum` 复核一致
- `pm install -r -g -t` → `Success`；**未重启设备**（只 force-stop settings 重开）
- 改动文件：`HonorRenderer.java`、`RowGlass.java`

## 1.4.5-beta25 — 消除「先分裂、闪一下才合并」（重绘延迟）

### 用户反馈

- beta24 实测：**现象是「先是分开的模块，然后变为连续整块，闪一下恢复正常」**，用户判断是重绘延迟 —— 判断正确。
- 机制：beta24 只在 `stripList()` 里 pin 分组，而 strip 是 **post 出去**的，滚动期间的那一帧可能先按瞬时子 View 顺序画出分裂像素；`scheduleScrollSettle()` 又要等滚动停止 160ms 才补救。于是「错一帧 → 兜底纠正」，肉眼就是闪一下。

### 修复（`HonorRenderer.java` + `RowGlass.java`）

- **把分组结果提前到每帧绘制之前**：`guardFrame()`（`OnPreDrawListener`，layout 之后 / draw 之前）现在先 `GlassBlocks.compute(list, density)` 一次算出**整个列表**的分组，再对每行 `pinRowGlass()` 重新固化；`applyRowGlass()` 同样传入 blocks；`handleCardAssignment()` 的 hook 分支也补 compute + pin。这样每一行在 draw 时读到的都是同一个一致答案，不再产生「先分裂」的中间帧。
- `RowGlass.pinBlock()` 的比较加 **1.5px 容差**（`Math.abs(delta) > 1.5f`）：滚动时 ramp 相对偏移理论恒定，但 1px 取整抖动若用精确浮点比较会每帧 `invalidateSelf()`，导致常驻重绘甚至与 pre-draw 互激。
- `scheduleScrollSettle()` **降级为保险**：注释与实现都明确它只兜「滚动结束后 ROM 又 re-bind 卡片、此后没有 pre-draw」的情况，不再是修复主路径；仍是一次性 debounce，无常驻定时器。
- `BUILD_TAG = 1.4.5-beta25`。

### 产物

- APK 5,394,077 B；SHA-256 `7EE78B163CD003F3CDFA23A0AFF6777B9760981C701951DE5EFD13910401A697`
- 归档：`out\archive\20261110-222139-7EE78B16.apk`；设备 `sha256sum` 复核一致
- `pm install -r -g -t` → `Success`；LSPosed 日志确认 `hb build 1.4.5-beta25` 已生效；**未重启设备**
- 改动文件：`HonorRenderer.java`、`RowGlass.java`；`out/handoff/src` 源码副本已同步（8 个 java 全部一致）
- **验证交由用户实测**：按用户要求「不用你测试」，本次未做滚动压测，等真实反馈

## 1.4.6 — 完美版冻结、代码整理、玻璃与分隔线全面可调

### 用户要求

> 「现在这个版本是最好的一个，根据这个版本整理代码，设置等一切项目相关东西，看是不是能进行简化。考虑一些能不能做出一个控制开关控制玻璃效果，比如透明度，折射，反射，描边等。还有中间的分隔线能否有一个编辑选项，颜色透明度什么的。保存好这个完美版本，继续进行开发。」
>
> 「重新确认一遍所有更改效果的功能都是生效的。把不生效的功能说明并去掉。在最终成果前不要一次性总结。」

### 1. 冻结 beta25 完美版

- 目录 `baseline/beta25-perfect/`：APK 两份（同一哈希）、完整源码、`build.ps1`、`handoff/`、`SHA256SUMS.txt`。
- Git 提交 `4f13644` + 标签 `beta25-perfect`（`git config user.name "HB Port"` 解决首次提交的 identity 报错）。
- 新建 `.gitignore`：只跟踪源码 / 构建脚本 / handoff 文档 / baseline，诊断脚本与截图不再污染 diff。
- `HonorRenderer.java.glassbak` / `.variant` 移入 `_scratch/`。

### 2. 代码整理（保持默认外观逐像素等于 beta25）

| 文件 | 变化 |
|---|---|
| `Prefs.java`（新增） | 统一远程偏好读取器；收掉 `AppearanceState` 里四组重复的 readBoolean/String/Int/Long，兼容远程把数字存成 String 的旧安装 |
| `GlassConfig.java`（新增） | 唯一参数中心：所有 `KEY_*`、`DEF_*`、不可变快照、`version`、`sDirty`；`writeDefaults()`；`describe()` |
| `AppearanceState.java` | 退化为纯壁纸状态，不再持有 `glassAlpha` / `glassSheen` / `glassEnabled`；`read()` 开头刷新 GlassConfig |
| `GlassStyle.java` | 绘制中心，可调数值全部从 `GlassConfig.get()` 读；新增折射光带、描边开关/强度/粗细、分隔线自定义颜色/alpha/线宽/内缩、动态几何 |
| `RowGlass.java` / `SurfaceGlass.java` | 接入共享配置，两种玻璃用同一套参数 |
| `HonorRenderer.java` | 删掉三个散落静态字段，改 `glassEnabled()`；`apply()` 消费 `consumeDirty()` 做 live 重绘 |
| `HonorSettingsUi.java` | 重写为 5 张卡（背景 / 液态玻璃 / 组内分隔线 / 几何 / 关于）；控件构造收敛为 `page/card/addSlider/addSwitchRow/...` 公共方法 + `Formatter/Writer` 接口，修正旧 SeekBar 忽略 `range[0]` 的偏移 bug |
| `ARCHITECTURE.md` | 重写，删掉早期 `nativeGlass` / `glassCard` / 滚动轮询等已不存在的架构描述 |

- 分组判据常量（`MIN_ROW_HEIGHT_DP` / `GAP_ROUND_DP` / `STUB_MAX_DP` 等）**刻意不参数化**：暴露它们会破坏已验证的分组行为。
- beta25 的 pre-draw `guardFrame()` + `pinBlock()` 主修法原样保留，1.4.6 只在其之上叠参数层。

### 3. 逐项验证（临时文件驱动验证包，非交付物）

为绕开「模块进程未被注入、精简设置页打不开」的限制，在 `GlassConfig.read()` 临时挂了
`applyDebugFile()`，从 `/sdcard/hb_cfg_test.txt` 读 `key=value` 覆盖参数。为避免冷启壁纸
解码造成截图噪声，统一用 `HOME → am start -a android.settings.SETTINGS` 在**同一 settings
进程内**触发配置重读，再截图 diff。结果：

- **确认生效**：`fill`、`sheen`、`refr`、描边开关 / 强度 0 / 粗细、分隔线开关 / 自定义红色 /
  线宽 / 内缩（左右各延伸 56px）/ alpha 梯度（红像素 1450 → 2893 → 23752）、圆角、
  左右边距、上下边距。
- **总开关初次不生效**：`strip()` 只负责装玻璃，`clearRow()` 明确跳过 `RowGlass` /
  `SurfaceGlass`，已装上的 drawable 不会因配置变化而消失。修复：新增
  `HonorRenderer.removeGlass(View)` 递归卸载，并在 `cfgChanged && !glassEnabled()` 时调用。
  修复后 `off` vs `base` 在 y>800 区域变化 1,963,136 像素，`on` 恢复玻璃块。
- 设置页总开关副标题从「回到系统原生卡片」改为实测准确的「移除玻璃块，设置项直接透出背景图」。

### 4. 本轮查出的「不生效」项及处理

| 项 | 问题 | 处理 |
|---|---|---|
| 文件驱动验证钩子 | 只是验证手段，不是产品功能 | 从 `GlassConfig.java` 删除 `applyDebugFile()` 及其调用；设备 `/sdcard/hb_cfg_test.txt` 与全部验证脚本/截图删除；最终 dex 已校验不含 `hb_cfg_test` / `applyDebugFile` |
| `hb_cfg_version` / 旧 `honor_config_version` | 写入端 `putInt`，读取端 `getLong`，类型不匹配 → 每次都退回 0，日志 `v=0` 误导，版本这个「强制重绘」保险形同虚设 | `version` 统一为 `int`，用 `Prefs.num()` 读 |
| 「恢复默认玻璃参数」 | 依赖 `Editor.remove()`；`pr` 镜像的是本地整张 Map，本地删键不保证远程也删 → 可能静默失效 | 改为 `GlassConfig.writeDefaults(e)` **显式覆写**全部默认值；默认值集中到 `DEF_*`，与 `read()` 共用一份 |
| 「清除背景」 | 同上 | 改为显式写 `mime_home=""` / `modified_home=-1` / `size_home=0` / `honor_home_enabled=true` |
| 「颜色不透明度」 | 只在「自定义颜色」打开时生效，UI 未说明，看起来像无效控件 | 不删除（设计如此，且删除会破坏 beta25 默认线色）；标题改为「颜色不透明度（仅自定义色）」，「自定义颜色」副标题与「关于」卡明确说明 |
| 「描边强度 = 0」与「轮廓描边关闭」 | 效果相同 | 属设计冗余，不删除；副标题注明二者等价 |
| 模块自身精简设置页 | 设备 scope 表缺 `app_pkg_name='com.ciallo.hyperbackground'` 自身这一行，`installModuleUiHooks()` 从未运行，打开模块设置仍是原版 Compose UI | 已向设备 `modules_config.db` 的 `scope` 表插入该行（保留）；按用户要求**未重启设备**，LSPosed daemon 重新加载后生效，由用户自行重启 |
| 直接改本地 `backgrounds.xml` / 离线改 `modules_config.db` | LSPosed daemon 把远程偏好缓存在内存，离线改动不会立即被读取 | 不作为功能交付；正式链路只走模块进程 `pr` editor |
| 实验残留 `honor_glass_alpha=0` | 会让回退 beta25 时玻璃全透明 | 1.4.6 参数改用 `hb_` 新命名空间，旧键不再被读取；设备本地 XML 与 `modules_config.db` 中两条实验键已删除（self-scope 行保留，权限/属主复原） |

### 5. 最终产物（1.4.6-beta2，clean build）

- `BUILD_TAG = 1.4.6-beta2`；默认快照（LSPosed 日志 `23:28:19` 实测）：
  `glass=true fill=153 sheen=40 refr=0 stroke=true/100/10 div=true/false/#ffffff/20/10 geo=180/140/40 v=0`
  —— 与 beta25 完美版完全一致，实验残留的 `fill=0` 已不复现。
- 分组日志正常：`blocks: [1,239-565][5,628-1538][5,1601-2511][2,2574-2938] rows=13 on dashboard_container`；无 FATAL / ANR。
- APK 5,398,173 B，SHA-256 `1BF19935B996FEEB90BE6CA037A35B0E379744B7FFD90E892D89940D7040F6DF`
- 归档 `out/archive/20261110-232743-1BF19935.apk`；`pm install -r -g -t` → `Success`；**未重启设备**。
- 产物校验：`classes2.dex` 含 `1.4.6-beta2`(2) / `hb_cfg_version`(1) / `hb_fill_alpha`(1)，
  不含 `hb_cfg_test` / `applyDebugFile` / `1.4.6-beta1` / `honor_glass_alpha`(0)；
  `scope.list` 含 7 个目标包 + 模块自身。

### 6. 仍待用户确认

- 精简设置页需要用户**自行重启一次设备**让 self-scope 生效；重启后打开模块设置应看到 5 张卡，
  调参后切回「设置」即生效。渲染侧已可正常使用，不依赖重启。
- 本轮按「不用测那么多轮 / 不用你测试」的要求，只做了装机后一次日志确认，未做滚动压测与
  设置页端到端点击验证；等用户实测反馈。
