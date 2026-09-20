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
$AppVersion = '1.2.2'   # Windows exe file version (x.y.z form); project release is 1.2.2
$MainClass  = 'com.island.IslandApplication'
$MainJar    = 'Java-island-1.2.2.jar'
$Staging    = Join-Path $Root 'target\jpackage-input'
$DistDir    = Join-Path $Root 'dist'
$ImageRoot  = Join-Path $DistDir $AppName
$IconPath   = Join-Path $Root 'app-icon.ico'

# jpackage(JDK 25) 默认运行时模块集 + jdk.crypto.mscapi/jdk.crypto.cryptoki/jdk.localedata：
# 前者是 jpackage 自带运行时的模块清单（保持体积不变）；
# jdk.crypto.* 提供 SunMSCAPI 提供者，Windows-ROOT 信任库（IslandApplication 中设置）
# 必需，否则打包产物启动即崩；
# jdk.localedata 提供 CLDR 多语言区域数据，缺失时 DateTimeFormatter 即使显式
# withLocale(SIMPLIFIED_CHINESE) 也拿不到中文格式，日期会退化为英文 "2026 Aug 31"。
$RuntimeModules = @(
    'java.base', 'java.compiler', 'java.datatransfer', 'java.xml', 'java.prefs',
    'java.desktop', 'java.instrument', 'java.logging', 'java.management',
    'java.security.sasl', 'java.naming', 'java.rmi', 'java.management.rmi',
    'java.net.http', 'java.scripting', 'java.security.jgss', 'java.smartcardio',
    'java.transaction.xa', 'java.sql', 'java.sql.rowset', 'java.xml.crypto',
    'jdk.accessibility', 'jdk.internal.jvmstat', 'jdk.attach', 'jdk.internal.opt',
    'jdk.zipfs', 'jdk.compiler', 'jdk.dynalink', 'jdk.httpserver',
    'jdk.incubator.vector', 'jdk.internal.ed', 'jdk.internal.le', 'jdk.internal.md',
    'jdk.jartool', 'jdk.javadoc', 'jdk.management', 'jdk.management.agent',
    'jdk.jconsole', 'jdk.jdwp.agent', 'jdk.jdi', 'jdk.jfr', 'jdk.jshell',
    'jdk.jsobject', 'jdk.management.jfr', 'jdk.net', 'jdk.nio.mapmode', 'jdk.sctp',
    'jdk.security.auth', 'jdk.security.jgss', 'jdk.unsupported',
    'jdk.unsupported.desktop', 'jdk.xml.dom',
    'jdk.crypto.mscapi', 'jdk.crypto.cryptoki', 'jdk.localedata'
) -join ','

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
  Build a full runtime image via jlink (default module set = full JDK runtime)
  plus jdk.crypto.mscapi / jdk.crypto.cryptoki: JDK 25 jpackage strips them from
  the default image, but the app switches the HTTPS trust store to Windows-ROOT
  (IslandApplication), which needs the SunMSCAPI provider; without it the default
  SSLContext fails to initialize and the packaged app crashes on startup.
  jdk.localedata is also included: without CLDR locale data the zh-CN date
  formatter falls back to the root pattern and the island shows "2026 Aug 31".
#>
function Build-RuntimeImage {
    param([string]$JdkHome)
    # 缓存目录带模块集哈希后缀：模块清单变化时自动失效重建，
    # 避免旧缓存（如缺 jdk.localedata）被误用导致日期显示英文回归
    $moduleHash = ([System.Security.Cryptography.MD5]::Create().ComputeHash(
        [System.Text.Encoding]::UTF8.GetBytes($RuntimeModules)) |
        ForEach-Object { $_.ToString('x2') }) -join ''
    $moduleHash = $moduleHash.Substring(0, 8)
    $out = Join-Path $Root ('target\jpackage-runtime-' + $moduleHash)
    if (Test-Path (Join-Path $out 'release')) {
        Write-Host '  using cached target\jpackage-runtime-'$moduleHash
        return $out
    }
    $jlink = Join-Path $JdkHome 'bin\jlink.exe'
    if (-not (Test-Path $jlink)) { throw "jlink.exe not found: $jlink" }
    if (Test-Path $out) { Remove-Item $out -Recurse -Force }
    $jlinkArgs = @(
        '--add-modules', $RuntimeModules,
        '--no-header-files', '--no-man-pages',
        '--compress', 'zip-6',
        '--output', $out
    )
    & $jlink @jlinkArgs 2>&1 | ForEach-Object { Write-Host "  $_" }
    if ($LASTEXITCODE -ne 0) { throw "jlink failed, exit $LASTEXITCODE" }
    return $out
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
    $runtimeImage = Build-RuntimeImage -JdkHome $JdkHome
    $jpackageArgs = @(
        '--type', 'app-image',
        '--name', $AppName,
        '--app-version', $AppVersion,
        '--vendor', 'Island',
        '--description', 'Java-Island Dynamic Island Desktop App',
        '--input', $Staging,
        '--main-jar', $MainJar,
        '--main-class', $MainClass,
        '--runtime-image', $runtimeImage,
        '--dest', $DistDir,
        '--java-options', '-Dfile.encoding=UTF-8',
        # 长期运行内存治理：硬顶 512m 杜绝涨到 1GB；SoftMaxHeapSize 让常态堆压回 224m；
        # G1PeriodicGCInterval 空闲 5min 触发并发标记并 uncommit 归还内存（解决"只扩不还"）；
        # MaxMetaspaceSize 给元数据区设上限，防类/lambda 缓慢堆积。
        '--java-options', '-Xmx512m -XX:SoftMaxHeapSize=224m -XX:G1PeriodicGCInterval=300000 -XX:+G1PeriodicGCInvokesConcurrent -XX:MaxMetaspaceSize=192m'
    )
    if (Test-Path $IconPath) { $jpackageArgs += @('--icon', $IconPath) }
    & $Jpackage @jpackageArgs
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed, exit $LASTEXITCODE" }

    # ── 4. copy daemons next to exe ──
    Write-Host '[4/6] copying daemons (MediaInfoDaemon / WechatNotifyDaemon / QqNotifyDaemon / ncm-server / QQMusicapi / qishui-api) ...'
    foreach ($f in @('MediaInfoDaemon.exe', 'MediaInfoDaemon.pdb', 'WechatNotifyDaemon.exe', 'QqNotifyDaemon.exe', 'ncm-server.exe')) {
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
    $qsDir = Join-Path $Root 'qishui-api'
    if (Test-Path $qsDir) {
        Copy-Item $qsDir (Join-Path $ImageRoot 'qishui-api') -Recurse -Force
        # 不随包分发本地登录态/运行时文件：清理 .env 与临时文件，写入无凭据的最小配置（默认端口即 3300）
        $qsImage = Join-Path $ImageRoot 'qishui-api'
        Remove-Item (Join-Path $qsImage '.env') -Force -ErrorAction SilentlyContinue
        Remove-Item (Join-Path $qsImage '.session.tmp') -Force -ErrorAction SilentlyContinue
        Set-Content -Path (Join-Path $qsImage '.env') -Value "# qishui-api default config (bundled)`nPORT=3300`nHOST=127.0.0.1" -Encoding ASCII
        Write-Host '  + qishui-api\ (local .env replaced with credential-free defaults)'
    } else {
        Write-Warning 'skip qishui-api (not found, 汽水音乐歌词/封面不可用)'
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
