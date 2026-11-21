<#
.SYNOPSIS
  JWH-Launcher 更新件打包：增量 jar + 六层资源 zip + 全量包 80MiB 分卷。

.DESCRIPTION
  版本号单一来源为工程根 pom.xml 的 <version>，自动解析。
  本脚本只产出更新件到交付目录；manifest.json 的生成、哈希汇总与 Gitee 上传由
  tools\release-upload.ps1 统一完成，避免两份清单逻辑漂移。

  前置：先跑 tools\pack-deliver.ps1 产出应用全量包 JWH-Launcher-v<版本>.zip。

.PARAMETER OutDir
  产物目录。默认工程根的上级（交付目录）。

.PARAMETER PartChunkMB
  全量包分卷大小（MiB），默认 80（Gitee 单附件 ≤100MB）。

.EXAMPLE
  .\tools\pack-update.ps1
#>

param(
    [string]$OutDir,
    [int]$PartChunkMB = 80
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

# ---------- 路径推导 ----------
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projDir   = Split-Path -Parent $scriptDir
if (-not $OutDir) { $OutDir = Split-Path -Parent $projDir }
$resSrc = Join-Path $projDir "src\main\resources"

# ---------- 版本号：单一来源 pom.xml ----------
function Read-PomVersion([string]$projRoot) {
    $pom = Join-Path $projRoot 'pom.xml'
    if (-not (Test-Path -LiteralPath $pom)) { throw "找不到 pom.xml：$pom" }
    $text = [IO.File]::ReadAllText($pom)
    $m = [regex]::Match($text, '<groupId>com\.jwh</groupId>\s*<artifactId>jwh-launcher</artifactId>\s*<version>\s*([^<\s]+)\s*</version>')
    if (-not $m.Success) { throw "无法从 pom.xml 解析项目版本号" }
    return $m.Groups[1].Value.Trim()
}
$ver = Read-PomVersion $projDir
Write-Output "==> 更新件版本 v$ver（来自 pom.xml）"

$jar          = Join-Path $projDir "target\JWH-Launcher.jar"
$appZip       = Join-Path $OutDir "JWH-Launcher-v$ver.zip"
$incJar       = Join-Path $OutDir "JWH-Launcher-$ver.jar"
$layerStage   = Join-Path $env:TEMP "jwh-layer-stage"
$partChunk    = [long]$PartChunkMB * 1024 * 1024

function Sha256([string]$p) { (Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash.ToLower() }

if (-not (Test-Path -LiteralPath $jar))    { throw "target jar 缺失：$jar" }
if (-not (Test-Path -LiteralPath $appZip)) { throw "应用全量包缺失：$appZip（先跑 pack-deliver.ps1）" }

# ---------- 校验应用包内 app jar 与 target jar 哈希一致 ----------
Write-Output "==> 校验应用包内 jar 与 target jar 一致"
$za = [IO.Compression.ZipFile]::OpenRead($appZip)
$je = $null
foreach ($e in $za.Entries) {
    if ($e.FullName -and $e.FullName -like '*JWH-Launcher.jar') { $je = $e; break }
}
if ($null -eq $je) { $za.Dispose(); throw "应用包内未找到 JWH-Launcher.jar" }
$tmpInner = Join-Path $env:TEMP 'jwh-pack-inner-check.jar'
[IO.Compression.ZipFileExtensions]::ExtractToFile($je, $tmpInner, $true)
$za.Dispose()
$targetHash = Sha256 $jar
$innerHash  = Sha256 $tmpInner
Write-Output ("target=" + $targetHash)
Write-Output ("inner  =" + $innerHash)
if ($targetHash -ne $innerHash) { throw "target jar 与应用包内 jar 哈希不一致" }

# ---------- 增量 jar ----------
Copy-Item -LiteralPath $jar -Destination $incJar -Force
$incSize = (Get-Item -LiteralPath $incJar).Length
$incHash = $targetHash
Write-Output ("INCJAR size=" + $incSize + " sha256=" + $incHash)

# ---------- 六层资源包 ----------
if (Test-Path -LiteralPath $layerStage) { Remove-Item -LiteralPath $layerStage -Recurse -Force }
New-Item -ItemType Directory -Path $layerStage | Out-Null
$layerDefs = @(
    @{ name = "relay";      kind = "dir";  src = "relay" },
    @{ name = "terracotta"; kind = "dir";  src = "terracotta" },
    @{ name = "music";      kind = "dir";  src = "music" },
    @{ name = "neoform";    kind = "glob"; glob = "neoform-*.jar" },
    @{ name = "neoforge";   kind = "glob"; glob = "neoforge-*.jar" },
    @{ name = "agent";      kind = "file"; file = "authlib-injector.jar" }
)
foreach ($def in $layerDefs) {
    $ls = Join-Path $layerStage $def.name
    New-Item -ItemType Directory -Path $ls -Force | Out-Null
    if ($def.kind -eq "dir") {
        Copy-Item -LiteralPath (Join-Path $resSrc $def.src) -Destination (Join-Path $ls $def.src) -Recurse -Force
    } elseif ($def.kind -eq "glob") {
        foreach ($f in @(Get-ChildItem $resSrc -Filter $def.glob)) { Copy-Item -LiteralPath $f.FullName -Destination (Join-Path $ls $f.Name) -Force }
    } else {
        Copy-Item -LiteralPath (Join-Path $resSrc $def.file) -Destination (Join-Path $ls $def.file) -Force
    }
    $lz = Join-Path $OutDir ("JWH-Launcher-$ver-$($def.name).zip")
    if (Test-Path -LiteralPath $lz) { Remove-Item -LiteralPath $lz -Force }
    [IO.Compression.ZipFile]::CreateFromDirectory($ls, $lz, [IO.Compression.CompressionLevel]::Optimal, $false)
    Write-Output ("LAYER " + $def.name + " size=" + (Get-Item -LiteralPath $lz).Length + " sha256=" + (Sha256 $lz))
}

# ---------- 全量包切分卷 ----------
$appSize = (Get-Item -LiteralPath $appZip).Length
$appHash = Sha256 $appZip
$appBytes = [IO.File]::ReadAllBytes($appZip)
$partIdx = 1
for ($off = 0; $off -lt $appBytes.Length; $off += $partChunk) {
    $len = [Math]::Min($partChunk, $appBytes.Length - $off)
    $pn  = "JWH-Launcher-v$ver.zip.part$partIdx"
    $pp  = Join-Path $OutDir $pn
    $seg = New-Object byte[] $len
    [Array]::Copy($appBytes, $off, $seg, 0, $len)
    [IO.File]::WriteAllBytes($pp, $seg)
    Write-Output ("PART " + $partIdx + " size=" + $len + " sha256=" + (Sha256 $pp))
    $partIdx++
}

Write-Output ("DONE appSize=" + $appSize + " appHash=" + $appHash + " parts=" + ($partIdx - 1))
Write-Output "下一步：运行 tools\release-upload.ps1 生成 manifest 并上传。"
