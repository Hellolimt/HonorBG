# ARCHITECTURE — 逐文件说明

> 源码唯一来源：`src/com/honor/hb/`。所有类都在 `package com.honor.hb` 下（包级访问，同包内互相调用，故多为 `final class` + 包内 `static` 方法）。
> 模块对外依赖只有 `io.github.libxposed.api`（`XposedModule`）与 Android SDK；不静态链接任何 Honor 私有 API。
> 本文描述 **1.4.6 及以后** 的实现。历史排错过程见 `CHANGELOG.md` / `KNOWN-ISSUES.md`；
> beta25 完美版源码冻结在 `baseline/beta25-perfect/`，git 标签 `beta25-perfect`。

## 概览：三个进程、三条 hook 链

`HonorHookEntry.onPackageLoaded` 按包名分流：

```
com.android.settings        → installLifecycleHooks() + HonorRenderer.onProcessLoaded()
                               （渲染层：磨砂背景 + 分组玻璃块）
com.ciallo.hyperbackground → installModuleUiHooks()
                               （配置层：精简设置页 HonorSettingsUi）
com.android.systemui       → installStartingWindowHooks()
                               （启动层：跳过白色起始窗口，解决白闪）
```

配置不直接 IPC，而是走 **LSPosed 远程偏好**：模块进程的 `HonorSettingsUi` 经混淆类 `pr`
的 editor 写入 `SharedPreferences("backgrounds")`；`pr` 在 commit 时把**整个 Map** 镜像到
远程通道；设置进程用 `XposedModule.getRemotePreferences()` 读取。新增任意参数键都不需要
改动原 APK。配置版本号 `hb_cfg_version` 每次编辑 +1，页面 onResume 时检测到变化就
整页重绘，因此「改完切回设置即生效」，无需重启、无需常驻轮询。

> **键命名空间**：1.4.6 的全部玻璃参数使用 `hb_` 前缀。beta25 用的 `honor_glass_*`
> 不再被读取 —— 这样即便 LSPosed 远程偏好缓存里残留着旧值（含开发期写入的调试值），
> 也不会污染 1.4.6 的默认外观；回退到 beta25 前请先清除那些残留键。

---

## HonorHookEntry.java

`final class HonorHookEntry extends XposedModule`，模块唯一入口（`java_init.list` 指向它）。

关键常量：`SETTINGS_PACKAGE` / `MODULE_PACKAGE` / `MODULE_MAIN_ACTIVITY` /
`SYSTEMUI_PACKAGE` / `TARGET_PACKAGES`。

- **settings 分支**：hook `Instrumentation.callActivityOnCreate` / `callActivityOnResume`
  （及 `Activity.onResume` 兜底）触发 `HonorRenderer.onActivityReady`，并调
  `HonorRenderer.onProcessLoaded()` 完成进程级初始化。
- **module 分支**：hook `Activity.onCreate` 与 `onActivityResult`，在主线程 post
  `HonorSettingsUi.show(activity)`，用精简设置页替换原 Compose UI。
- **systemui 分支**：`installStartingWindowHooks()` 反射 hook 起始窗口相关类，目标包为
  settings 时返回 `null`，跳过白色起始窗口。Honor 上 `targetActivityInfo` 可能为 null，
  所以 `startingWindowPackage()` 会遍历 `ActivityInfo` / `ComponentName` /
  `ActivityManager$RunningTaskInfo` 字段回退查找目标包名。

> `TARGET_PACKAGES` 只是命中白名单；是否真正注入由 LSPosed 的 **scope 表**决定。
> `build.ps1` 写入 `META-INF/xposed/scope.list`，两边必须一致，缺一个包就会静默不生效。

---

## Prefs.java（1.4.6 新增）

