# 把中继构建成一个独立的 Windows 程序（DSH中继.exe）。
#
# 两个刻意的选择：
# 1. **用系统自带的 C# 编译器**（.NET Framework 4.8 的 csc.exe）。这样产出的 exe
#    在 Windows 10 1903 以上、Windows 11 上开箱即用，目标机不需要装 .NET SDK、
#    也不需要装 Node.js —— 这是「在任何电脑上打开」的唯一低成本路径。
# 2. **界面用 WebView2**。托管封装库（Microsoft.Web.WebView2.*.dll）不随系统提供，
#    因此由本脚本从 NuGet 取一次并缓存到 BuildRoot，编译时静态链接引用。
#    WebView2 运行时本身在 Win10/11 上随 Edge 一起装好。
#
# 用法：
#   pwsh -File scripts/build-app.ps1 -BuildRoot <工作树之外的目录>
[CmdletBinding()]
param(
    [string]$WorkRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$BuildRoot = $env:WATCH_BUILD_ROOT,
    [string]$OutputDir = '',
    [string]$WebView2Version = '1.0.2903.40',
    # 内嵌依赖按这个版本号分目录释放；换了 WebView2 版本就要跟着改。
    [string]$AppVersion = '1.0.0',
    [switch]$Console
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

$WorkRoot = [IO.Path]::GetFullPath($WorkRoot)
if (-not $BuildRoot) { $BuildRoot = Join-Path $WorkRoot 'build-app' }
$BuildRoot = [IO.Path]::GetFullPath($BuildRoot)
if (-not $OutputDir) { $OutputDir = Join-Path $WorkRoot 'dist\DSH中继' }

$src = Join-Path $WorkRoot 'desktop\src'
if (-not (Test-Path $src)) { throw "缺少源码目录：$src" }

<#
    找本机已有的 cloudflared。
    分发时把它一起打包，目标机就不必再装；找不到也不影响局域网使用。
#>
function Find-Cloudflared {
    $candidates = @(
        (Join-Path $env:USERPROFILE '.dsh\profiles\desktop\node_modules\cloudflared\bin\cloudflared.exe'),
        (Join-Path $WorkRoot 'relay\bin\cloudflared.exe')
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) { return $candidate }
    }
    return $null
}

$csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path $csc)) { throw "找不到 C# 编译器：$csc（需要 .NET Framework 4.x）" }

# ---------------------------------------------------------------- WebView2 依赖
# 需要三类文件：
#   Core / WinForms 托管程序集（编译时引用）
#   WebView2Loader.dll 原生加载器（缺它会在运行时抛「无法加载 DLL」）
#   三个架构各带一份，由系统按进程位数自动挑，这样 32/64 位 Windows 都能跑。
$webview2 = Join-Path $BuildRoot 'webview2'
$coreDll = Join-Path $webview2 'Microsoft.Web.WebView2.Core.dll'
$formsDll = Join-Path $webview2 'Microsoft.Web.WebView2.WinForms.dll'
$loaderX64 = Join-Path $webview2 'loader\x64\WebView2Loader.dll'
$loaderX86 = Join-Path $webview2 'loader\x86\WebView2Loader.dll'

if (-not (Test-Path $coreDll) -or -not (Test-Path $formsDll) -or -not (Test-Path $loaderX64)) {
    Write-Host "获取 WebView2 依赖（$WebView2Version）…"
    New-Item -ItemType Directory -Force $webview2 | Out-Null
    $package = Join-Path $webview2 'package.zip'
    $url = "https://www.nuget.org/api/v2/package/Microsoft.Web.WebView2/$WebView2Version"
    try {
        Invoke-WebRequest -Uri $url -OutFile $package -UseBasicParsing -TimeoutSec 300
    } catch {
        throw "下载 WebView2 依赖失败：$($_.Exception.Message)`n（需要一次联网；之后会缓存在 $webview2）"
    }
    $extract = Join-Path $webview2 'pkg'
    if (Test-Path $extract) { Remove-Item $extract -Recurse -Force }
    Expand-Archive -Path $package -DestinationPath $extract -Force
    Remove-Item $package -Force -ErrorAction SilentlyContinue
    # net462 版本兼容 .NET Framework 4.8。
    foreach ($name in 'Core', 'WinForms') {
        $from = Join-Path $extract "lib\net462\Microsoft.Web.WebView2.$name.dll"
        if (-not (Test-Path $from)) { throw "WebView2 包里没有找到 $name 程序集" }
        Copy-Item -LiteralPath $from -Destination $webview2 -Force
    }
    foreach ($arch in 'x64', 'x86') {
        $from = Join-Path $extract "runtimes\win-$arch\native\WebView2Loader.dll"
        if (-not (Test-Path $from)) { throw "WebView2 包里没有找到 $arch 的 WebView2Loader.dll" }
        $to = Join-Path $webview2 "loader\$arch"
        New-Item -ItemType Directory -Force $to | Out-Null
        Copy-Item -LiteralPath $from -Destination $to -Force
    }
    Remove-Item $extract -Recurse -Force -ErrorAction SilentlyContinue
}
Write-Host "WebView2 依赖：$webview2"

