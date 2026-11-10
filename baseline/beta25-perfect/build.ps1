# Builds the Honor (MagicOS) port of HyperBackground 1.4.5-beta2.
# The original APK is only *augmented*: its UI, resources and classes.dex stay untouched,
# we add classes2.dex (the Honor rendering bridge) and extend META-INF/xposed/java_init.list.
# Every native tool (javac/d8/zipalign/apksigner) has an explicit $LASTEXITCODE check
# below, so 'Stop' is not needed; 'Stop' + native stderr (e.g. javac's benign deprecation
# note) aborts the whole build under Windows PowerShell 5.1.
$ErrorActionPreference = 'Continue'
$root = "$env:USERPROFILE\harness\hb-work"
$tools = "$root\tools"
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$jdk = 'C:\Program Files\Java\jdk-21'
$src = "$env:USERPROFILE\Desktop\HyperBackground-v1.4.5-beta2.apk"
$out = "$root\port\out"
New-Item -ItemType Directory -Force -Path $out | Out-Null

# Source APK resolution: the original HyperBackground build is no longer on the
# Desktop, so fall back to a previous build artifact. staging.apk / aligned.apk are
# the source APK plus the old classes2.dex and xposed metadata; the build rewrites
# classes2.dex and META-INF/xposed below, so using them as the base is equivalent.
$base = "$out\base.apk"
if (Test-Path $src) {
    Copy-Item $src $base -Force
} elseif (Test-Path "$out\staging.apk") {
    Copy-Item "$out\staging.apk" $base -Force
} elseif (Test-Path "$out\aligned.apk") {
    Copy-Item "$out\aligned.apk" $base -Force
} else {
    throw "source APK not found: $src (and no staging/aligned fallback in $out)"
}

$javac = "$jdk\bin\javac.exe"
$keytool = "$jdk\bin\keytool.exe"
$d8 = "$tools\bt36\android-16\d8.bat"
$zipalign = "$tools\bt36\android-16\zipalign.exe"
$apksigner = "$tools\bt36\android-16\apksigner.bat"
$androidJar = "$tools\platform-35\android-35\android.jar"
$apiJar = "$tools\libs\api-101.0.1-classes.jar"

# ---- 1. compile + dex the Honor bridge -------------------------------------
# No compile-time stubs any more: libxposed loads the module dex in its own class loader, so
# androidx.recyclerview (shipped by com.android.settings) is not resolvable from module code.
# The glass blocks are painted by a plain android.graphics.drawable.Drawable instead.
Remove-Item -Recurse -Force "$root\port\build\classes\*" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "$root\port\build\dex\*" -ErrorAction SilentlyContinue
& $javac --release 11 -encoding UTF-8 -cp "$androidJar;$apiJar" -d "$root\port\build\classes" (Get-ChildItem "$root\port\src\com\honor\hb\*.java" | ForEach-Object { $_.FullName }) 2>$null
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

& $d8 --min-api 33 --release --lib $androidJar --classpath $apiJar --output "$root\port\build\dex" (Get-ChildItem "$root\port\build\classes\com\honor\hb\*.class" | ForEach-Object { $_.FullName }) 2>$null
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
    $zip, "$root\port\build\dex\classes.dex", 'classes2.dex',
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

$ks = "$root\port\honorbg.jks"
if (-not (Test-Path $ks)) {
    Start-Process -FilePath $keytool -Wait -NoNewWindow `
        -ArgumentList @('-genkeypair', '-keystore', $ks, '-alias', 'honorbg', '-keyalg', 'RSA',
                        '-keysize', '2048', '-validity', '10000',
                        '-storepass', 'honorbg123', '-keypass', 'honorbg123',
                        '-dname', 'CN=HonorBackgroundPort,OU=honor,O=honor,L=magicos') `
        -RedirectStandardOutput "$out\keytool.out" -RedirectStandardError "$out\keytool.err"
}

$signed = "$out\HyperBackground-Honor-1.4.5-beta2.apk"
Remove-Item $signed -ErrorAction SilentlyContinue
& $apksigner sign --ks $ks --ks-pass pass:honorbg123 --key-pass pass:honorbg123 `
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true `
    --out $signed $aligned 2>$null
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& $apksigner verify --print-certs $signed 2>$null | Select-Object -First 8
"OUTPUT: $signed"

# ---- 6. archive the artifact ------------------------------------------------
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
