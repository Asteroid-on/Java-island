#Requires -Version 5.1
<#
  Java-Island jpackage packaging script.
  Usage : powershell -ExecutionPolicy Bypass -File package-jpackage.ps1 [-NoZip] [-NoNode] [-NodeVersion v22.14.0]
  Output: dist\Java-island\Java-island.exe (portable app-image, double-click to run)

  Notes :
    - MediaInfoDaemon.exe / ncm-server.exe / QQMusicapi are copied next to the exe,
      because the app launches them relative to the exe directory at runtime.
    - node.exe from the official Node.js zip is bundled into node\ next to the exe,
      so the target machine does NOT need Node.js installed (-NoNode to skip).
    - app-icon.ico (generated from src\main\resources\icons\favicon.pub.zip via make-icon.ps1)
      is used as the exe icon when present.
#>
param(
    [switch]$NoZip,
    [switch]$NoNode,
    [string]$NodeVersion = 'v22.14.0'
)
$ErrorActionPreference = 'Stop'

$Root       = Split-Path -Parent $MyInvocation.MyCommand.Path
$AppName    = 'Java-island'
$AppVersion = '1.0.1'   # Windows exe file version (x.y.z form); project release is 1.0.1
$MainClass  = 'com.island.IslandApplication'
$MainJar    = 'Java-island-1.0.1.jar'
$Staging    = Join-Path $Root 'target\jpackage-input'
$DistDir    = Join-Path $Root 'dist'
$ImageRoot  = Join-Path $DistDir $AppName
$IconPath   = Join-Path $Root 'app-icon.ico'

function Find-Mvn {
    $cmd = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    # IntelliJ bundled Maven: both C: and D: installs, e.g. 'D:\IntelliJ IDEA 2025.2\plugins\maven\lib\maven3\bin\mvn.cmd'
    $ideaMvn = @()
    if (Test-Path 'C:\Program Files\JetBrains') {
        $ideaMvn += Get-ChildItem 'C:\Program Files\JetBrains' -Filter 'mvn.cmd' -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -like '*plugins\maven\lib\maven3\bin\mvn.cmd' }
    }
    $ideaMvn += Get-ChildItem 'D:\' -Directory -Filter 'IntelliJ IDEA*' -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'plugins\maven\lib\maven3\bin\mvn.cmd' } |
        Where-Object { Test-Path $_ }
    if ($ideaMvn.Count -gt 0) { return $ideaMvn[0] }
    throw 'mvn.cmd not found. Add Maven to PATH or use the IntelliJ bundled Maven (plugins\maven\lib\maven3\bin).'
}

function Find-Jpackage {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += Join-Path $env:JAVA_HOME 'bin\jpackage.exe' }
    # IntelliJ-managed JDKs (%USERPROFILE%\.jdks), highest version first
    $jdksDir = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path $jdksDir) {
        $candidates += Get-ChildItem $jdksDir -Directory |
            Sort-Object Name -Descending |
            ForEach-Object { Join-Path $_.FullName 'bin\jpackage.exe' } |
            Where-Object { Test-Path $_ }
    }
    if (Test-Path "$env:ProgramFiles\Java") {
        $candidates += Get-ChildItem "$env:ProgramFiles\Java" -Filter 'jpackage.exe' -Recurse -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -ExpandProperty FullName
    }
    if (Test-Path "$env:ProgramFiles\JetBrains") {
        $candidates += Get-ChildItem "$env:ProgramFiles\JetBrains" -Filter 'jpackage.exe' -Recurse -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty FullName
    }
    $cmd = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }

    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    throw 'jpackage.exe not found. Install JDK 25 (same as the compile target) or set JAVA_HOME.'
}

<#
  Download the official Node.js win-x64 zip and extract only node.exe into node\ of the image.
  QQMusicapi deps are pure JS (koa/undici), so node.exe alone is enough - npm is not needed.
#>
function Ensure-BundledNode {
    param([string]$Version, [string]$ImageRoot)
    $nodeExe = Join-Path $ImageRoot 'node\node.exe'
    if (Test-Path $nodeExe) {
        Write-Host "  node.exe already exists: $nodeExe"
        return
    }

    $zipName  = "node-$Version-win-x64.zip"
    # cache lives OUTSIDE target\ (mvn clean would wipe it on every run)
    $cacheDir = Join-Path $Root '.node-cache'
    New-Item -ItemType Directory -Path $cacheDir -Force | Out-Null
    $zipPath = Join-Path $cacheDir $zipName
    if (-not (Test-Path $zipPath)) {
        $url = "https://nodejs.org/dist/$Version/$zipName"
        Write-Host "  downloading $url ..."
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri $url -OutFile $zipPath
    } else {
        Write-Host "  using cached $zipPath"
    }

    $tmp = Join-Path $env:TEMP ('node-bundle-' + [guid]::NewGuid().ToString('N'))
    Expand-Archive -Path $zipPath -DestinationPath $tmp -Force
    $src = Join-Path $tmp "node-$Version-win-x64\node.exe"
    if (-not (Test-Path $src)) { throw "node.exe not found after extract: $src" }
    $nodeDir = Join-Path $ImageRoot 'node'
    New-Item -ItemType Directory -Path $nodeDir -Force | Out-Null
    Copy-Item $src $nodeExe -Force
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host '  + node\node.exe'
}

