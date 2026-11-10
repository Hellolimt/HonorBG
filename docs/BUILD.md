# 构建指南

`build.ps1` 会把本仓库的桥接源码编译成 `classes2.dex`，写进**你自备的上游 HyperBackground APK**，然后对齐、签名、归档。上游 APK 的 `classes.dex`、资源与界面保持原样。

## 1. 准备工具链

Windows + PowerShell 5.1+，需要：

| 工具 | 版本 | 用途 |
|---|---|---|
| JDK | 21 | `javac` / `keytool` |
| Android build-tools | r36（`bt36\android-16`） | `d8.bat` / `zipalign.exe` / `apksigner.bat` |
| Android SDK platform | android-35 | `android.jar`（编译用） |
| libxposed API | 101 | `api-101.0.1-classes.jar` |

推荐的目录布局（脚本默认按此查找）：

```
<work>/
├── HonorBG/                          ← 本仓库
└── tools/
    ├── bt36/android-16/
    │   ├── d8.bat
    │   ├── zipalign.exe
    │   └── apksigner.bat
    ├── platform-35/android-35/android.jar
    └── libs/api-101.0.1-classes.jar
```

`$tools` 默认是仓库的**同级** `tools/` 目录。如果你的工具链在别处，用 `HONORBG_TOOLS` 指定绝对路径。

## 2. 环境变量（全部可选）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `HONORBG_BASE_APK` | `<repo>\base\HyperBackground-v1.4.5-beta2.apk` | 上游原版 APK 路径 |
| `HONORBG_TOOLS` | `<repo 同级>\tools` | 工具链根目录 |
| `HONORBG_JDK` | `JAVA_HOME` 或 `C:\Program Files\Java\jdk-21` | JDK 21 根目录 |
| `HONORBG_KS_FILE` | `<repo>\honorbg.jks` | 签名 keystore（**git-ignored**） |
| `HONORBG_KS_PASS` | `honorbg123` | 调试密钥口令 |

> **本仓库不分发上游 APK，也不分发私钥。** 上游 APK 需自行准备；keystore 在首次构建时由 `keytool` 自动生成，仅用于本地调试。对外发布请自备密钥并通过 `HONORBG_KS_FILE` / `HONORBG_KS_PASS` 指定。

## 3. 准备基底 APK

按优先级解析：

1. `HONORBG_BASE_APK` 指向的文件
2. `<repo>\base\HyperBackground-v1.4.5-beta2.apk`
3. `<repo>\out\staging.apk`
4. `<repo>\out\aligned.apk`

后两项是先前构建的产物，已经包含上游 APK + 旧 `classes2.dex` + Xposed 元数据。由于构建过程会完整替换这三个条目，用它们当基底与全新 APK 等价，适合迭代开发。

## 4. 构建

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

脚本依次执行：

1. **编译** `javac --release 11 -encoding UTF-8 -cp android.jar;api.jar -d build\classes`，只编 `src\com\honor\hb\*.java`
2. **Dex 化** `d8 --min-api 33 --release --lib android.jar --classpath api.jar --output build\dex`
3. **注入** 复制基底为 `out\staging.apk`，以 Zip Update 模式：
   - 删除旧 `classes2.dex`，写入新编译的 `classes.dex`
   - 重写 `META-INF/xposed/java_init.list` 为 `com.honor.hb.HonorHookEntry`
   - 重写 `META-INF/xposed/scope.list` 为 8 个荣耀/模块包名（删除上游的 26 个 HyperOS/MIUI 条目）
4. **对齐** `zipalign -f 4`
5. **签名** `apksigner sign --v1 false --v2 true --v3 true`
6. **归档** 复制一份到 `out\archive\yyyyMMdd-HHmmss-<SHA256前8位>.apk`，永不覆盖

每个原生工具调用后都显式检查 `$LASTEXITCODE`，失败立即抛错。

## 5. 输出与校验

```
out/HonorBG-1.4.6.apk                        ← 当前可安装产物
out/archive/20261110-235939-E3F056C1.apk    ← 带时间戳与哈希的归档
```

```powershell
Get-FileHash out\HonorBG-1.4.6.apk -Algorithm SHA256
& "$tools\bt36\android-16\apksigner.bat" verify --print-certs out\HonorBG-1.4.6.apk
```

校验 APK 内容：

```python
import zipfile
z = zipfile.ZipFile('out/HonorBG-1.4.6.apk')
print(z.read('META-INF/xposed/java_init.list').decode())
print(z.read('META-INF/xposed/scope.list').decode())
print('classes2.dex bytes:', len(z.read('classes2.dex')))
```

## 6. 安装

```bash
adb install -r -g out/HonorBG-1.4.6.apk
```

> 覆盖安装要求签名一致。换了 keystore 就必须先卸载旧模块（会丢失壁纸与参数），请先备份。

随后在 LSPosed 里确认 scope 的 8 个包都已勾选，首次改动 scope 需重启设备。详见 [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md)。

## 7. 常见构建错误

| 报错 | 原因 / 处理 |
|---|---|
| `source APK not found … See docs/BUILD.md` | 三个基底路径都不存在；放置上游 APK 或设置 `HONORBG_BASE_APK` |
| `missing toolchain file: …` | 工具链路径不对；设置 `HONORBG_TOOLS` / `HONORBG_JDK` |
| `javac failed` | JDK 版本不对或 `api-101.0.1-classes.jar` 缺失；去掉 `2>$null` 看完整输出 |
| `d8 failed` | build-tools 版本与 `--min-api 33` 不匹配；使用 r36 |
| `apksigner failed` | keystore 与口令不匹配；删除本地 `honorbg.jks` 让它重新生成（注意会改变签名） |
| PowerShell 里构建莫名中止 | 不要用 `$ErrorActionPreference='Stop'`，`javac` 的无害 deprecation stderr 会被当成失败 |