**唯一的偏好读取器**。原先 `AppearanceState` 里有四组几乎相同的
`readBoolean / readString / readInt / readLong`（各自处理 primary-vs-mirror 与
`ClassCastException`），现在收敛成一个类：`of(XposedModule)` 打开 `backgrounds` 与
`settings_appearance` 两个远程组，`owner(key)` 选出真正持有该 key 的组，再提供
`bool / str / num / lng / setContains`。远程 prefs 从通用 Map 重建，旧安装可能把数字存成
字符串，`num` / `lng` 会在 `ClassCastException` 时回退解析字符串。`clamp` 也放这里共用。

---

## GlassConfig.java（1.4.6 新增，玻璃参数唯一来源）

一份**不可变快照**，`GlassConfig.get()` 在每一帧被各个 painter 读取。

```
HonorSettingsUi --写--> SharedPreferences("backgrounds")
       |                        |
       |   pr 的 editor 在 commit 时把整个 Map 镜像进 LSPosed 远程偏好
       v                        v
   KEY_* 常量  <-------  GlassConfig.refresh(XposedModule)
                              |
                 sCurrent 快照 -> GlassStyle / RowGlass / SurfaceGlass
```

- **所有 key 与默认值都在这里**，设置页和渲染端共用 `KEY_*`，不再各写一份字符串字面量。
- 小数统一用 **x10 整数**存（`cornerX10=180` → 18dp），便于跨进程 Map 与 SeekBar 直连。
- `refresh()` 比较新旧快照，变化时置 `sDirty`；`HonorRenderer.apply()` 消费该标志，若页面
  已安装则 `strip(decor)` + `forcePageRedraw(decor)`。
- **默认值 = beta25 完美版**：`fill=0x99(153) sheen=40 refr=0 stroke=on/100%/1dp
  divider=on/跟随系统/1px/16dp geo=18dp/14dp/4dp`。折射、自定义分隔线颜色、几何调整
  全部默认关闭/取原值，未调参时画面与 beta25 一致。
- `describe()` 输出一行紧凑快照，LSPosed 日志里可直接确认当前生效参数。
- `DEF_*` 常量是**唯一一份默认值**，`read()` 的 fallback 与设置页「恢复默认」都引用它。
  `writeDefaults(Editor)` 把全部默认值**显式覆写**回去；不能用 `remove()` —— `pr` 镜像的是
  本地 Map，本地删掉的键不保证在远程侧也被删除，那样「恢复默认」会静默失效。
- `version` 是 `int`（写入端用 `putInt`）。早期用 `getLong` 读它，因类型不匹配每次都退回 0，
  日志里的 `v=0` 曾误导排查。

| 组 | key（`hb_` 前缀） | 范围 | 默认 |
|---|---|---|---|
| 总开关 | `hb_glass_enabled` | bool | true |
| 玻璃不透明度 | `hb_fill_alpha` | 0–255 | 153 |
| 反射/高光 | `hb_sheen` | 0–255 | 40 |
| 折射光带 | `hb_refraction` | 0–100 % | 0（关） |
| 描边 | `hb_stroke_enabled` / `_alpha` / `_width_x10` | bool / 0–100 % / 0.5–3dp | on / 100 / 1dp |
| 分隔线 | `hb_divider_enabled` / `_custom` / `_color` / `_alpha` / `_width_x10` / `_inset_x10` | bool / bool / #RRGGBB / 0–100 % / 0.2–4px / 0–32dp | on / 关 / 跟随系统 / 1px / 16dp |
| 几何 | `hb_corner_x10` / `_h_inset_x10` / `_v_inset_x10` | 0–30dp / 0–28dp / 0–12dp | 18 / 14 / 4 dp |
| 版本 | `hb_cfg_version` | int，每次编辑 +1 | 0 |

---

## AppearanceState.java

设置进程里读取「当前壁纸」的纯数据类（不碰 UI，不再管玻璃参数）。

- `Prefs.of(module)` → `backgrounds` / `settings_appearance`；key 形式 `<key>_home`：
  `mime_home` / `opacity_home` / `blur_enabled_home` / `blur_radius_home` / `modified_home`
  / `honor_home_enabled`。
