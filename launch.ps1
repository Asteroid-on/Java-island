# Dev launcher: runs the app with process name "Java-island" in Task Manager.
# It copies java.exe from the JDK to target\Java-island.exe and launches it.
# Usage:
#   powershell -ExecutionPolicy Bypass -File launch.ps1              # silent (stdout -> target\app-out.log)
#   powershell -ExecutionPolicy Bypass -File launch.ps1 -Console     # visible console window (interactive debug)
#   powershell -ExecutionPolicy Bypass -File launch.ps1 -Debug       # enable -Disland.debug=true
param(
    [switch]$Debug,
    [switch]$Console
)
$ErrorActionPreference = 'Stop'

$Root      = Split-Path -Parent $MyInvocation.MyCommand.Path
$MainClass = 'com.island.IslandApplication'

# ── 1. locate JDK ──
$javaHome = $env:JAVA_HOME
if (-not $javaHome) {
    $candidates = @(
        'D:\Program Files\Java\jdk-25.0.3',
        "$env:USERPROFILE\.jdks\openjdk-25.0.2",
        "$env:USERPROFILE\.jdks\openjdk-25.0.3"
    )
    foreach ($c in $candidates) {
        if ($c -and (Test-Path (Join-Path $c 'bin\java.exe'))) { $javaHome = $c; break }
    }
}
if (-not $javaHome) {
    $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($cmd) { $javaHome = Split-Path -Parent (Split-Path -Parent $cmd.Source) }
}
if (-not $javaHome) { throw 'JDK not found. Set JAVA_HOME or install JDK 25.' }
Write-Host "JDK: $javaHome"

# ── 2. ensure renamed launcher exe (process image name = Java-island) ──
$exeName = 'Java-island.exe'
$srcJava = Join-Path $javaHome 'bin\java.exe'
if (-not (Test-Path $srcJava)) { throw "not found: $srcJava" }
$launcher = Join-Path $Root ('target\' + $exeName)
New-Item -ItemType Directory -Force -Path (Split-Path $launcher) | Out-Null
if (-not (Test-Path $launcher)) {
    Copy-Item $srcJava $launcher -Force
    Write-Host "created $launcher"
}

# ── 3. build classpath once (cached in target\cp.txt; mvn clean wipes it, auto-rebuilt) ──
$cpFile = Join-Path $Root 'target\cp.txt'
if (-not (Test-Path $cpFile)) {
    $mvn = Get-Command mvn.cmd -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Source
    if (-not $mvn) {
        $ideaMvn = Get-ChildItem 'D:\' -Directory -Filter 'IntelliJ IDEA*' -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName 'plugins\maven\lib\maven3\bin\mvn.cmd' } |
            Where-Object { Test-Path $_ } | Select-Object -First 1
        if ($ideaMvn) { $mvn = $ideaMvn }
    }
    if (-not $mvn) { throw 'mvn.cmd not found. Use IDEA bundled Maven or add Maven to PATH.' }
    Write-Host 'building classpath via mvn (one-time) ...'
    & $mvn -q -f "$Root\pom.xml" dependency:build-classpath "-Dmdep.outputFile=$cpFile"
    if ($LASTEXITCODE -ne 0) { throw 'mvn dependency:build-classpath failed' }
}
$deps = (Get-Content $cpFile -Raw).Trim()
$cp   = "$Root\target\classes;$deps"

# ── 4. launch ──
$argsList = @('-Dfile.encoding=UTF-8')
if ($Debug) { $argsList += '-Disland.debug=true' }
$argsList += @('-cp', $cp, $MainClass)
if ($Console) {
    Write-Host "launch: $exeName (console window)"
    Start-Process -FilePath $launcher -ArgumentList $argsList -WorkingDirectory $Root
} else {
    # 重定向输出到日志文件：无控制台窗口，且进程不随当前会话销毁
    $outLog = Join-Path $Root 'target\app-out.log'
    $errLog = Join-Path $Root 'target\app-err.log'
    Write-Host "launch: $exeName (silent, stdout -> target\app-out.log)"
    Start-Process -FilePath $launcher -ArgumentList $argsList -WorkingDirectory $Root `
        -RedirectStandardOutput $outLog -RedirectStandardError $errLog
}
Write-Host "started. Task Manager process name: $exeName"
