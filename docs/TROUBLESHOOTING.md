# 排查与已知问题

## 1. 设置页完全没有玻璃效果 / 大片白卡

**先看 LSPosed 作用域**。`META-INF/xposed/scope.list` 只是建议清单，真正生效的是 LSPosed 里勾选的 scope 表。缺包时模块**静默不注入**，日志里也不会报错。

必须勾选 8 个：

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

`scope.list` 与 `HonorHookEntry.TARGET_PACKAGES` 是两道独立的开关，两处都要有。

## 2. 点模块图标还是上游原版 Compose UI

最后一行 `com.ciallo.hyperbackground`（**模块自身**）没勾。精简设置页运行在模块进程里，只有模块自身被注入才会接管 `MainActivity`。

首次改动 scope 表后需要**重启一次设备**让 LSPosed daemon 重新加载；仅重开设置不会生效。

## 3. 改了参数但画面没变

正式链路必须走模块设置页的 `pr` editor：

- LSPosed daemon 把远程偏好缓存在内存里；
- 直接改模块本地 `/data/data/.../shared_prefs/backgrounds.xml` **不生效**；
- 直接离线改 `/data/adb/lspd/config/modules_config.db` 的 `module_configs` 也**不会立刻生效**。

排查时可用 LSPosed 模块日志的 `appearance: enabled ...` 快照确认实际读到的值。改完参数切回系统「设置」（触发 onResume 重读）即可，不需要重启设备。

## 4. 整屏像蒙了一层透明黑

不是夜间模式，也不是护眼模式。检查「背景」卡的**背景不透明度**，它被调低了（例如 20%），默认应为 **100%（设置页已标注）**。

该值同时作用于壁纸 ImageView 和窗口 `WallpaperDrawable`，有效浓度为 `1-(1-a)²`；20% 时只剩约 36% 壁纸，剩下是主题黑底，看起来就是一层暗色。想调玻璃通透度请用「液态玻璃」卡的「玻璃不透明度」。

系统侧自检（都不是原因）：

```bash
adb shell cmd uimode night
adb shell dumpsys color_display
adb shell settings get system eyes_protection_mode
```

## 5. 静止时整块被拆成独立卡片 / 白块残留

根因是窗口零重绘 + 最后一次 draw 发生在布局稳定之前，错误像素被冻结，滑动触发重绘才恢复。

beta23 起用 `forcePageRedraw()` + 延迟 settle 作为保险，beta25 起把整表分组计算挪到 `OnPreDrawListener.guardFrame()`，在每帧 draw **之前**统一固化，已消除该类问题。若仍遇到，请记录页面、滚动方式与出现时机，并附 LSPosed 日志反馈。

## 6. 慢速下滑时「先分裂、闪一下合并」

重绘延迟问题。`RowGlass.draw()` 曾按瞬时父容器子 View 顺序判断首/末行，滚动中顺序不稳定导致错一帧。beta24 的 `pinBlock()` + beta25 的 pre-draw guard 已修复；`pinBlock()` 里 1.5px 死区避免取整抖动引发每帧自刷新。

## 7. 关闭玻璃总开关后玻璃仍在

`strip()` / `clearRow()` 会跳过 `RowGlass` / `SurfaceGlass`，已安装的 Drawable 不会自动卸载。1.4.6 起在实时配置变化且总开关关闭时调用 `HonorRenderer.removeGlass()` 递归卸载并强制重绘，已验证生效。

## 8. 恢复默认后外观不对

- 1.4.6 的「恢复默认」是显式覆写 `DEF_*`，不是删键；
- 若曾手动往偏好里写过早期的 `honor_glass_alpha=0` 等实验键，请清理——1.4.6 已换用 `hb_` 命名空间隔离，正常情况下不会再受影响。

## 9. 构建失败

见 [`BUILD.md`](BUILD.md#7-常见构建错误)。

## 回滚到完美版

`baseline/beta25-perfect/` 保存用户实测确认的完美版源码（8 个 `.java`）、当时的 `build.ps1` 与 `SHA256SUMS.txt`。把该目录的 `src/` 覆盖回仓库 `src/` 并重新构建即可回到未经参数化的原始外观。对应 APK 的 SHA-256：

```
7EE78B163CD003F3CDFA23A0AFF6777B9760981C701951DE5EFD13910401A697
```

> 回滚会丢失 1.4.6 的可调参数体系；建议先保留当前构建产物（`out/archive/` 里的归档 APK）再操作。