- `enabled` = mime 非空、不是 `application/octet-stream`、`modified_home >= 0` 且 `honorEnabled`。
- 候选背景文件（按序）：`background_home.random.bin` → `background_home.bin` →
  `backgrounds/home.bin` → `ui_background.bin` → `settings_appearance/home.bin`。
- `read()` 开头调 `GlassConfig.refresh(module)`，一次页面进入同时刷新壁纸与玻璃快照。

---

## HonorSettingsUi.java

模块进程里的**精简设置页**，替代原 HyperBackground 的 Compose UI。

- `show(activity)`：`applyPalette`（浅/深两套色板）→ `applyWindow` →
  `ScrollView` + `LinearLayout`，五张卡：**背景 / 液态玻璃 / 组内分隔线 / 几何 / 关于**。
- 复用控件：`page / card / addCard / addTitle / section / addDivider / textColumn / row /
  button / addSwitchRow / addActionRow / addSlider`。原来三个 add*Row 各写一遍标题+副标题
  的两层 `LinearLayout`，现在统一由 `textColumn()` 生成；滑杆统一为
  `addSlider(title, value, min, max, Formatter, Writer)`，修正了旧版忽略 `range[0]` 的偏移 bug。
- `addColorRow()`：`#RRGGBB` 输入 + 实时色块预览 + 8 个预设色板，写入
  `hb_divider_color` 并自动打开 `hb_divider_custom`。
- **一次编辑一次同步**：`editor(sp)` 在同一个 editor 里写入目标 key **和**
  `hb_cfg_version + 1`，`commit()` 触发一次完整远程镜像。
- `restoreDefaults()`：`GlassConfig.writeDefaults(editor(sp))` 显式覆写全部默认值。
- `clearBackground()` 同理写 `mime_home=""` / `modified_home=-1` / `size_home=0` /
  `honor_home_enabled=true`，不依赖 `remove()` 的远程传播。
- **有条件生效的控件**：「颜色不透明度」只在「自定义颜色」打开时生效（关闭时线色跟随
  系统深浅色，这是 beta25 默认外观）。设置页标题与「关于」卡里都写明了这一点。
- `pick()` / `importImage()` / `prefs()`：反射混淆类 `pr`（`Class.forName("pr")`，构造
  `(Context)`，方法 `p(String, Uri, String)`）完成文件拷贝与取得 `SharedPreferences`。

---

## GlassStyle.java

**液态玻璃绘制中心**：只负责 `Canvas` 上的 fill / stroke / divider，所有可调数值从
`GlassConfig.get()` 读；几何判定常量保持固定（暴露它们会破坏已验证的分组）。

- 固定常量（分组判定用，不可调）：`MIN_ROW_HEIGHT_DP=44`、`MAX_ROW_HEIGHT_FRAC=0.62`、
  `MIN_ROW_WIDTH_FRAC=0.55`、`GAP_ROUND_DP=4`、`STUB_MAX_DP=8`、`GROUP_GAP_DP=20`。
- 动态取值：`cornerRadiusDp()` / `hInsetDp()` / `vInsetDp()` / `dividerInsetDp()`，
  默认 18 / 14 / 4 / 16 dp。
- `drawBlockFill`：半透明填充 + 对角 sheen（深色减半）+ **可选边缘折射光带**。折射用整块
  坐标的垂直渐变描边，每行画自己那一段；强度 0 时整段跳过。
- `drawBlockStroke`：单层顶亮底暗渐变 hairline，受描边开关 / 强度（缩放 alpha）/ 粗细控制。
- `drawDivider`：默认用 ROM 匹配的浅/深线色，自定义模式下用用户颜色 + alpha + 线宽。
- `isDark` / `cardRgb` / `maskColor` / `layerAlpha` / `dp` 等小工具保持不变。

---

## GlassBlocks.java

**分组判定的唯一规则**（纯几何，不看 resource-id）：`isMember` 判断某子 View 是否是
分组里的行；`neighbour` 跳过零高度 / 细线胶水 View；`blockExtent` 算出整块在容器里的
上下界；`compute` 一次性算出整个列表的所有 `Block`。

