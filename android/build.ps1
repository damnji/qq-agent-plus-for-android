# 构建 QQ Agent 安卓 APK（不依赖 Gradle / Android Studio）
#
# 为什么手写构建脚本：本机没有 Gradle，也没有 Flutter SDK。
# aapt2 + javac + d8 + apksigner 这条链完全够用，而且构建过程透明可查。
#
# 用法：
#   .\build.ps1                 # 构建 release 签名 APK
#   .\build.ps1 -Debug          # 构建 debug（用 debug keystore）
#   .\build.ps1 -SkipAssets     # 跳过 assets 检查（assets 已在时加速迭代）
param(
    [switch]$Debug,
    [switch]$SkipAssets
)

$ErrorActionPreference = 'Stop'
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Write-TextNoBom([string]$path, [string]$text) {
    $dir = Split-Path $path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [System.IO.File]::WriteAllText($path, $text, $Utf8NoBom)
}
# 跑原生命令：临时把 $ErrorActionPreference 降级，避免 stderr 输出被当成异常抛出
function Invoke-Native {
    param([scriptblock]$Block)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & $Block 2>&1
        $code = $LASTEXITCODE
        return @{ Code = $code; Output = $out }
    } finally {
        $ErrorActionPreference = $prev
    }
}
function Step([string]$msg) { Write-Host ""; Write-Host "=== $msg ===" -ForegroundColor Cyan }
function Fail([string]$msg) { Write-Host "失败: $msg" -ForegroundColor Red; exit 1 }

$Root = $PSScriptRoot
$AppDir = Join-Path $Root 'app'
$Sdk = "$env:LOCALAPPDATA\Android\Sdk"
$BT = Join-Path $Sdk 'build-tools\36.0.0'
$Platform = Join-Path $Sdk 'platforms\android-36.1\android.jar'
$Build = Join-Path $Root 'build'
$Gen = Join-Path $Build 'gen'
$Classes = Join-Path $Build 'classes'
$DexOut = Join-Path $Build 'dex'
$OutDir = Join-Path $Root 'dist'

# ── 前置检查 ──
Step '0. 检查工具链'
foreach ($t in @("$BT\aapt2.exe", "$BT\d8.bat", "$BT\apksigner.bat", "$BT\zipalign.exe", $Platform)) {
    if (-not (Test-Path $t)) { Fail "缺少 $t" }
}
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) { Fail '缺少 javac（需要 JDK 11+）' }
if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) { Fail '缺少 keytool' }
Write-Host "  SDK      : $Sdk"
Write-Host "  android  : $Platform"

# ── assets 检查 ──
$Assets = Join-Path $AppDir 'assets'
$requiredAssets = @('rootfs.tar.gz', 'node-arm64.tar.gz', 'app.tar.gz', 'node_modules.tar.gz')
if (-not $SkipAssets) {
    Step '1. 检查 assets'
    foreach ($a in $requiredAssets) {
        $p = Join-Path $Assets $a
        if (-not (Test-Path $p)) {
            Fail "缺少 assets/$a`n请先运行 build-assets.sh（需要 WSL）生成这四个文件。"
        }
        $mb = [math]::Round((Get-Item $p).Length / 1MB, 1)
        Write-Host ("  {0,-24} {1,6} MB" -f $a, $mb)
    }
} else {
    Write-Host '  跳过 assets 检查'
}

# ── 清理 ──
Step '2. 清理构建目录'
foreach ($d in @($Gen, $Classes, $DexOut)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }
Write-Host '  已清理'

# ── 编译资源 + 生成 R.java ──
Step '3. aapt2 编译资源'
$resDir = Join-Path $AppDir 'res'
$manifest = Join-Path $AppDir 'AndroidManifest.xml'
$compiledRes = Join-Path $Build 'compiled-res'
if (Test-Path $compiledRes) { Remove-Item $compiledRes -Recurse -Force }
New-Item -ItemType Directory -Force -Path $compiledRes | Out-Null

# 资源很多，用 --dir 一次编译整个目录
& "$BT\aapt2.exe" compile --dir $resDir -o $compiledRes
if ($LASTEXITCODE -ne 0) { Fail 'aapt2 compile 失败' }
$flatCount = @(Get-ChildItem $compiledRes -Filter *.flat).Count
Write-Host "  编译了 $flatCount 个资源"

Step '4. aapt2 链接（生成 base APK + R.java）'
$baseApk = Join-Path $Build 'base-unsigned.apk'
$flatFiles = @(Get-ChildItem $compiledRes -Filter *.flat | ForEach-Object { $_.FullName })
& "$BT\aapt2.exe" link `
    -o $baseApk `
    -I $Platform `
    --manifest $manifest `
    --java $Gen `
    -A $Assets `
    --min-sdk-version 24 `
    --target-sdk-version 35 `
    --version-code 1 `
    --version-name '1.0.0' `
    --auto-add-overlay `
    $flatFiles
if ($LASTEXITCODE -ne 0) { Fail 'aapt2 link 失败' }
Write-Host "  base APK: $([math]::Round((Get-Item $baseApk).Length/1MB,1)) MB"

# ── 编译 Java ──
Step '5. javac 编译 Java 源码'
$srcDirs = @(Get-ChildItem (Join-Path $AppDir 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$rJava = @(Get-ChildItem $Gen -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Host "  源码 $($srcDirs.Count) 个，生成的 R.java $($rJava.Count) 个"
$allJava = $srcDirs + $rJava
# JDK 21 不再接受 `-source 11 -bootclasspath`（要求系统模块路径）；
# `--release 11 -classpath <android.jar>` 是等价且能用的写法。
# 注意：javac 的"过时 API"提示写到 stderr，PowerShell 会把它当错误记录，
# 所以这里不看 $LASTEXITCODE 之前先吃掉 stderr，改用产物是否生成来判定。
# 原生命令把提示写到 stderr 时，$ErrorActionPreference='Stop' 会抛 NativeCommandError。
# 临时降级为 Continue，跑完再恢复。
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javacOut = & javac --release 11 -classpath $Platform `
    -Xlint:-options -nowarn -encoding UTF-8 -d $Classes @allJava 2>&1
