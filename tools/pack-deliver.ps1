<#
.SYNOPSIS
  JWH-Launcher 交付打包：离线构建 jar -> jpackage app-image -> 外置资源 -> 应用全量 zip + 源码 zip。

.DESCRIPTION
  版本号单一来源为工程根 pom.xml 的 <version>，本脚本自动解析，不再手动填写。
  产出（输出目录默认为工程根的上级）：
    JWH-Launcher-v<版本>.zip      应用全量包（exe + app jar + 外置资源 + licenses）
    JWH-Launcher-src-v<版本>.zip  完整源码包（含工程、配套房间服务/Cloudflare 目录，若存在）

.PARAMETER SkipBuild
  跳过 mvnw 离线构建，复用 target\JWH-Launcher.jar。默认先执行离线 package。

.PARAMETER OutDir
  产物输出目录。默认工程根的上级（交付目录）。

.PARAMETER Jpackage
  jpackage.exe 路径。默认 JDK 21 安装目录下的 jpackage。

.PARAMETER RoomService
  配套房间服务目录。默认交付目录下 jwh-room-service；不存在则跳过打包进源码包。

.PARAMETER RoomCloudflare
  配套 Cloudflare 目录。默认交付目录下 jwh-room-cloudflare；不存在则跳过。

.EXAMPLE
  .\tools\pack-deliver.ps1
  .\tools\pack-deliver.ps1 -SkipBuild
#>

param(
    [switch]$SkipBuild,
    [string]$OutDir,
    [string]$Jpackage,
    [string]$RoomService,
    [string]$RoomCloudflare
)

$ErrorActionPreference = "Stop"
# 补回 System32（部分运行环境 PATH 中 %SystemRoot% 未展开，导致 robocopy 等系统命令找不到）
$env:Path = "C:\Windows\System32;" + $env:Path
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression

# ---------- 路径推导 ----------
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$projDir   = Split-Path -Parent $scriptDir
if (-not $OutDir)          { $OutDir = Split-Path -Parent $projDir }
if (-not $RoomService)     { $RoomService     = Join-Path $OutDir "jwh-room-service" }
if (-not $RoomCloudflare)  { $RoomCloudflare  = Join-Path $OutDir "jwh-room-cloudflare" }
if (-not $Jpackage)        { $Jpackage = "C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\jpackage.exe" }

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
Write-Output "==> 交付版本 v$ver（来自 pom.xml）"

$jar      = Join-Path $projDir "target\JWH-Launcher.jar"
$icon     = Join-Path $projDir "src\main\resources\icon.ico"
$resSrc   = Join-Path $projDir "src\main\resources"
$appZip   = Join-Path $OutDir "JWH-Launcher-v$ver.zip"
$srcZip   = Join-Path $OutDir "JWH-Launcher-src-v$ver.zip"
$dist     = Join-Path $env:TEMP "jwh-pack-dist"
$inputDir = Join-Path $env:TEMP "jwh-pack-input"
$srcStage = Join-Path $env:TEMP "jwh-pack-src-stage"

# ---------- STEP1 离线构建 ----------
if (-not $SkipBuild) {
    Write-Output "STEP1 mvnw offline package"
    $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $Jpackage)
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    Push-Location $projDir
    try {
        & .\mvnw.cmd -o -q package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "mvnw 构建失败，EXIT=$LASTEXITCODE" }
    } finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath $jar)) { throw "target jar 缺失：$jar" }
Write-Output ("JAR=" + (Get-Item -LiteralPath $jar).Length + " mtime=" + (Get-Item -LiteralPath $jar).LastWriteTime)

# ---------- STEP2 准备输入目录 ----------
Write-Output "STEP2 prepare input dir"
foreach ($d in @($inputDir, $srcStage)) {
    if (Test-Path -LiteralPath $d) { Remove-Item -LiteralPath $d -Recurse -Force }
    New-Item -ItemType Directory -Path $d | Out-Null
}
if (Test-Path -LiteralPath $dist) { Remove-Item -LiteralPath $dist -Recurse -Force }
New-Item -ItemType Directory -Path $dist | Out-Null
Copy-Item -LiteralPath $jar  -Destination $inputDir
Copy-Item -LiteralPath $icon -Destination $inputDir

