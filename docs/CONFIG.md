# 参数与配置链路

全部玻璃参数集中在 `GlassConfig.java`，设置页只负责写键，绘制端只读快照。默认值严格等于 beta25 完美版——**不调任何滑杆，画面就不变**。

## 参数表

所有键使用 `hb_` 前缀，保存在 LSPosed 远程偏好组 `backgrounds`。小数用 ×10 整数存储（例如 `180` = 18dp），便于跨进程 Map 与 `SeekBar` 直连。

| 分组 | 控件 | key | 范围 | 默认 | 绘制端 |
|---|---|---|---|---|---|
| 总开关 | 玻璃效果总开关 | `hb_glass_enabled` | bool | `true` | `HonorRenderer.glassEnabled()` |
| 玻璃 | 玻璃不透明度 | `hb_fill_alpha` | 0–255（UI 0–100%） | `153`（60%） | `RowGlass` / `SurfaceGlass` 填充 |
| 玻璃 | 反射 / 高光 | `hb_sheen` | 0–80（UI 0–100%） | `40` | `GlassStyle.drawBlockFill` |
| 玻璃 | 折射光带 | `hb_refraction` | 0–100 % | `0`（关） | `GlassStyle.drawBlockFill` 渐变光带 |
| 描边 | 轮廓描边 | `hb_stroke_enabled` | bool | `true` | `GlassStyle.drawBlockStroke` |
| 描边 | 描边强度 | `hb_stroke_alpha` | 0–100 % | `100` | 同上 |
| 描边 | 描边粗细 | `hb_stroke_width_x10` | 0.5–3dp | `10`（1dp） | 同上 |
| 分隔线 | 显示分隔线 | `hb_divider_enabled` | bool | `true` | `GlassStyle.drawDivider` |
| 分隔线 | 自定义颜色 | `hb_divider_custom` | bool | `false` | 同上；关时跟随系统深浅色 |
| 分隔线 | 颜色 | `hb_divider_color` | `#RRGGBB` | `0xFFFFFF` | 同上 |
| 分隔线 | 颜色不透明度 | `hb_divider_alpha` | 0–100 % | `20` | 同上（**仅自定义颜色开启时生效**） |
| 分隔线 | 线宽 | `hb_divider_width_x10` | 0.2–4px | `10`（1px） | 同上 |
| 分隔线 | 左右内缩 | `hb_divider_inset_x10` | 0–32dp | `160`（16dp） | `GlassStyle.dividerInsetDp()` |
| 几何 | 圆角半径 | `hb_corner_x10` | 0–30dp | `180`（18dp） | `GlassStyle.cornerRadiusDp()` |
| 几何 | 左右边距 | `hb_h_inset_x10` | 0–28dp | `140`（14dp） | `GlassStyle.hInsetDp()` |
| 几何 | 上下边距 | `hb_v_inset_x10` | 0–12dp | `40`（4dp） | `GlassStyle.vInsetDp()` |
| 版本 | （内部） | `hb_cfg_version` | int，每次编辑 +1 | `0` | 触发整页重绘 |

> 描边强度调为 `0` 与关闭「轮廓描边」视觉效果相同，属于设计上的冗余，不是失效项。
>
> 几何分组的**判定常量**（行宽/高/间距阈值）没有参数化：它决定「哪些行属于同一块」，参数化会破坏已验证的分组结果。可调的只是圆角、边距等**绘制**数值。

## 背景参数（不属于玻璃配置）

| 控件 | key | 默认 | 说明 |
|---|---|---|---|
| 启用背景 | `honor_home_enabled` | `true` | 是否在设置页显示壁纸层 |
| 背景不透明度 | `opacity_home` | `100`（默认） | 控制**壁纸本身**，不是玻璃 |
| 背景图片 | `mime_home` / `size_home` / `modified_home` | — | 选图后写入模块私有 `backgrounds/home.bin` |

> ⚠ **背景不透明度会平方衰减**：`buildLayer()` 给壁纸 ImageView 设 alpha，`setWindowWallpaper()` 又给窗口 `WallpaperDrawable` 设同一个 alpha，两层叠在主题黑底上，有效浓度约为 `1-(1-a)²`。滑到 20% 时壁纸只剩 36%，看起来像整屏蒙了透明黑。**保持 100%**。

## 写入链路

```
模块进程 HonorSettingsUi
  └─ editor(sp): put(目标键) + put(hb_cfg_version, +1)
       └─ commit() → 上游混淆类 pr 把整张 backgrounds Map 镜像到 LSPosed 远程偏好
            └─ LSPosed daemon 的 module_configs(backgrounds 组)
                 └─ 设置进程 XposedModule.getRemotePreferences("backgrounds")
                      └─ AppearanceState.read() → GlassConfig.refresh() → 不可变快照
                           └─ HonorRenderer.apply() 检测版本变化 → strip + 重绘
```

- `Prefs.java` 统一了 `bool()` / `str()` / `num()` / `lng()` 读取，兼容远程偏好把数字存成 `String` 的情况。
- `GlassConfig.consumeDirty()` 让页面在 onResume 时知道配置变过，从而整页重绘。
- 总开关关闭时需要 `HonorRenderer.removeGlass()` 递归卸载**已经装上的** `RowGlass` / `SurfaceGlass`，否则 `strip()` 会跳过它们、玻璃不会消失。

## 恢复默认

`GlassConfig.writeDefaults(editor)` 把 `DEF_*` 常量**显式覆写**进每一个键，并递增版本号，然后重建页面。

这里刻意不用 `Editor.remove()`：远程偏好镜像的是模块本地整张 Map，`remove()` 不保证远端对应键也消失。历史实验残留的 `honor_glass_alpha=0` 曾让回退到 beta25 时玻璃全透明，改成 `hb_` 命名空间 + 显式覆写后不再受影响。

## 快照日志

LSPosed 模块日志里可以直接确认当前生效参数：

```
hb build 1.4.6-beta3
appearance: enabled ... glass=true fill=153 sheen=40 refr=0
stroke=true/100/10 div=true/false/#ffffff/20/10 geo=180/140/40 v=0
```
