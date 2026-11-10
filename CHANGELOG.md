# CHANGELOG

完整修复时间线（含历次构建 SHA-256）见 [`docs/CHANGELOG-full.md`](docs/CHANGELOG-full.md)。

## 1.4.6 — 当前版本（2026-11-10）

**项目化与展示名**
- 项目定名 **HonorBG**，作者 ZOQOO / GitHub `Hellolimt` 写入「关于」卡，并提供项目主页入口。
- 设置页「背景不透明度」在 100% 处标注「默认」，并在关于卡说明其控制的是壁纸而非玻璃。
- 构建产物改名 `HonorBG-1.4.6.apk`；`build.ps1` 改为路径相对仓库、支持环境变量覆盖，便于任意目录克隆构建。
- 补齐 README / LICENSE / BUILD / CONFIG / TROUBLESHOOTING / 基线源码，整理为可独立发布的完整仓库。

**参数体系（默认外观严格等于 beta25）**
- 新增 `GlassConfig`（参数唯一来源、不可变快照、版本与脏标记）、`Prefs`（远程偏好统一读取器）。
- `GlassStyle` 成为绘制中心；`RowGlass` / `SurfaceGlass` / `HonorRenderer` 全部改为读取共享配置。
- 设置页重写为 5 张卡 / 16 项控件，控件构造收敛为公共方法；每次编辑写入目标键并递增 `hb_cfg_version`。
- 全部玻璃参数使用 `hb_` 前缀，与早期的 `honor_glass_*` 实验键隔离，避免历史残留污染默认值。

**逐项验证与修复**
- 同进程截图 diff 逐项确认填充、反射、折射、描边开关/强度/粗细、分隔线开关/颜色/透明度/线宽/内缩、圆角、左右/上下边距均实际改变像素。
- 总开关最初在实时切换时不生效：已安装的玻璃 Drawable 不会被 `strip()` 卸载。新增 `HonorRenderer.removeGlass()` 递归卸载后验证生效（关→玻璃消失，开→恢复）。
- 「恢复默认」「清除背景」改为显式覆写默认值，不再依赖 `Editor.remove()` 跨远程偏好镜像删键。
- 版本号读写统一为 `int`，修正 `getLong` 导致日志恒为 `v=0` 的问题。
- 移除仅用于验证的文件驱动钩子，最终 dex 中不含任何调试入口。

**基线冻结**
- `baseline/beta25-perfect/` 保存用户实测确认的完美版源码与 `SHA256SUMS.txt`，可随时回滚。

## 1.4.5-beta25 — 完美版基线（2026-11-10）

- 把整表分组计算与逐行 `pinBlock()` 挪到 `OnPreDrawListener.guardFrame()`：每帧 draw **之前**完成分组固化，消除「先分裂、闪一下才合并」的重绘延迟。
- `pinBlock()` 加入 1.5px 死区，避免滚动时 1px 取整抖动引发每帧 `invalidateSelf()` 与 pre-draw 互激。
- 用户实测评价为历次版本中最好的一个，冻结为基线。

## 1.4.5-beta24 / beta23

- beta24：`pinBlock()` 固化整表分组结果，取代 `draw()` 里按瞬时父容器子 View 顺序判断首/末行；加入 160ms 滚动 settle debounce。
- beta23：`forcePageRedraw()`、延迟 settle、多处 `invalidate()`，修复静止画面「整块被拆成独立卡片」。

## 1.4.5-beta22 及更早

- 12 个二级菜单统一玻璃风格、圆角丢失修复、顶栏白块、返回时块分裂、通知页应用开关成块、荣耀互联首帧白块、LSPosed scope 补全等。详见完整时间线。
