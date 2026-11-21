<#
.SYNOPSIS
  JWH-Launcher 更新分发：读取已打包产物 -> 生成 manifest.json -> 上传 Gitee Release(latest)。

.DESCRIPTION
  配套打包流程使用：先用打包脚本产出增量 jar / 六层资源 zip / 全量 zip / 全量分卷到 -OutDir，
  本脚本负责：
    1. 逐项校验产物存在、计算 size 与 SHA-256；
    2. 生成双源 urls 的 manifest.json（urls[0] 主源占位、urls[1] Gitee 兜底直链）；
    3. 上传 manifest 与全部更新包到 Gitee Release 标签 latest；
    4. 输出每个附件的下载直链清单。

  源码双推（gitee / github）不在本脚本内，见 RELEASE.md「双推源码」一节。

.PARAMETER Version
  本次发布版本号，例如 1.0.0。必填。

.PARAMETER OutDir
  产物所在目录（打包脚本输出目录）。默认取本脚本上级目录的上级（即工程根的同级交付目录）。

.PARAMETER GiteeOwner
  Gitee 命名空间/所有者，默认 JWH-Minecraft。

.PARAMETER GiteeRepo
  Gitee 仓库名，默认 jwh-launcher。

.PARAMETER GiteeToken
  Gitee 私人令牌（需 projects 权限）。缺省读环境变量 GITEE_TOKEN。上传阶段必填。

.PARAMETER PrimaryBase
  主源静态直链前缀，缺省为占位 https://你的更新源/。主源接入后替换；不影响 Gitee 兜底。

.PARAMETER Notes
  更新说明（写入 manifest 的 notes 字段）。缺省为版本模板文案。

.PARAMETER SkipUpload
  只在本地生成 manifest.json 并校验产物，不调用 Gitee 上传接口。

.EXAMPLE
  # 仅本地生成 manifest 并校验
  .\tools\release-upload.ps1 -Version 1.0.0 -SkipUpload

.EXAMPLE
  # 生成 manifest 并上传到 Gitee Release latest
  $env:GITEE_TOKEN='xxxxxxxx'
  .\tools\release-upload.ps1 -Version 1.0.0
#>

param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$OutDir,
    [string]$GiteeOwner = 'JWH-Minecraft',
    [string]$GiteeRepo  = 'jwh-launcher',
    [string]$GiteeToken = $env:GITEE_TOKEN,
    [string]$PrimaryBase = 'https://你的更新源/',
    [string]$Notes,
    [switch]$SkipUpload
)

$ErrorActionPreference = 'Stop'
if (-not $Notes) { $Notes = "JWH-Launcher v$Version 更新清单" }

# ---------- 路径推导 ----------
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projDir   = Split-Path -Parent $scriptDir
if (-not $OutDir) { $OutDir = Split-Path -Parent $projDir }

$giteeBase = "https://gitee.com/$GiteeOwner/$GiteeRepo/releases/download/latest/"

function Get-FileSha256([string]$path) {
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLower()
}

function Assert-Artifact([string]$file) {
    if (-not (Test-Path -LiteralPath $file)) {
        throw "缺少产物文件：$file"
    }
}

# ---------- 1. 定位并校验产物 ----------
$incJar   = Join-Path $OutDir "JWH-Launcher-${Version}.jar"
$appZip   = Join-Path $OutDir "JWH-Launcher-v${Version}.zip"
Assert-Artifact $incJar
Assert-Artifact $appZip

$layerDefs = @('relay', 'terracotta', 'music', 'neoform', 'neoforge', 'agent')
$layerFiles = @{}
foreach ($name in $layerDefs) {
    $f = Join-Path $OutDir "JWH-Launcher-$Version-$name.zip"
    Assert-Artifact $f
    $layerFiles[$name] = $f
}

# 分卷清单由全量包按 80MiB 切出；这里按磁盘上已存在的 .partN 收集并校验
$partFiles = Get-ChildItem -LiteralPath $OutDir -Filter "JWH-Launcher-v${Version}.zip.part*" |
    Sort-Object { [int]($_.Name -replace '.*\.part(\d+)$', '$1') }
if ($partFiles.Count -lt 1) { throw "缺少全量分卷（.partN）产物" }

Write-Output "==> 校验产物大小与哈希"
$incSize = (Get-Item -LiteralPath $incJar).Length
$incHash = Get-FileSha256 $incJar
$appSize = (Get-Item -LiteralPath $appZip).Length
$appHash = Get-FileSha256 $appZip
Write-Output ("  jar   size={0} sha256={1}" -f $incSize, $incHash)
Write-Output ("  full  size={0} sha256={1}" -f $appSize, $appHash)

$layerResults = @()
foreach ($name in $layerDefs) {
    $f = $layerFiles[$name]
    $layerResults += [PSCustomObject]@{
        name  = $name
        size  = (Get-Item -LiteralPath $f).Length
        sha256 = (Get-FileSha256 $f)
        file  = $f
    }
    Write-Output ("  layer {0,-11} size={1}" -f $name, (Get-Item -LiteralPath $f).Length)
}

