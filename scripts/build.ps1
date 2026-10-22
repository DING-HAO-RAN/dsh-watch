# 构建手表 APK（在 PowerShell 5.1 下也能稳定运行的版本）。
#
# 三个必须注意的坑，都在这里绕开了：
# 1. PowerShell 5.1 会把原生命令写到 stderr 的内容当成 NativeCommandError 并中断流程，
#    因此所有外部工具的输出都重定向到文件后再判断退出码，不用 2>&1 直连管道。
# 2. 长参数列表（源码清单、class 清单）经 shell 展开会被拆坏，因此一律写清单文件，
#    再用 @file 形式交给 javac / d8。
# 3. 受限语言模式下不可用 .NET 的 ZipFile，因此用 JDK 自带 jar 工具把 dex 追加进包。

[CmdletBinding()]
param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$WorkRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$BuildRoot = $env:WATCH_BUILD_ROOT,
    [string]$KeystorePath = '',
    [string]$KeystorePassword = 'android-dev',
    [string]$KeyAlias = 'dsh-watch',
    [string]$OutputDir = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
foreach ($item in @{ SdkRoot = $SdkRoot; JavaHome = $JavaHome; BuildRoot = $BuildRoot }.GetEnumerator()) {
    if (-not $item.Value) { throw "请先指定 $($item.Key)（参数或对应环境变量）" }
}

