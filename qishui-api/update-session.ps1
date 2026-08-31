# ═══ 汽水音乐登录态提取（一次性/登录态过期后重跑）═══
# 原理：读取本机汽水音乐客户端的加密 Cookie，解出 sessionid 写入 .env，
#       供 qishui-api 搜索等接口透传登录态。
# 前提：需先退出汽水音乐（Cookie 数据库被其独占锁定）。
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = Join-Path $root '.env'

if (Get-Process -Name SodaMusic -ErrorAction SilentlyContinue) {
    Write-Output '[!] 检测到汽水音乐仍在运行。'
    Write-Output '[!] 请先在汽水音乐托盘/窗口中完全退出，然后重新运行本脚本。'
    exit 1
}

$base = Join-Path $env:APPDATA 'SodaMusic'
$srcCookies = Join-Path $base 'Network\Cookies'
$srcState = Join-Path $base 'Local State'
if (-not (Test-Path $srcCookies)) {
    Write-Output '[!] 未找到汽水音乐数据目录，请确认已安装并登录过汽水音乐。'
    exit 1
}

$tmpDir = Join-Path $env:TEMP ('qishui-extract-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$dbCopy = Join-Path $tmpDir 'Cookies.db'
$keyHex = Join-Path $tmpDir 'key.hex'
$sessionOut = Join-Path $tmpDir 'session.out'

try {
    Copy-Item $srcCookies $dbCopy -Force

    # 1) DPAPI 解出 Chromium 主密钥
    $lsText = Get-Content $srcState -Raw -Encoding UTF8
    if ($lsText -notmatch '"encrypted_key":"([^"]+)"') {
        Write-Output '[!] Local State 中未找到 encrypted_key。'
        exit 1
    }
    $raw = [Convert]::FromBase64String($Matches[1])
    $payload = New-Object byte[] ($raw.Length - 5)
    [Array]::Copy($raw, 5, $payload, 0, $payload.Length)
    Add-Type -AssemblyName System.Security
    $key = [System.Security.Cryptography.ProtectedData]::Unprotect(
        $payload, $null, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
    Set-Content -Path $keyHex -Value (($key | ForEach-Object { $_.ToString('x2') }) -join '') -Encoding ASCII

    # 2) Node 解库 + AES-GCM 解密
    $node = Get-Command node.exe -ErrorAction SilentlyContinue
    if (-not $node) {
        $bundled = Join-Path (Split-Path $root -Parent) 'node\node.exe'
        if (Test-Path $bundled) { $node = $bundled } else { Write-Output '[!] 未找到 node.exe。'; exit 1 }
    }
    & $node.Source (Join-Path $root 'extract-session.js') $dbCopy $keyHex $sessionOut
    if ($LASTEXITCODE -ne 0) { Write-Output '[!] 解密失败。'; exit 1 }

    $sid = (Get-Content $sessionOut -Raw).Trim()
    if (-not $sid) {
        Write-Output '[!] 未找到 sessionid：请确认已在汽水音乐中登录账号。'
        exit 1
    }

    # 3) 写入 .env（不落其他位置，不回显明文）
    $envText = ''
    if (Test-Path $envFile) { $envText = Get-Content $envFile -Raw -Encoding UTF8 }
    if ($envText -match '(?m)^QISHUI_SESSIONID=.*$') {
        $envText = $envText -replace '(?m)^QISHUI_SESSIONID=.*$', ('QISHUI_SESSIONID=' + $sid)
    } else {
        if ($envText -and -not $envText.EndsWith("`n")) { $envText += "`n" }
        $envText += ('QISHUI_SESSIONID=' + $sid + "`n")
    }
    [System.IO.File]::WriteAllText($envFile, $envText, (New-Object System.Text.UTF8Encoding $false))
    Write-Output '[OK] sessionid 已写入 .env（掩码: ' + $sid.Substring(0, 4) + '****' + $sid.Substring($sid.Length - 4) + '）。'
    Write-Output '[OK] 请重启 Java-island 应用（或重启 qishui-api）后生效。'
} finally {
    Remove-Item $tmpDir -Recurse -Force -ErrorAction SilentlyContinue
}
