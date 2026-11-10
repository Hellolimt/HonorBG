# HonorBG 1.4.6 - build script (Windows PowerShell 5.1+)
#
# The upstream HyperBackground APK is only *augmented*: its UI, resources and
# classes.dex stay untouched. This script adds classes2.dex (the Honor rendering
# bridge) and rewrites META-INF/xposed/java_init.list + scope.list.
#
# Everything external is configurable through environment variables so the repo
# can be cloned anywhere. See docs/BUILD.md for the required toolchain layout.
#   HONORBG_BASE_APK  original HyperBackground-v1.4.5-beta2.apk
#   HONORBG_TOOLS     directory holding bt36/, platform-35/, libs/
#   HONORBG_JDK       JDK 21 home (or JAVA_HOME)
#   HONORBG_KS_FILE   signing keystore (default: <repo>\honorbg.jks, git-ignored)
#   HONORBG_KS_PASS   keystore/key password (default: local debug password)
#
# Every native tool (javac/d8/zipalign/apksigner) has an explicit $LASTEXITCODE
# check below, so 'Stop' is not needed; 'Stop' + native stderr (e.g. javac's
# benign deprecation note) aborts the whole build under Windows PowerShell 5.1.
$ErrorActionPreference = 'Continue'

$repo = $PSScriptRoot
$workRoot = Split-Path -Parent $repo
$tools = "$workRoot\tools"
if ($env:HONORBG_TOOLS) { $tools = $env:HONORBG_TOOLS }

$jdk = 'C:\Program Files\Java\jdk-21'
if ($env:HONORBG_JDK) { $jdk = $env:HONORBG_JDK }
elseif ($env:JAVA_HOME) { $jdk = $env:JAVA_HOME }
$env:JAVA_HOME = $jdk

$src = "$repo\base\HyperBackground-v1.4.5-beta2.apk"
if ($env:HONORBG_BASE_APK) { $src = $env:HONORBG_BASE_APK }
$out = "$repo\out"
New-Item -ItemType Directory -Force -Path $out | Out-Null
New-Item -ItemType Directory -Force -Path "$repo\build\classes" | Out-Null
New-Item -ItemType Directory -Force -Path "$repo\build\dex" | Out-Null

# A debug keystore is generated on demand and never committed. Its password is
# only a local convenience: the private key itself is git-ignored. Use your own
# keystore (HONORBG_KS_FILE / HONORBG_KS_PASS) for anything you distribute.
$ks = "$repo\honorbg.jks"
if ($env:HONORBG_KS_FILE) { $ks = $env:HONORBG_KS_FILE }
$ksPass = 'honorbg123'
if ($env:HONORBG_KS_PASS) { $ksPass = $env:HONORBG_KS_PASS }

# Source APK resolution. staging.apk / aligned.apk already contain the upstream
# APK plus an older classes2.dex and xposed metadata; since this script replaces
# exactly those entries, using them as the base is equivalent to a fresh APK.
$base = "$out\base.apk"
if (Test-Path $src) {
    Copy-Item $src $base -Force
} elseif (Test-Path "$out\staging.apk") {
    Copy-Item "$out\staging.apk" $base -Force
} elseif (Test-Path "$out\aligned.apk") {
    Copy-Item "$out\aligned.apk" $base -Force
} else {
    throw "source APK not found: $src (and no staging/aligned fallback in $out). See docs/BUILD.md"
}

$javac = "$jdk\bin\javac.exe"
$keytool = "$jdk\bin\keytool.exe"
$d8 = "$tools\bt36\android-16\d8.bat"
$zipalign = "$tools\bt36\android-16\zipalign.exe"
$apksigner = "$tools\bt36\android-16\apksigner.bat"
$androidJar = "$tools\platform-35\android-35\android.jar"
$apiJar = "$tools\libs\api-101.0.1-classes.jar"
foreach ($required in @($javac, $keytool, $d8, $zipalign, $apksigner, $androidJar, $apiJar)) {
    if (-not (Test-Path $required)) {
        throw "missing toolchain file: $required (set HONORBG_TOOLS / HONORBG_JDK, see docs/BUILD.md)"
    }
}

# ---- 1. compile + dex the Honor bridge -------------------------------------
# No compile-time stubs any more: libxposed loads the module dex in its own class loader, so
# androidx.recyclerview (shipped by com.android.settings) is not resolvable from module code.
# The glass blocks are painted by a plain android.graphics.drawable.Drawable instead.
Remove-Item -Recurse -Force "$repo\build\classes\*" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "$repo\build\dex\*" -ErrorAction SilentlyContinue
& $javac --release 11 -encoding UTF-8 -cp "$androidJar;$apiJar" -d "$repo\build\classes" (Get-ChildItem "$repo\src\com\honor\hb\*.java" | ForEach-Object { $_.FullName }) 2>$null
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

