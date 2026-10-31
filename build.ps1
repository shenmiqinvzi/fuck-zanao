# 赞奥热榜 APK 一键构建（零 Gradle / 零 Android Studio，纯 aapt2 + javac + d8 + apksigner）
#
# 用法：
#   .\build.ps1                          # 用 strings.xml 里的空 token 构建
#   .\build.ps1 -Token "xxxxx"           # 把 token 编进 APK 的默认值
#   .\build.ps1 -Clean                   # 先清空 out
#   .\build.ps1 -Sdk "D:\android-sdk"    # 指定 SDK 根目录（含 build-tools 与 platforms）
#
# 前置：
#   JDK 8+ 在 JAVA_HOME（本机实测 JDK 25 可用）
#   build-tools r37（含 aapt2/d8/zipalign/apksigner）
#   android.jar（platform android-34）
#   SDK 路径查找顺序：-Sdk 参数 → $env:ANDROID_SDK_ROOT → $env:ANDROID_HOME → ..\ (同级目录)
#
# 三条硬性约束（踩过的坑，别改）：
#   1. 工程与 SDK 必须放纯 ASCII 路径。aapt2 在中文路径下静默失败：
#      aapt2. W ... AssetsProvider.cpp:114] Failed to stat file ...: No such file or directory
#      （exit 0，但产物缺资源，装上去白屏）
#   2. build-tools 必须 r37。r34 的 d8 遇到任何匿名内部类必崩：
#      java.lang.NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null
#   3. javac 必须 -source 8 -target 8 -bootclasspath。用 --release 8 会报
#      「选项 --boot-class-path 不能与 --release 一起使用」
#   4. 本脚本文件本身必须存成 UTF-8 with BOM，否则 PowerShell 5.1 解析中文注释会崩
#      Unexpected token '}' in expression or statement

param(
    [switch]$Clean,
    [string]$Token = "",
    [string]$Sdk = ""
)

$ErrorActionPreference = 'Stop'

$proj = Split-Path -Parent $MyInvocation.MyCommand.Path
$out  = Join-Path $proj 'out'
$jdk  = $env:JAVA_HOME
if (-not $jdk) { throw 'JAVA_HOME 未设置' }

# ---- 定位 SDK ----
$cands = @()
if ($Sdk) { $cands += $Sdk }
if ($env:ANDROID_SDK_ROOT) { $cands += $env:ANDROID_SDK_ROOT }
if ($env:ANDROID_HOME)     { $cands += $env:ANDROID_HOME }
$cands += (Split-Path -Parent $proj)

$bt = $null; $plat = $null
foreach ($c in $cands) {
    if (-not (Test-Path $c)) { continue }
    # build-tools：优先 r37，退而求其次找任意含 aapt2.exe 的目录
    $bts = Get-ChildItem (Join-Path $c 'build-tools') -Directory -ErrorAction SilentlyContinue |
           Sort-Object Name -Descending
    foreach ($b in $bts) {
        if (Test-Path (Join-Path $b.FullName 'aapt2.exe')) { $bt = $b.FullName; break }
    }
    if (-not $bt -and (Test-Path (Join-Path $c 'bt37\android-37.0\aapt2.exe'))) {
        $bt = Join-Path $c 'bt37\android-37.0'
    }
    # android.jar
    $j = Join-Path $c 'android.jar'
    if (Test-Path $j) { $plat = $j; break }
    $p = Get-ChildItem (Join-Path $c 'platforms') -Directory -ErrorAction SilentlyContinue |
         Sort-Object Name -Descending | Select-Object -First 1
    if ($p -and (Test-Path (Join-Path $p.FullName 'android.jar'))) {
        $plat = Join-Path $p.FullName 'android.jar'
    }
    if ($bt -and $plat) { break }
}