$javacExit = $LASTEXITCODE
$ErrorActionPreference = $prevEap
if ($javacExit -ne 0) {
    $javacOut | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Fail "javac 编译失败 (exit=$javacExit)"
}
$classCount = @(Get-ChildItem $Classes -Recurse -Filter *.class).Count
Write-Host "  生成了 $classCount 个 class"

# ── d8 转 dex ──
Step '6. d8 生成 classes.dex'
$classFiles = @(Get-ChildItem $Classes -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& "$BT\d8.bat" --lib $Platform --min-api 24 --output $DexOut @classFiles
if ($LASTEXITCODE -ne 0) { Fail 'd8 失败' }
$dex = Join-Path $DexOut 'classes.dex'
if (-not (Test-Path $dex)) { Fail 'd8 没有产出 classes.dex' }
Write-Host "  classes.dex: $([math]::Round((Get-Item $dex).Length/1KB,1)) KB"

# ── 组装 APK ──
Step '7. 组装 APK'
$unsigned = Join-Path $Build 'app-unsigned.apk'
Copy-Item $baseApk $unsigned -Force
Push-Location $DexOut
& "$BT\aapt.exe" add $unsigned classes.dex
$rc = $LASTEXITCODE
Pop-Location
if ($rc -ne 0) { Fail 'aapt add classes.dex 失败' }

# ── 把 jniLibs 里的 arm64 可执行文件打进 lib/arm64-v8a/ ──
# 这一步不能省：proot/loader/bash/busybox 全靠它，缺了 App 起来就会报
# 「proot 二进制准备失败」。aapt2 的 -A 只管 assets，native lib 要手工 add。
$jniDir = Join-Path $AppDir 'jniLibs\arm64-v8a'
if (-not (Test-Path $jniDir)) { Fail "缺少 jniLibs: $jniDir" }
$nativeLibs = @(Get-ChildItem $jniDir -File)
if ($nativeLibs.Count -eq 0) { Fail 'jniLibs 里没有文件' }
Write-Host "  打包 $($nativeLibs.Count) 个 native 库到 lib/arm64-v8a/"
$stage = Join-Path $Build 'native-stage'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'lib\arm64-v8a') | Out-Null
foreach ($f in $nativeLibs) {
    Copy-Item $f.FullName (Join-Path $stage ('lib\arm64-v8a\' + $f.Name)) -Force
}
# aapt add 只接受具体文件路径（传目录会报 Unable to add）
Push-Location $stage
$libFiles = @(Get-ChildItem 'lib\arm64-v8a' -File | ForEach-Object { 'lib/arm64-v8a/' + $_.Name })
& "$BT\aapt.exe" add $unsigned @libFiles
$rc2 = $LASTEXITCODE
Pop-Location
if ($rc2 -ne 0) { Fail 'aapt add native libs 失败' }

Write-Host "  未签名 APK: $([math]::Round((Get-Item $unsigned).Length/1MB,1)) MB"

# ── keystore ──
Step '8. 准备签名密钥'
$ksDir = Join-Path $Root 'keystore'
if (-not (Test-Path $ksDir)) { New-Item -ItemType Directory -Force -Path $ksDir | Out-Null }
$ks = Join-Path $ksDir 'qqagent.jks'
if (-not (Test-Path $ks)) {
    Write-Host '  生成新的签名密钥（请妥善保管，升级 APK 必须用同一个）'
    $r = Invoke-Native { keytool -genkeypair -v `
        -keystore $ks `
        -storepass qqagent2026 -keypass qqagent2026 `
        -alias qqagent -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=QQ Agent, OU=Mobile, O=QQAgent, L=CN, ST=CN, C=CN' }
    if ($r.Code -ne 0) {
        $r.Output | ForEach-Object { Write-Host "  $_" }
        Fail "keytool 生成密钥失败 (exit=$($r.Code))"
    }
    Write-Host "  已生成: $ks"
    Write-Host '  口令: qqagent2026（请自己记下来）'
} else {
    Write-Host "  复用已有密钥: $ks"
}

# ── zipalign + sign ──
Step '9. 对齐与签名'
$aligned = Join-Path $Build 'app-aligned.apk'
& "$BT\zipalign.exe" -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { Fail 'zipalign 失败' }

$finalName = if ($Debug) { 'qq-agent-debug.apk' } else { 'qq-agent-android.apk' }
$final = Join-Path $OutDir $finalName
& "$BT\apksigner.bat" sign `
    --ks $ks `
    --ks-pass pass:qqagent2026 `
    --key-pass pass:qqagent2026 `
    --ks-key-alias qqagent `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --out $final `
    $aligned
if ($LASTEXITCODE -ne 0) { Fail 'apksigner 签名失败' }

# ── 验证 ──
Step '10. 验证签名'
& "$BT\apksigner.bat" verify --verbose $final
if ($LASTEXITCODE -ne 0) { Fail '签名验证失败' }

Step '完成'
$sizeMb = [math]::Round((Get-Item $final).Length / 1MB, 1)
Write-Host ""
Write-Host "  APK: $final" -ForegroundColor Green
Write-Host "  体积: $sizeMb MB" -ForegroundColor Green
Write-Host ""
Write-Host '安装：'
Write-Host "  adb install -r `"$final`""
Write-Host ""
Write-Host '或把 APK 传到手机，用文件管理器点开安装（需允许"安装未知来源应用"）。'