$WorkRoot = [IO.Path]::GetFullPath($WorkRoot)
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
if ($BuildRoot.StartsWith($WorkRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'BuildRoot 必须位于工作树之外'
}
if (-not $OutputDir) { $OutputDir = Join-Path $WorkRoot 'dist' }
if (-not $KeystorePath) { $KeystorePath = Join-Path $BuildRoot 'signing/dsh-watch-dev.jks' }

$env:JAVA_HOME = $JavaHome
$utf8 = [Text.UTF8Encoding]::new($false)
$javac = Join-Path $JavaHome 'bin/javac.exe'
$keytool = Join-Path $JavaHome 'bin/keytool.exe'
$jarTool = Join-Path $JavaHome 'bin/jar.exe'
foreach ($tool in @($javac, $keytool, $jarTool)) {
    if (-not (Test-Path $tool)) { throw "缺少 JDK 工具：$tool" }
}

$bt = Get-ChildItem (Join-Path $SdkRoot 'build-tools') -Directory |
    Where-Object Name -Match '^\d+\.\d+\.\d+$' |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
$androidJar = Join-Path $SdkRoot 'platforms/android-35/android.jar'
if (-not $bt -or -not (Test-Path $androidJar)) { throw '需要 Android SDK platform-35 与 build-tools' }

$main = Join-Path $WorkRoot 'app/src/main'
foreach ($required in @('AndroidManifest.xml', 'java/cn/dsh/watch/MainActivity.java', 'res/values/styles.xml')) {
    if (-not (Test-Path (Join-Path $main $required))) { throw "缺少源文件：$required" }
}

$build = Join-Path $BuildRoot ('apk-' + [guid]::NewGuid().ToString('N'))
foreach ($dir in 'flat', 'gen', 'classes', 'dex', 'log') { New-Item -ItemType Directory -Force (Join-Path $build $dir) | Out-Null }
$logDir = Join-Path $build 'log'

<#
    运行一个外部命令并把输出落盘，避免 PowerShell 的 stderr 处理中断流程。

    两个必须这样做的原因：
    - PowerShell 5.1 在 $ErrorActionPreference='Stop' 时会把原生程序写到 stderr 的
      普通提示（如 javac 的过时警告）当成致命错误并中断；
    - 本机环境变量里同时存在 HTTP_PROXY 与 http_proxy，Start-Process 在构造环境块时
      会因大小写不敏感字典重复键而抛异常，因此不能用 Start-Process。

    @return 退出码
#>
function Invoke-Step([string]$exe, [string[]]$Arguments, [string]$LogName) {
    $logFile = Join-Path $logDir ($LogName + '.log')
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $exe @Arguments 2>&1 | Out-File -FilePath $logFile -Encoding utf8
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
    return $exitCode
}

function Read-Log([string]$LogName) {
    $file = Join-Path $logDir ($LogName + '.log')
    if (Test-Path $file) {
        $text = [IO.File]::ReadAllText($file, $utf8).Trim()
        if ($text.Length -gt 0) { Write-Host $text }
    }
}

try {
    Write-Host '1/6 编译资源'
    $aapt2 = Join-Path $bt.FullName 'aapt2.exe'
    $flats = @()
    $code = Invoke-Step $aapt2 @('compile', '--dir', (Join-Path $main 'res'), '-o', (Join-Path $build 'flat')) 'aapt2-compile'
    if ($code -ne 0) { Read-Log 'aapt2-compile'; throw "aapt2 compile 失败（退出码 $code）" }
    $flats = @(Get-ChildItem (Join-Path $build 'flat') -Recurse -Filter *.flat | ForEach-Object FullName)

    Write-Host '2/6 链接资源'
    $linkArgs = @('link', '-o', (Join-Path $build 'base.apk'), '-I', $androidJar,
        '--manifest', (Join-Path $main 'AndroidManifest.xml'),
        '--java', (Join-Path $build 'gen'),
        '--version-code', '1', '--version-name', '1.0.0',
        '--min-sdk-version', '26', '--target-sdk-version', '33') + $flats
    $code = Invoke-Step $aapt2 $linkArgs 'aapt2-link'
    if ($code -ne 0) { Read-Log 'aapt2-link'; throw "aapt2 link 失败（退出码 $code）" }

    Write-Host '3/6 编译 Java'
    $sources = @(Get-ChildItem (Join-Path $main 'java'), (Join-Path $build 'gen') -Recurse -Filter *.java | ForEach-Object FullName)
    $sourceList = Join-Path $build 'sources.txt'
    [IO.File]::WriteAllLines($sourceList, $sources, $utf8)
    $code = Invoke-Step $javac @('--release', '8', '-encoding', 'UTF-8', '-nowarn',
        '-cp', $androidJar, '-d', (Join-Path $build 'classes'), ('@' + $sourceList)) 'javac'
    if ($code -ne 0) { Read-Log 'javac'; throw "javac 失败（退出码 $code）" }

    # 逐类核对：缺一个都会在手表上变成 NoClassDefFoundError，必须在这里挡住。
    $expected = @('MainActivity', 'WatchUi', 'WatchUi$Page', 'Store', 'Api', 'Discovery', 
                      'Theme', 'CodeView')
    $classesDir = Join-Path $build 'classes'
    foreach ($name in $expected) {
        $file = Join-Path $classesDir ('cn/dsh/watch/' + $name + '.class')
        if (-not (Test-Path -LiteralPath $file)) { throw "缺少编译产物：$name.class" }
    }
    Write-Host "    关键类齐全（$($expected.Count) 个）"

    Write-Host '4/6 生成 dex'
    $classFiles = @(Get-ChildItem $classesDir -Recurse -Filter *.class | ForEach-Object FullName)
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($value in @('--release', '--min-api', '26', '--lib', $androidJar, '--output', (Join-Path $build 'dex'))) {
        $lines.Add($value)
    }
    foreach ($file in $classFiles) { $lines.Add($file) }
    $d8Args = Join-Path $build 'd8-args.txt'
    [IO.File]::WriteAllLines($d8Args, $lines, $utf8)
    $code = Invoke-Step (Join-Path $bt.FullName 'd8.bat') @('@' + $d8Args) 'd8'
    if ($code -ne 0) { Read-Log 'd8'; throw "d8 失败（退出码 $code）" }
    $dexFile = Join-Path $build 'dex/classes.dex'
    if (-not (Test-Path $dexFile)) { throw 'classes.dex 未生成' }

    # 把 dex 追加进包：受限语言模式下不可用 .NET ZipFile，改用 JDK 的 jar 工具。
    $previous = Get-Location
    Set-Location (Join-Path $build 'dex')
    try {
        $code = Invoke-Step $jarTool @('--update', '--file', (Join-Path $build 'base.apk'), 'classes.dex') 'jar-update'
        if ($code -ne 0) { Read-Log 'jar-update'; throw "装入 dex 失败（退出码 $code）" }
    } finally { Set-Location $previous }

    Write-Host '5/6 对齐'
    $code = Invoke-Step (Join-Path $bt.FullName 'zipalign.exe') @('-p', '-f', '4', (Join-Path $build 'base.apk'), (Join-Path $build 'aligned.apk')) 'zipalign'
    if ($code -ne 0) { Read-Log 'zipalign'; throw "zipalign 失败（退出码 $code）" }

    Write-Host '6/6 签名'
    if (-not (Test-Path -LiteralPath $KeystorePath)) {
        New-Item -ItemType Directory -Force (Split-Path -Parent $KeystorePath) | Out-Null
        $code = Invoke-Step $keytool @('-genkeypair', '-keystore', $KeystorePath, '-alias', $KeyAlias,
            '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
            '-storepass', $KeystorePassword, '-keypass', $KeystorePassword,
            '-dname', 'CN=DSH Watch, O=Local, C=CN') 'keytool'
        if ($code -ne 0) { Read-Log 'keytool'; throw "生成签名密钥失败（退出码 $code）" }
    }
    $signer = Join-Path $bt.FullName 'apksigner.bat'
    $signed = Join-Path $build 'signed.apk'
    $code = Invoke-Step $signer @('sign', '--ks', $KeystorePath, '--ks-pass', "pass:$KeystorePassword",
        '--key-pass', "pass:$KeystorePassword", '--ks-key-alias', $KeyAlias,
        '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v4-signing-enabled', 'false',
        '--out', $signed, (Join-Path $build 'aligned.apk')) 'apksigner'
    if ($code -ne 0) { Read-Log 'apksigner'; throw "签名失败（退出码 $code）" }

    New-Item -ItemType Directory -Force $OutputDir | Out-Null
    $final = Join-Path $OutputDir 'DSH手表.apk'
    Copy-Item -LiteralPath $signed -Destination $final -Force
    Write-Host "构建成功：$final（$((Get-Item -LiteralPath $final).Length) 字节）"
} finally {
    $resolved = [IO.Path]::GetFullPath($build)
    if ($resolved.StartsWith($BuildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction SilentlyContinue
    }
}
