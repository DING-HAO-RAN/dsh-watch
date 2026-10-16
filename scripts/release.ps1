# 发布 GitHub Release 并上传资产。
#
# 约定（AGENTS.md 里记录过的坑）：
# - GitHub 会剥掉资产名里的非 ASCII 字符，所以文件名固定用 DSH-Relay.exe / DSH-Watch.apk；
# - 上传必须用「只带文件」的 PUT，不要把 --data-urlencode 与 --data-binary 混在同一条 curl；
# - 上传后必须下载回来比对哈希，确认内容没被改动。
#
# 用法：
#   pwsh -File scripts/release.ps1 -Tag v1.0.1 -Files <目录> -Notes <文件>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$Files,
    [string]$Notes = '',
    [string]$Repo = 'DING-HAO-RAN/dsh-watch',
    [switch]$SkipTitle
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)

# 从 git 的凭据管理器取令牌：不落盘、不打印，复用 git push 已经用过的那个。
#
# 用临时文件 + cmd 喂给 git：PowerShell 管道的换行与结尾空行不符合
# git credential 的协议格式（会报 credential missing protocol field），
# 而 PowerShell 本身又不支持 `<` 输入重定向。
$credentialFile = Join-Path ([IO.Path]::GetTempPath()) ('gh-cred-' + [guid]::NewGuid().ToString('N') + '.txt')
try {
    [IO.File]::WriteAllText($credentialFile, "protocol=https`nhost=github.com`n`n", [Text.UTF8Encoding]::new($false))
    $credential = (& cmd /c "git credential fill < `"$credentialFile`"" 2>&1) -split "`n"
} finally {
    Remove-Item $credentialFile -Force -ErrorAction SilentlyContinue
}
$token = ($credential | Where-Object { $_ -match '^password=' } | ForEach-Object { $_ -replace '^password=', '' }) -join ''
$token = $token.Trim()
if (-not $token) { throw '取不到 GitHub 凭据，请先用 git push 过一次以让凭据管理器记住。' }

$headers = @{ Authorization = "token $token"; Accept = 'application/vnd.github+json' }
$api = "https://api.github.com/repos/$Repo"

function Invoke-GitHub {
    param([string]$Method, [string]$Uri, [object]$Body)
    $params = @{ Method = $Method; Uri = $Uri; Headers = $headers; TimeoutSec = 300 }
    if ($Body -ne $null) {
        # 必须显式用 UTF-8 编码字节再发：直接用 ConvertTo-Json 的字符串，
        # 中文在部分环境下会被替换成 `?`（实测发布说明整段变成问号）。
        $jsonText = $Body | ConvertTo-Json -Depth 6 -Compress
        $params.Body = [Text.UTF8Encoding]::new($false).GetBytes($jsonText)
        $params.ContentType = 'application/json; charset=utf-8'
    }
    return Invoke-RestMethod @params
}

Write-Host "仓库：$Repo  标签：$Tag"

# ---------------------------------------------------------------- 1. 确保 release 存在
$release = $null
try {
    $release = Invoke-GitHub -Method GET -Uri "$api/releases/tags/$Tag"
    Write-Host "  已存在同名 release，将复用它（不重复创建）"
} catch {
    Write-Host "  创建 release…"
    $payload = @{
        tag_name = $Tag
        name     = $Tag
        draft    = $false
        prerelease = $false
    }
    if ($Notes -and (Test-Path -LiteralPath $Notes)) {
        $payload.body = [IO.File]::ReadAllText($Notes, [Text.UTF8Encoding]::new($false))
    } else {
        $payload.body = "见仓库提交记录。"
    }
    $release = Invoke-GitHub -Method POST -Uri "$api/releases" -Body $payload
}

# ---------------------------------------------------------------- 2. 上传资产（已存在则替换）
$names = @('DSH-Relay.exe', 'DSH-Watch.apk')
$wanted = @(Get-ChildItem -LiteralPath $Files -File | Where-Object { $names -contains $_.Name })
if ($wanted.Count -eq 0) { throw "在 $Files 里没有找到要发布的文件（需要 $($names -join '、')）" }

foreach ($file in $wanted) {
    Write-Host "上传 $($file.Name)（$([Math]::Round($file.Length / 1KB, 1)) KB）…"

    # 同名资产先删掉，否则会得到 xxx.1.exe 这种重复条目。
    foreach ($asset in $release.assets) {
        if ($asset.name -eq $file.Name) {
            Write-Host "  删除旧资产 id=$($asset.id)"
            Invoke-GitHub -Method DELETE -Uri "$api/releases/assets/$($asset.id)" | Out-Null
        }
    }

    $uploadUrl = "https://uploads.github.com/repos/$Repo/releases/$($release.id)/assets?name=$($file.Name)"
    # 关键：用 curl 走二进制上传，只带文件本身。
    $result = & curl.exe -s -X POST `
        -H "Authorization: token $token" `
        -H 'Content-Type: application/octet-stream' `
        --data-binary "@$($file.FullName)" `
        $uploadUrl 2>&1
    if ($LASTEXITCODE -ne 0) { throw "上传 $($file.Name) 失败：$result" }
    $parsed = $result | ConvertFrom-Json
    if (-not $parsed.name) { throw "上传 $($file.Name) 返回异常：$result" }
    Write-Host "  完成，远端大小 $([Math]::Round($parsed.size / 1KB, 1)) KB"
}

# ---------------------------------------------------------------- 3. 下载回来比对哈希
Write-Host ''
Write-Host '校验：下载回来比对哈希'
$verifyDir = Join-Path ([IO.Path]::GetTempPath()) ('dsh-release-verify-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $verifyDir | Out-Null
try {
    $allOk = $true
    foreach ($file in $wanted) {
        $local = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
        $url = "https://github.com/$Repo/releases/download/$Tag/$($file.Name)"
        $target = Join-Path $verifyDir $file.Name
        & curl.exe -sL --max-time 300 -o $target $url 2>&1 | Out-Null
        if (-not (Test-Path -LiteralPath $target)) { Write-Host "  $($file.Name)：下载失败"; $allOk = $false; continue }
        $remote = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
        if ($local -eq $remote) {
            Write-Host "  $($file.Name)：哈希一致 $($local.Substring(0, 16))…"
        } else {
            Write-Host "  $($file.Name)：哈希不一致！本地 $($local.Substring(0, 16))… 远端 $($remote.Substring(0, 16))…"
            $allOk = $false
        }
    }
    if (-not $allOk) { throw '校验未通过，请检查上传结果' }
    Write-Host ''
    Write-Host "发布完成：https://github.com/$Repo/releases/tag/$Tag"
} finally {
    if (Test-Path $verifyDir) { Remove-Item $verifyDir -Recurse -Force -ErrorAction SilentlyContinue }
}