if (-not $bt)   { throw "找不到 build-tools（需要 r37 的 aapt2.exe）。用 -Sdk 指定 SDK 根目录。" }
if (-not $plat) { throw "找不到 android.jar（platform android-34）。用 -Sdk 指定 SDK 根目录。" }

Write-Host "build-tools : $bt"
Write-Host "android.jar : $plat"
Write-Host "JAVA_HOME   : $jdk"

if ($Clean -and (Test-Path $out)) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path "$out\cls", "$out\dex", "$out\gen" | Out-Null

# ---- 资源目录：需要注入 token 时拷一份到 out 再改，不动源码 ----
$resDir = Join-Path $proj 'res'
if ($Token) {
    $resDir = Join-Path $out 'res_src'
    if (Test-Path $resDir) { Remove-Item -Recurse -Force $resDir }
    Copy-Item (Join-Path $proj 'res') $resDir -Recurse -Force
    $sx = Join-Path $resDir 'values\strings.xml'
    $txt = [System.IO.File]::ReadAllText($sx, [System.Text.Encoding]::UTF8)
    $txt = [regex]::Replace($txt, '<string name="default_token">.*?</string>',
        '<string name="default_token">' + $Token + '</string>')
    [System.IO.File]::WriteAllText($sx, $txt, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "已注入 token（长度 $($Token.Length)）"
}

function Step($name, [scriptblock]$body) {
    & $body
    if ($LASTEXITCODE -ne 0) { throw "步骤失败: $name (exit $LASTEXITCODE)" }
    Write-Host "[ok] $name" -ForegroundColor Green
}

# 1) 编译资源
Step 'aapt2 compile' {
    & "$bt\aapt2.exe" compile --dir $resDir -o "$out\res.zip"
}

# 2) 链接资源 + 生成 R.java
Step 'aapt2 link' {
    & "$bt\aapt2.exe" link -o "$out\base.apk" -I $plat `
        --manifest "$proj\AndroidManifest.xml" -R "$out\res.zip" `
        --java "$out\gen" --auto-add-overlay
}

# 3) 编译 Java
Step 'javac' {
    $src = @(Get-ChildItem "$proj\src" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
    $src += "$out\gen\com\zanao\hot\R.java"
    & "$jdk\bin\javac.exe" -encoding UTF-8 -Xlint:-options -source 8 -target 8 `
        -bootclasspath $plat -d "$out\cls" $src
}

# 4) dex
Step 'd8' {
    $cl = @(Get-ChildItem "$out\cls" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
    & "$bt\d8.bat" --lib $plat --min-api 24 --output "$out\dex" $cl
}

# 5) 把 dex 塞进 apk
Step 'jar uf' {
    Copy-Item "$out\base.apk" "$out\unsigned.apk" -Force
    & "$jdk\bin\jar.exe" uf "$out\unsigned.apk" -C "$out\dex" classes.dex
}

# 6) 对齐
Step 'zipalign' {
    & "$bt\zipalign.exe" -f -p 4 "$out\unsigned.apk" "$out\aligned.apk"
}

# 7) 签名（keystore 口令见 README，换自己的请改这里）
Step 'apksigner sign' {
    & "$bt\apksigner.bat" sign --ks "$proj\zanao.jks" `
        --ks-pass pass:***REMOVED-PASS*** --key-pass pass:***REMOVED-PASS*** `
        --out "$out\zanao_hot_1.0.apk" "$out\aligned.apk"
}

# 8) 校验
Step 'apksigner verify' {
    & "$bt\apksigner.bat" verify --print-certs "$out\zanao_hot_1.0.apk"
}

$apk = "$out\zanao_hot_1.0.apk"
$h = (Get-FileHash $apk -Algorithm SHA256).Hash
Write-Host ""
Write-Host "产物: $apk" -ForegroundColor Cyan
Write-Host "大小: $((Get-Item $apk).Length) bytes" -ForegroundColor Cyan
Write-Host "SHA256: $h" -ForegroundColor Cyan