> 组内的行紧邻，组与组之间有 63px 空隙；MagicOS 各页面私有 id 会变，几何关系不会变。
> 这一层**刻意不参数化**：改这里的阈值会直接破坏已验证的分组。

---

## RowGlass.java / SurfaceGlass.java

- `RowGlass`：一个 **Drawable**，画「一个分组中的一行」那一小块玻璃。为什么要每行各画
  而不是整块一个背景：`RecyclerView` 滚动时容器自己的 display list 不重录，整块面板会
  冻结在原地留下白色残影；行的 background 属于行自己的 render node，会跟着滚。
  - 块首画顶部圆角、块尾画底部圆角，中间行画左右边与顶部分隔线；水平接缝推到行界外
    被 `clipRect` 裁掉，合并后是一条连续 hairline。
  - `pinBlock(first,last,rampTop,rampBottom)`：把 `GlassBlocks.compute()` 的结果固化到每行，
    `draw()` 只读固化值。**必须在 pre-draw 里重新 pin**（见 HonorRenderer 的 guardFrame），
    否则慢速滚动时新 attach 的行会按瞬时子 View 顺序画成独立圆角卡 —— 「先分裂再闪回」。
  - 1.5px 死区：滚动时 ramp 相对偏移有取整抖动，精确浮点比较会每帧 `invalidateSelf()`。
- `SurfaceGlass`：列表之外的自由卡片（蓝牙 / 荣耀互联 的开关栏）用整张圆角面板，
  几何与 `RowGlass` 共用同一组动态取值，保证上下对齐。

---

## HonorRenderer.java — 核心渲染器（最大的文件）

设置进程里的渲染主循环。按职责分成几段：

**页面进入 `apply()`**
`AppearanceState.read()`（内部刷新 GlassConfig）→ 消费 `consumeDirty()` →
`applyState(activity)`（标记护眼页）→ 若 decor 已装过 layer，则 `syncLayer + strip`，
配置有变化时额外 `forcePageRedraw`；否则 `install()` 装壁纸层。
冷启壁纸尚未解码完时**不 return**，先装深色占位层并 strip，解码完成后 `syncLayer` 换真图。

**背景层 `install()` / `buildLayer()`**
`decor` / `content` 背景置空、状态栏透明 → `setWindowWallpaper` → `ImageView(CENTER_CROP)`
插到 decor 第 0 位，`RenderEffect.createBlurEffect` 处理配置的模糊。随后 `strip` +
`armSettleStrips` + `watch` + 800ms 兜底 strip/重绘。

**白卡清理 `strip()` / `walk()`**
递归遍历整棵 view 树，按容器类型分派：`RecyclerView` → `stripList`；`ScrollView` 堆叠页
→ `stripStack`；`AbsListView` / 通知页列表 → 先 `stripList` 再继续递归子项。命中黑名单
resource-id 的跳过。清除手段包括 `clearRow` / `clearTile`（含 foreground、ViewOverlay、
子层、`setWillNotDraw`、装饰器 onDraw 静音）等，详见 `KNOWN-ISSUES.md`。

**两个 hook（进程级，只装一次）**
`hookCardBackground` 拦 `setBackground` 与 `setBackgroundDrawable`（ROM 在 ItemDecoration
里给行重新贴原生白卡时拒绝赋值并补 RowGlass）；`hookCardForeground` 拦 `setForeground`。
这两个 hook 都要求 `glassEnabled()`，因此总开关能干净地回退到原生外观。

**逐帧守卫 `ensureFrameGuard` / `guardFrame`**
`ViewTreeObserver.OnPreDrawListener`（layout 之后、draw 之前）。每帧先
`GlassBlocks.compute(list, density)` 一次算出整表分组，逐行 `pinRowGlass()`；必要时补装
RowGlass、清残留白卡，并在页面进入前 2 秒逐帧 `scrubSurface` 处理异步构建的宫格卡。
**这是「不出现分裂帧」的关键**：正确结果必须在这一帧 draw 之前就写好。