# ---------------------------------------------------------------- 编译
$build = Join-Path $BuildRoot ('app-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $build | Out-Null

try {
    $sources = @(Get-ChildItem $src -Filter *.cs | ForEach-Object FullName)
    if ($sources.Count -eq 0) { throw "源码目录里没有 .cs 文件" }
    Write-Host "编译 $($sources.Count) 个源文件…"

    $target = if ($Console) { 'exe' } else { 'winexe' }
    $out = Join-Path $build 'DSH中继.exe'

    # 用参数文件传递：路径含中文与特殊字符时，命令行拼接容易出错。
    $argsFile = Join-Path $build 'csc.rsp'
    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add('/nologo')
    $lines.Add('/target:' + $target)
    $lines.Add('/platform:anycpu')
    $lines.Add('/optimize+')
    $lines.Add('/langversion:5')
    $lines.Add('/out:"' + $out + '"')
    $lines.Add('/reference:"System.dll"')
    $lines.Add('/reference:"System.Core.dll"')
    $lines.Add('/reference:"System.Drawing.dll"')
    $lines.Add('/reference:"System.Windows.Forms.dll"')
    $lines.Add('/reference:"System.Web.Extensions.dll"')
    $lines.Add('/reference:"' + $coreDll + '"')
    $lines.Add('/reference:"' + $formsDll + '"')
    # 全部资源都嵌进 exe，产出**单个文件**：靠 Bootstrap 在启动时释放。
    # WebView2 的原生加载器不能只放一边——用户拷文件时漏一个就打不开。
    $lines.Add('/resource:"' + (Join-Path $src 'panel.html') + '",panel.html')
    $lines.Add('/resource:"' + $coreDll + '",Microsoft.Web.WebView2.Core.dll')
    $lines.Add('/resource:"' + $formsDll + '",Microsoft.Web.WebView2.WinForms.dll')
    $lines.Add('/resource:"' + $loaderX64 + '",WebView2Loader.x64.dll')
    $lines.Add('/resource:"' + $loaderX86 + '",WebView2Loader.x86.dll')
    # 版本号：Bootstrap 用它给释放出来的依赖分目录，改动依赖时要一起改。
    $infoFile = Join-Path $build 'AssemblyInfo.cs'
    $infoText = @"
using System.Reflection;
[assembly: AssemblyTitle("DSH 中继")]
[assembly: AssemblyProduct("DSH 中继")]
[assembly: AssemblyDescription("把 DSH 连接到手表的中继服务")]
[assembly: AssemblyVersion("1.0.0.0")]
[assembly: AssemblyFileVersion("1.0.0.0")]
[assembly: AssemblyInformationalVersion("$AppVersion")]
"@
    [IO.File]::WriteAllText($infoFile, $infoText, [Text.UTF8Encoding]::new($true))
    $lines.Add('"' + $infoFile + '"')
    foreach ($source in $sources) { $lines.Add('"' + $source + '"') }
    [IO.File]::WriteAllLines($argsFile, $lines, [Text.UTF8Encoding]::new($false))

    # csc 的输出走 stderr（中文提示），直接管道会被 PowerShell 当错误中断，
    # 因此重定向到文件后判退出码。
    $logFile = Join-Path $build 'csc.log'
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $csc ('@' + $argsFile) 2>&1 | Out-File -FilePath $logFile -Encoding utf8
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
    if ($exit -ne 0) {
        Write-Host ([IO.File]::ReadAllText($logFile, [Text.UTF8Encoding]::new($false)))
        throw "编译失败（退出码 $exit）"
    }

    # ---------------------------------------------------------------- 组装
    Write-Host '组装分发目录…'
    if (Test-Path $OutputDir) { Remove-Item $OutputDir -Recurse -Force }
    New-Item -ItemType Directory -Force $OutputDir | Out-Null
    # 依赖已经嵌进 exe（由 Bootstrap 在启动时释放），因此这里只有一个文件。
    Copy-Item -LiteralPath $out -Destination (Join-Path $OutputDir 'DSH中继.exe') -Force
    # cloudflared 单独放：它只在用户点「开启公网访问」时才需要，且体积大（约 50MB），
    # 不适合塞进 exe。缺了它只影响公网功能，局域网照常可用。
    $cloudflared = Find-Cloudflared
    if ($cloudflared) {
        Copy-Item -LiteralPath $cloudflared -Destination (Join-Path $OutputDir 'cloudflared.exe') -Force
        Write-Host '  已附带 cloudflared（支持公网访问）'
    } else {
        Write-Host '  未找到 cloudflared，公网访问需要目标机自行准备'
    }
    Copy-Item -LiteralPath (Join-Path $WorkRoot 'desktop\使用说明.txt') -Destination $OutputDir -Force -ErrorAction SilentlyContinue

    Write-Host ''
    Write-Host "构建成功：$OutputDir"
    Get-ChildItem $OutputDir -File | ForEach-Object {
        Write-Host ("  {0,-22} {1,8:N2} MB" -f $_.Name, ($_.Length / 1MB))
    }
} finally {
    if (Test-Path $build) { Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue }
}
