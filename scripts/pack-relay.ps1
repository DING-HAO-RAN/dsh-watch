# 用 Node 的 SEA（Single Executable Application）把中继打包成单个可执行文件。
#
# 本机开发时用 start-relay.cmd 直接跑源码即可；这个脚本用于产出一份可以拷到
# 其它电脑上的独立程序（目标机器无需安装 Node.js）。
#
# 用法：pwsh -File scripts/pack-relay.ps1 -OutputDir dist
#
# 原理：Node 22 起支持用 postject 把打包好的 JS 注入 node 可执行文件的 SEA blob。
# 这里不引入 npm 依赖，postject 用 npx 现取一次。
[CmdletBinding()]
param(
    [string]$WorkRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$OutputDir = '',
    [string]$NodePath = '',
    # postject 的 cli.js 路径；不指定时依次找缓存的副本、再联网装。
    [string]$PostjectCli = '',
    [switch]$SkipTunnel
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

$WorkRoot = [IO.Path]::GetFullPath($WorkRoot)
# 输出到独立目录：build-app.ps1 会清空自己的输出目录，两者共用 dist/DSH中继 时
# 后跑的那个会把先跑的产物删掉。
if (-not $OutputDir) { $OutputDir = Join-Path $WorkRoot 'dist\中继命令行版' }
$relay = Join-Path $WorkRoot 'relay'

if (-not $NodePath) {
    $found = Get-Command node -ErrorAction SilentlyContinue
    if (-not $found) { throw '找不到 node，请把它加入 PATH 或用 -NodePath 指定' }
    $NodePath = $found.Source
}
Write-Host "使用的 Node：$NodePath"
$nodeVersion = (& $NodePath --version).Trim()
Write-Host "版本：$nodeVersion"
if ($nodeVersion -notmatch '^v(2[2-9]|[3-9]\d)') {
    throw "SEA 打包需要 Node 22 以上，当前是 $nodeVersion"
}

$build = Join-Path ([IO.Path]::GetTempPath()) ('relay-pack-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $build | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)

try {
    Write-Host '1/4 生成单文件脚本'
    # 拼接交给 scripts/bundle-relay.mjs：它按模块顺序合并、保留内置模块导入、
    # 把 import.meta 改写成 CJS 形式，并整体包一层 async main（CJS 不允许顶层 await）。
    # 不要改回在这里用 PowerShell 正则拼接 —— 那样会产出 `class class_ X` 这种坏代码。
    $outFile = Join-Path $build 'bundle.cjs'
    & $NodePath (Join-Path $PSScriptRoot 'bundle-relay.mjs') $outFile
    if ($LASTEXITCODE -ne 0) { throw '单文件生成失败' }
    if (-not (Test-Path $outFile)) { throw "没有生成 $outFile" }

    Write-Host '2/4 生成 SEA 配置'
    $seaConfig = @{
        main = $outFile
        output = (Join-Path $build 'sea-prep.blob')
        disableExperimentalSEAWarning = $true
    } | ConvertTo-Json
    $seaFile = Join-Path $build 'sea-config.json'
    [IO.File]::WriteAllText($seaFile, $seaConfig, $utf8)
    & $NodePath --experimental-sea-config $seaFile
    if ($LASTEXITCODE -ne 0) { throw 'SEA blob 生成失败' }

    Write-Host '3/4 复制并注入 Node 可执行文件'
    $exeName = if ($IsWindows -or $env:OS -match 'Windows') { 'DSH中继.exe' } else { 'dsh-relay' }
    $target = Join-Path $build $exeName
    Copy-Item -LiteralPath $NodePath -Destination $target -Force

    $blob = Join-Path $build 'sea-prep.blob'
    # postject 只在打包时需要。查找顺序：显式参数 → 环境变量 → 常见缓存位置 → 现场安装。
    # 直接调用它的 cli.js，不走 npx —— npx 在本机 shell 环境下解析不到包名。
    $candidates = @()
    if ($PostjectCli) { $candidates += $PostjectCli }
    if ($env:POSTJECT_CLI) { $candidates += $env:POSTJECT_CLI }
    $candidates += @(
        (Join-Path $env:LOCALAPPDATA 'dsh-relay-pack\node_modules\postject\dist\cli.js'),
        (Join-Path $build 'node_modules\postject\dist\cli.js'),
        (Join-Path (Split-Path -Parent $NodePath) 'node_modules\postject\dist\cli.js')
    )
    $cli = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1

    if (-not $cli) {
        $npm = Join-Path (Split-Path -Parent $NodePath) 'npm.cmd'
        if (-not (Test-Path $npm)) { $npm = 'npm.cmd' }
        Write-Host '  正在获取 postject…'
        Push-Location $build
        try {
            '{"name":"postject-env","private":true}' | Out-File -Encoding utf8 (Join-Path $build 'package.json')
            & $npm install --no-audit --no-fund postject 2>&1 | Out-Null
        } finally { Pop-Location }
        $cli = Join-Path $build 'node_modules\postject\dist\cli.js'
    }
    if (-not (Test-Path $cli)) {
        throw 'postject 不可用。请先装一次：npm i postject，或用 -PostjectCli 指定它的 cli.js 路径'
    }
    $fuse = 'NODE_SEA_FUSE_fce680ab2cc467b6e072b8b5df1996b2'
    & $NodePath $cli $target NODE_SEA_BLOB $blob --sentinel-fuse $fuse --overwrite
    if ($LASTEXITCODE -ne 0) { throw 'postject 注入失败' }

    Write-Host '4/4 组装分发包'
    $packageDir = Join-Path $OutputDir 'DSH中继'
    New-Item -ItemType Directory -Force $packageDir | Out-Null
    Copy-Item -LiteralPath $target -Destination (Join-Path $packageDir $exeName) -Force
    # 控制面板页面要跟着走，否则面板打不开。
    $publicDir = Join-Path $packageDir 'public'
    New-Item -ItemType Directory -Force $publicDir | Out-Null
    Copy-Item -LiteralPath (Join-Path $relay 'public/index.html') -Destination $publicDir -Force
    Copy-Item -LiteralPath (Join-Path $relay 'start-relay.cmd') -Destination $packageDir -Force -ErrorAction SilentlyContinue

    if (-not $SkipTunnel) {
        # cloudflared 用于公网中转；本机若已有就一起带上，目标机无需再装。
        $cf = Join-Path $env:USERPROFILE '.dsh\profiles\desktop\node_modules\cloudflared\bin\cloudflared.exe'
        if (Test-Path $cf) {
            $binDir = Join-Path $packageDir 'bin'
            New-Item -ItemType Directory -Force $binDir | Out-Null
            Copy-Item -LiteralPath $cf -Destination $binDir -Force
            Write-Host '  已附带 cloudflared（支持 --tunnel）'
        } else {
            Write-Host '  未找到 cloudflared，公网中转需要目标机自行准备'
        }
    }

    Write-Host "打包完成：$packageDir"
    Get-ChildItem $packageDir -Recurse -File | ForEach-Object {
        Write-Host ("  {0}  {1:N1} MB" -f $_.FullName.Replace($packageDir + '\', ''), ($_.Length / 1MB))
    }
} finally {
    if (Test-Path $build) { Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue }
}