**兜底重绘（保险，不是主修法）**
`scheduleLateSettle`（500ms + 1400ms 两级，覆盖返回动画结束）、
`scheduleScrollSettle`（160ms debounce，滚动结束后一次 strip + forcePageRedraw）、
`armSettleStrips`。全部是一次性 post，没有常驻定时器。

**日志**：`sStripLog` / `sBlockLog` 等计数是有配额的，用尽后 strip 仍在执行但不再打日志。
判断补扫有没有跑要看帧数 / 像素，不要只看 `blocks:` 行。这些计数刻意保留为独立 int
（热路径上只有一次比较，不引入 Map 查找）。

---

## 数据流小结

```
[模块进程] HonorSettingsUi.show → 用户调参
    → pr editor: put(key) + put(hb_cfg_version+1) → commit
    → 整个 Map 镜像进 LSPosed 远程偏好 "backgrounds"
[设置进程] Activity.onResume → HonorRenderer.apply
    → AppearanceState.read → GlassConfig.refresh（新快照 + dirty）
    → strip + forcePageRedraw（页面已存在时）
    → guardFrame(pre-draw) 每帧 compute + pin → RowGlass/SurfaceGlass.draw
    → GlassStyle 读 GlassConfig.get() 画 fill / sheen / refraction / stroke / divider
```

---

## 逐项参数验证结论（1.4.6-beta2）

所有可调项都以「写入 → 远程偏好 → 快照 → 绘制端」完整链路确认过，并用同进程截图 diff
逐项验证过实际像素变化。结论：

| 控件 | 生效 | 备注 |
|---|---|---|
| 玻璃效果总开关 | 是 | live-change 需 `HonorRenderer.removeGlass()` 主动卸载已装的 drawable |
| 玻璃不透明度 / 反射高光 / 折射光带 | 是 | 折射默认 0，关闭时完全跳过绘制分支 |
| 轮廓描边 / 描边强度 / 描边粗细 | 是 | 强度 0 与开关关闭效果相同，属设计冗余 |
| 显示分隔线 / 线宽 / 左右内缩 | 是 | 始终生效 |
| 自定义颜色 / 颜色 / 颜色不透明度 | 条件 | 仅「自定义颜色」打开时生效；关闭时跟随系统深浅色 |
| 圆角 / 左右边距 / 上下边距 | 是 | 影响玻璃几何，不影响分组判定 |
| 恢复默认 / 清除背景 | 是 | 改为显式覆写，规避 `remove()` 远程不同步 |

**分组判据常量刻意不参数化**（`GlassStyle.MIN_ROW_HEIGHT_DP` 等），暴露它们会破坏已验证的
beta25 分组行为。`RowGlass` 里 `DEFAULT_BORDER_DP` 只用于把横向接缝推出 bounds 再裁掉，
不是描边宽度，故不受「描边粗细」控制。

## 设置页的注入前提

`HonorSettingsUi` 运行在**模块自身进程**，因此 LSPosed 的 scope 表必须包含
`com.ciallo.hyperbackground` → `com.ciallo.hyperbackground` 这一行；`scope.list` 已包含它，
但设备侧 scope 表需要单独存在该行，且 LSPosed daemon 需重新加载（用户自行重启设备）后
才会注入模块进程。缺这一行时打开模块设置会看到原版 Compose UI，看不到任何新控件。
渲染侧（settings 进程）不依赖这一行，照常工作。

---

## 回滚到完美版

- 直接装机：`baseline/beta25-perfect/apk/20261110-222139-7EE78B16.apk`
  （SHA-256 `7EE78B16…04A697`）。
- 从源码重建：`git checkout beta25-perfect -- src/ build.ps1` 后运行 `build.ps1`。
- `baseline/beta25-perfect/SHA256SUMS.txt` 收录了全部 40 个基线文件的校验值。