# ---------- STEP3 jpackage app-image ----------
Write-Output "STEP3 jpackage app-image"
$exe = Join-Path $dist "JWH-Launcher\JWH-Launcher.exe"
& $Jpackage --type app-image --input $inputDir --main-jar "JWH-Launcher.jar" --main-class "com.jwh.launcher.Main" `
    --dest $dist --app-version $ver --name "JWH-Launcher" --icon $icon --java-options "-Dfile.encoding=UTF-8"
if ($LASTEXITCODE -ne 0) { Write-Output "JPACKAGE-FAILED=$LASTEXITCODE"; exit 1 }
$appRoot = Join-Path $dist "JWH-Launcher"
if (-not (Test-Path -LiteralPath $exe)) { Write-Output "EXE-MISSING"; exit 1 }
Write-Output ("EXE=" + (Get-Item -LiteralPath $exe).Length)

# ---------- STEP4 licenses ----------
$appJar = Join-Path $appRoot "app\JWH-Launcher.jar"
if (-not (Test-Path -LiteralPath $appJar)) { Write-Output "APPJAR-MISSING"; exit 1 }
Copy-Item -LiteralPath (Join-Path $resSrc "THIRD_PARTY_NOTICES.txt") -Destination (Join-Path $appRoot "THIRD_PARTY_NOTICES.txt")
New-Item -ItemType Directory -Path (Join-Path $appRoot "licenses") -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $resSrc "licenses\Terracotta-AGPL-3.0.txt") -Destination (Join-Path $appRoot "licenses\Terracotta-AGPL-3.0.txt")

# ---------- STEP4b 外置资源 ----------
Write-Output "STEP4b external resources"
$resRoot = Join-Path $appRoot "resources"
New-Item -ItemType Directory -Path $resRoot -Force | Out-Null
foreach ($dir in @("relay", "terracotta", "music")) {
    Copy-Item -LiteralPath (Join-Path $resSrc $dir) -Destination (Join-Path $resRoot $dir) -Recurse -Force
}
foreach ($f in @(Get-ChildItem -LiteralPath $resSrc -Filter "neoform-*.jar")) { Copy-Item -LiteralPath $f.FullName -Destination (Join-Path $resRoot $f.Name) -Force }
foreach ($f in @(Get-ChildItem -LiteralPath $resSrc -Filter "neoforge-*.jar")) { Copy-Item -LiteralPath $f.FullName -Destination (Join-Path $resRoot $f.Name) -Force }
Copy-Item -LiteralPath (Join-Path $resSrc "authlib-injector.jar") -Destination (Join-Path $resRoot "authlib-injector.jar") -Force

$relayN = @(Get-ChildItem (Join-Path $resRoot "relay") -File).Count
$teraN  = @(Get-ChildItem (Join-Path $resRoot "terracotta") -File).Count
$musicN = @(Get-ChildItem (Join-Path $resRoot "music") -File).Count
$neoN   = @(Get-ChildItem $resRoot -Filter "neoform-*.jar").Count
$nfN    = @(Get-ChildItem $resRoot -Filter "neoforge-*.jar").Count
$agentN = @(Test-Path (Join-Path $resRoot "authlib-injector.jar"))
Write-Output ("EXT relay=$relayN terracotta=$teraN music=$musicN neoform=$neoN neoforge=$nfN agent=$agentN")
if ($relayN -lt 4 -or $teraN -lt 2 -or $musicN -lt 13 -or $neoN -lt 4 -or $nfN -lt 3 -or -not $agentN) { Write-Output "CHECK-FAIL external resources incomplete"; exit 1 }

# ---------- STEP5 打应用 zip ----------
Write-Output "STEP5 zip app"
if (Test-Path -LiteralPath $appZip) { Remove-Item -LiteralPath $appZip -Force }
$sw = [Diagnostics.Stopwatch]::StartNew()
[IO.Compression.ZipFile]::CreateFromDirectory($appRoot, $appZip, [IO.Compression.CompressionLevel]::Optimal, $true)
$sw.Stop()
Write-Output ("APPZIP=" + (Get-Item -LiteralPath $appZip).Length + " in " + [int]$sw.Elapsed.TotalSeconds + "s")

# ---------- STEP5b 校验应用 zip ----------
Write-Output "STEP5b verify app zip"
$za = [IO.Compression.ZipFile]::OpenRead($appZip)
$names = $za.Entries | ForEach-Object { $_.FullName }
Write-Output ("APPZIP-ENTRIES=" + $names.Count)
$leak = $names | Where-Object { $_ -match "F:\\jj" -or $_ -match "33095" }
Write-Output ("LEAK-COUNT=" + @($leak).Count)
$jarEntry = $za.Entries | Where-Object { ($_.FullName -replace "\\", "/") -like "*app/JWH-Launcher.jar" } | Select-Object -First 1
if ($null -eq $jarEntry) { Write-Output "INNER-JAR-NOT-FOUND"; $za.Dispose(); exit 1 }
$tmpJar = Join-Path $env:TEMP "jwh-pack-inner.jar"
[IO.Compression.ZipFileExtensions]::ExtractToFile($jarEntry, $tmpJar, $true)
$za.Dispose()

$zj = [IO.Compression.ZipFile]::OpenRead($tmpJar)
$inner = $zj.Entries | ForEach-Object { $_.FullName }
$relay  = @($inner | Where-Object { $_ -like "relay/*" })
$tera   = @($inner | Where-Object { $_ -like "terracotta/*" })
$music  = @($inner | Where-Object { $_ -like "music/*" })
$neo    = @($inner | Where-Object { $_ -like "neoform-*.jar" })
$nfJar  = @($inner | Where-Object { $_ -like "neoforge-*.jar" })
$auth   = @($inner | Where-Object { $_ -eq "authlib-injector.jar" })
$webkit = @($inner | Where-Object { $_ -like "*jfxwebkit*" -or $_ -like "*javafx/web*" })
$lic    = @($inner | Where-Object { $_ -like "licenses/*" })
$notice = @($inner | Where-Object { $_ -eq "THIRD_PARTY_NOTICES.txt" })
$nfTxt  = @($inner | Where-Object { $_ -eq "neoforge-21.1.264-lcp.txt" -or $_ -eq "neoforge-21.1.264-natives.txt" })
$updCls = @($inner | Where-Object { ($_ -replace "\\", "/") -eq "com/jwh/launcher/update/Updater.class" })
$verCls = @($inner | Where-Object { ($_ -replace "\\", "/") -eq "com/jwh/launcher/Versions.class" })
$resCls = @($inner | Where-Object { ($_ -replace "\\", "/") -eq "com/jwh/launcher/util/Resources.class" })
$mediaCls = @($inner | Where-Object { $_ -like "javafx/scene/media/*" })
$verProps = @($inner | Where-Object { $_ -eq "version.properties" })
Write-Output ("JAR relay=$($relay.Count) terracotta=$($tera.Count) music=$($music.Count) neoform=$($neo.Count) neoforgeJar=$($nfJar.Count) authlib=$($auth.Count) webkit=$($webkit.Count)")
Write-Output ("JAR licenses=$($lic.Count) notice=$($notice.Count) nfTxt=$($nfTxt.Count) updater=$($updCls.Count) versions=$($verCls.Count) resources=$($resCls.Count) media=$($mediaCls.Count) verProps=$($verProps.Count)")
if ($relay.Count -gt 0 -or $tera.Count -gt 0 -or $music.Count -gt 0 -or $neo.Count -gt 0 -or $nfJar.Count -gt 0 -or $auth.Count -gt 0) { Write-Output "CHECK-FAIL big assets still inside fat jar"; $zj.Dispose(); exit 1 }
if ($webkit.Count -gt 0) { Write-Output "CHECK-FAIL jfxwebkit still packaged"; $zj.Dispose(); exit 1 }
if ($lic.Count -lt 1 -or $notice.Count -lt 1) { Write-Output "CHECK-FAIL license files missing"; $zj.Dispose(); exit 1 }
if ($nfTxt.Count -lt 2) { Write-Output "CHECK-FAIL neoforge txt lists missing"; $zj.Dispose(); exit 1 }
if ($updCls.Count -lt 1 -or $verCls.Count -lt 1 -or $resCls.Count -lt 1) { Write-Output "CHECK-FAIL Updater/Versions/Resources class missing"; $zj.Dispose(); exit 1 }
if ($mediaCls.Count -lt 1) { Write-Output "CHECK-FAIL javafx media classes missing"; $zj.Dispose(); exit 1 }
if ($verProps.Count -lt 1) { Write-Output "CHECK-FAIL version.properties missing in fat jar"; $zj.Dispose(); exit 1 }
$zj.Dispose()

# ---------- STEP6 打源码 zip ----------
Write-Output "STEP6 zip source"
robocopy $projDir $srcStage /E /NFL /NDL /NJH /NJS /NP /XD target dist .minecraft logs .creative-design .git CustomSkinLoader music /XF *.log *.bak *.bak_* *.bak-* | Out-Null
Get-ChildItem -LiteralPath $srcStage -Filter "*.png" -File | Remove-Item -Force
if (Test-Path -LiteralPath $RoomService) {
    robocopy $RoomService (Join-Path $srcStage "jwh-room-service") /E /NFL /NDL /NJH /NJS /NP /XD node_modules __pycache__ | Out-Null
}
if (Test-Path -LiteralPath $RoomCloudflare) {
    robocopy $RoomCloudflare (Join-Path $srcStage "jwh-room-cloudflare") /E /NFL /NDL /NJH /NJS /NP /XD node_modules .wrangler | Out-Null
}
if (Test-Path -LiteralPath $srcZip) { Remove-Item -LiteralPath $srcZip -Force }
$sw2 = [Diagnostics.Stopwatch]::StartNew()
[IO.Compression.ZipFile]::CreateFromDirectory($srcStage, $srcZip, [IO.Compression.CompressionLevel]::Optimal, $true)
$sw2.Stop()
Write-Output ("SRCZIP=" + (Get-Item -LiteralPath $srcZip).Length + " in " + [int]$sw2.Elapsed.TotalSeconds + "s")

# ---------- STEP6b 校验源码 zip ----------
Write-Output "STEP6b verify source zip"
$zs = [IO.Compression.ZipFile]::OpenRead($srcZip)
$sn = $zs.Entries | ForEach-Object { $_.FullName -replace "\\", "/" }
foreach ($k in @(
    "java/com/jwh/launcher/Versions.java",
    "java/com/jwh/launcher/update/Updater.java",
    "java/com/jwh/launcher/util/Resources.java",
    "java/com/jwh/launcher/ui/MusicBar.java",
    "java/com/jwh/launcher/net/RelayService.java",
    "java/com/jwh/launcher/net/Terracotta.java",
    "java/com/jwh/launcher/game/GameLauncher.java",
    "java/com/jwh/launcher/game/GameInstaller.java",
    "resources/version.properties",
    "resources/relay/easytier-core.exe",
    "resources/authlib-injector.jar",
    "tools/release-upload.ps1",
    "tools/pack-deliver.ps1",
    "RELEASE.md")) {
    $hit = @($sn | Where-Object { $_.EndsWith($k) }).Count
    Write-Output ("  SRC " + $k + " = " + $hit)
    if ($hit -lt 1) { Write-Output ("CHECK-FAIL missing " + $k) }
}
$zs.Dispose()

Write-Output "DONE"
Write-Output ("APPZIP-FINAL=" + (Get-Item -LiteralPath $appZip).Length)
Write-Output ("SRCZIP-FINAL=" + (Get-Item -LiteralPath $srcZip).Length)
exit 0