& $d8 --min-api 33 --release --lib $androidJar --classpath $apiJar --output "$repo\build\dex" (Get-ChildItem "$repo\build\classes\com\honor\hb\*.class" | ForEach-Object { $_.FullName }) 2>$null
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# ---- 2. assemble the APK ---------------------------------------------------
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$staging = "$out\staging.apk"
Copy-Item $base $staging -Force

$zip = [System.IO.Compression.ZipFile]::Open($staging, [System.IO.Compression.ZipArchiveMode]::Update)

# 2a. drop a possibly existing classes2 and add the new one
$old = $zip.GetEntry('classes2.dex')
if ($old) { $old.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip, "$repo\build\dex\classes.dex", 'classes2.dex',
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null

# 2b. java_init.list: Honor-only entry. The two original entries hook MIUI/HyperOS classes that
#     do not exist on MagicOS; keeping them only adds startup work and failure noise.
$newInit = @(
    'com.honor.hb.HonorHookEntry'
) -join "`n"
$newInit = $newInit + "`n"
$entry = $zip.GetEntry('META-INF/xposed/java_init.list')
if ($entry) { $entry.Delete() }
$e = $zip.CreateEntry('META-INF/xposed/java_init.list', [System.IO.Compression.CompressionLevel]::Optimal)
$stream = $e.Open()
$bytes = [System.Text.Encoding]::UTF8.GetBytes($newInit)
$stream.Write($bytes, 0, $bytes.Length)
$stream.Close()

# 2c. scope.list: drop the 26 HyperOS/MIUI packages; the port only serves Honor's Settings
#     (plus the module package itself, which hosts the trimmed settings UI).
#     Every package here must also be listed in HonorHookEntry.TARGET_PACKAGES, and both must
#     be selected in LSPosed - the two lists are separate switches. A package missing from the
#     LSPosed scope is silently never injected, which is what made 8 of the second-level menus
#     look unstyled while their rendering code was fine.
$newScope = "com.android.settings`ncom.hihonor.systemmanager`ncom.hihonor.controlcenter`ncom.hihonor.assistant`ncom.hihonor.id`ncom.hihonor.wallet`ncom.hihonor.parentcontrol`ncom.ciallo.hyperbackground`n"
$scopeEntry = $zip.GetEntry('META-INF/xposed/scope.list')
if ($scopeEntry) { $scopeEntry.Delete() }
$se = $zip.CreateEntry('META-INF/xposed/scope.list', [System.IO.Compression.CompressionLevel]::Optimal)
$sstream = $se.Open()
$sbytes = [System.Text.Encoding]::UTF8.GetBytes($newScope)
$sstream.Write($sbytes, 0, $sbytes.Length)
$sstream.Close()
$zip.Dispose()

# ---- 3. align + sign -------------------------------------------------------
$aligned = "$out\aligned.apk"
Remove-Item $aligned -ErrorAction SilentlyContinue
& $zipalign -f 4 $staging $aligned 2>$null
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

if (-not (Test-Path $ks)) {
    Start-Process -FilePath $keytool -Wait -NoNewWindow `
        -ArgumentList @('-genkeypair', '-keystore', $ks, '-alias', 'honorbg', '-keyalg', 'RSA',
                        '-keysize', '2048', '-validity', '10000',
                        '-storepass', $ksPass, '-keypass', $ksPass,
                        '-dname', 'CN=HonorBG,OU=honor,O=honor,L=magicos') `
        -RedirectStandardOutput "$out\keytool.out" -RedirectStandardError "$out\keytool.err"
}

$signed = "$out\HonorBG-1.4.6.apk"
Remove-Item $signed -ErrorAction SilentlyContinue
& $apksigner sign --ks $ks --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true `
    --out $signed $aligned 2>$null
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& $apksigner verify --print-certs $signed 2>$null | Select-Object -First 8
"OUTPUT: $signed"

# ---- 4. archive the artifact ------------------------------------------------
# Keep every successful build under out\archive so the only installable APK can
# never be lost to a later build overwriting out\*.apk. The file name carries a
# second-resolution timestamp plus the first 8 hex digits of the SHA256, and an
# existing archive file is never overwritten (a numeric suffix is added instead).
$archiveDir = "$out\archive"
if (-not (Test-Path $archiveDir)) {
    New-Item -ItemType Directory -Force -Path $archiveDir | Out-Null
}
$sha = (Get-FileHash $signed -Algorithm SHA256).Hash
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$archive = "$archiveDir\$stamp-$($sha.Substring(0, 8)).apk"
$n = 1
while (Test-Path $archive) {
    $archive = "$archiveDir\$stamp-$($sha.Substring(0, 8))-$n.apk"
    $n++
}
Copy-Item $signed $archive
"ARCHIVE: $archive"
Get-Item $signed | Format-List Name,Length