$partResults = @()
$idx = 1
foreach ($p in $partFiles) {
    $partResults += [PSCustomObject]@{
        index = $idx
        name  = $p.Name
        size  = $p.Length
        sha256 = (Get-FileSha256 $p.FullName)
        file  = $p.FullName
    }
    Write-Output ("  part  {0} size={1}" -f $p.Name, $p.Length)
    $idx++
}

# ---------- 2. 生成 manifest.json ----------
$layersJson = @()
foreach ($lr in $layerResults) {
    $fn = Split-Path -Leaf $lr.file
    $layersJson += [ordered]@{
        name    = $lr.name
        version = $Version
        urls    = @(($PrimaryBase + $fn), ($giteeBase + $fn))
        size    = $lr.size
        sha256  = $lr.sha256
    }
}
$partsJson = @()
foreach ($pr in $partResults) {
    $partsJson += [ordered]@{
        index = $pr.index
        name  = $pr.name
        urls  = @(($PrimaryBase + $pr.name), ($giteeBase + $pr.name))
        size  = $pr.size
        sha256 = $pr.sha256
    }
}

$manifest = [ordered]@{
    version = $Version
    notes   = $Notes
    jar     = [ordered]@{
        urls    = @(($PrimaryBase + (Split-Path -Leaf $incJar)), ($giteeBase + (Split-Path -Leaf $incJar)))
        size    = $incSize
        sha256  = $incHash
    }
    layers  = $layersJson
    size    = $appSize
    sha256  = $appHash
    parts   = $partsJson
}

$manifestPath = Join-Path $OutDir 'manifest.json'
$json = $manifest | ConvertTo-Json -Depth 8
[IO.File]::WriteAllText($manifestPath, $json, (New-Object Text.UTF8Encoding($false)))
# 回读校验 JSON 合法
$null = $json | ConvertFrom-Json
Write-Output "==> manifest.json 已生成：$manifestPath"

if ($SkipUpload) {
    Write-Output "==> -SkipUpload 指定，跳过 Gitee 上传。"
    return
}

# ---------- 3. 上传到 Gitee Release latest ----------
if (-not $GiteeToken) { throw "未提供 Gitee 令牌：请传 -GiteeToken 或设置环境变量 GITEE_TOKEN。" }

$apiBase = "https://gitee.com/api/v5/repos/$GiteeOwner/$GiteeRepo"

# 取已存在的 latest Release；没有则创建
$releaseId = $null
try {
    $latest = Invoke-RestMethod -Uri "$apiBase/releases/latest?access_token=$GiteeToken" -TimeoutSec 15
    $releaseId = $latest.id
    Write-Output "==> 复用已有 Release：id=$releaseId tag=$($latest.tag_name)"
} catch {
    $body = @{
        access_token      = $GiteeToken
        tag_name          = 'latest'
        name              = "v$Version"
        body              = "JWH-Launcher v$Version 更新分发"
        target_commitish  = 'main'
    } | ConvertTo-Json
    $created = Invoke-RestMethod -Uri "$apiBase/releases" -Method Post -ContentType 'application/json' -Body $body -TimeoutSec 15
    $releaseId = $created.id
    Write-Output "==> 已创建 Release：id=$releaseId tag=$($created.tag_name)"
}

function Upload-Attachment([string]$file) {
    $resp = & curl.exe -s --max-time 600 -X POST "$apiBase/releases/$releaseId/attach_files" `
        -F "access_token=$GiteeToken" -F "release_id=$releaseId" -F "file=@$file"
    $obj = $resp | ConvertFrom-Json
    if (-not $obj.browser_download_url) { throw "上传失败：$file -> $resp" }
    return $obj.browser_download_url
}

Write-Output "==> 上传附件到 Release latest"
$uploaded = @()

$manifestUrl = Upload-Attachment $manifestPath
$uploaded += [PSCustomObject]@{ file = 'manifest.json'; url = $manifestUrl }
Write-Output "  OK manifest.json"

$uploaded += [PSCustomObject]@{ file = (Split-Path -Leaf $incJar); url = (Upload-Attachment $incJar) }
Write-Output "  OK $(Split-Path -Leaf $incJar)"

foreach ($lr in $layerResults) {
    $uploaded += [PSCustomObject]@{ file = (Split-Path -Leaf $lr.file); url = (Upload-Attachment $lr.file) }
    Write-Output "  OK $(Split-Path -Leaf $lr.file)"
}
foreach ($pr in $partResults) {
    $uploaded += [PSCustomObject]@{ file = $pr.name; url = (Upload-Attachment $pr.file) }
    Write-Output "  OK $($pr.name)"
}

Write-Output ""
Write-Output "==> 上传完成，附件直链："
foreach ($u in $uploaded) { Write-Output ("  {0}  {1}" -f $u.file, $u.url) }