# ── 0. locate tools ──
$Mvn      = Find-Mvn
$Jpackage = Find-Jpackage
$JdkHome  = Split-Path -Parent (Split-Path -Parent $Jpackage)
Write-Host "Maven    : $Mvn"
Write-Host "jpackage : $Jpackage"
$java = Join-Path $JdkHome 'bin\java.exe'
if (Test-Path $java) {
    Write-Host 'jpackage JDK:'
    # cmd /c wrapper: java -version prints to stderr, which would abort the script
    # under $ErrorActionPreference='Stop' in Windows PowerShell 5.1
    cmd /c "`"$java`" -version 2>&1" | ForEach-Object { Write-Host "  $_" }
}
Write-Host ''

Push-Location $Root
try {
    # compile with the same JDK that ships the jpackage runtime (avoids class-version mismatch)
    $env:JAVA_HOME = $JdkHome

    # ── 1. full rebuild ──
    Write-Host '[1/6] mvn clean package ...'
    & $Mvn clean package
    if ($LASTEXITCODE -ne 0) { throw "mvn clean package failed, exit $LASTEXITCODE" }
    if (-not (Test-Path (Join-Path $Root "target\$MainJar"))) { throw "missing target\$MainJar" }

    # ── 2. collect jars ──
    Write-Host '[2/6] collecting dependency jars ...'
    if (Test-Path $Staging) { Remove-Item $Staging -Recurse -Force }
    New-Item -ItemType Directory -Path $Staging | Out-Null
    & $Mvn org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies `
        "-DoutputDirectory=$Staging" "-DincludeScope=runtime"
    if ($LASTEXITCODE -ne 0) { throw "copy-dependencies failed, exit $LASTEXITCODE" }
    Copy-Item (Join-Path $Root "target\$MainJar") $Staging -Force

    # ── 3. jpackage app-image ──
    Write-Host '[3/6] jpackage --type app-image ...'
    if (Test-Path $ImageRoot) {
        try { Remove-Item $ImageRoot -Recurse -Force }
        catch { throw 'cannot remove old image. close the running Java-island instance first.' }
    }
    $jpackageArgs = @(
        '--type', 'app-image',
        '--name', $AppName,
        '--app-version', $AppVersion,
        '--vendor', 'Island',
        '--description', 'Java-Island Dynamic Island Desktop App',
        '--input', $Staging,
        '--main-jar', $MainJar,
        '--main-class', $MainClass,
        '--dest', $DistDir,
        '--java-options', '-Dfile.encoding=UTF-8'
    )
    if (Test-Path $IconPath) { $jpackageArgs += @('--icon', $IconPath) }
    & $Jpackage @jpackageArgs
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed, exit $LASTEXITCODE" }

    # ── 4. copy daemons next to exe ──
    Write-Host '[4/6] copying daemons (MediaInfoDaemon / ncm-server / QQMusicapi) ...'
    foreach ($f in @('MediaInfoDaemon.exe', 'MediaInfoDaemon.pdb', 'ncm-server.exe')) {
        $src = Join-Path $Root $f
        if (Test-Path $src) {
            Copy-Item $src $ImageRoot -Force
            Write-Host "  + $f"
        } else {
            Write-Warning "skip $f (not found)"
        }
    }
    $qqDir = Join-Path $Root 'QQMusicapi'
    if (Test-Path $qqDir) {
        Copy-Item $qqDir (Join-Path $ImageRoot 'QQMusicapi') -Recurse -Force
        Write-Host '  + QQMusicapi\'
    } else {
        Write-Warning 'skip QQMusicapi (not found, QQ music features unavailable)'
    }

    # ── 5. bundle Node.js ──
    if ($NoNode) {
        Write-Host '[5/6] skipping bundled Node.js (will fall back to system Node)'
    } else {
        Write-Host "[5/6] bundling Node.js $NodeVersion ..."
        Ensure-BundledNode -Version $NodeVersion -ImageRoot $ImageRoot
    }

    # ── 6. optional zip ──
    $zip = Join-Path $DistDir 'Java-island-portable.zip'
    if (-not $NoZip) {
        Write-Host '[6/6] compressing portable zip ...'
        if (Test-Path $zip) { Remove-Item $zip -Force }
        Compress-Archive -Path $ImageRoot -DestinationPath $zip
    }

    Write-Host ''
    Write-Host 'Done:'
    Write-Host "  exe : $ImageRoot\$AppName.exe"
    if (-not $NoZip) { Write-Host "  zip : $zip" }
} finally {
    Pop-Location
}
