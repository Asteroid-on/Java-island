Add-Type -AssemblyName System.Drawing
$zip = 'd:\Java-island\Java-island\src\main\resources\icons\favicon.pub.zip'
$tmp = Join-Path $env:TEMP 'favicon-extract2'
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
Expand-Archive -Path $zip -DestinationPath $tmp -Force
$png = Join-Path $tmp 'android-chrome-512x512.png'
$out = 'd:\Java-island\Java-island\app-icon.ico'
$sizes = @(16, 32, 48, 64, 128, 256)
$src = [System.Drawing.Image]::FromFile($png)
$list = @()
foreach ($s in $sizes) {
    $bmp = New-Object System.Drawing.Bitmap($src, $s, $s)
    $ms = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $list += , $ms.ToArray()
    $bmp.Dispose(); $ms.Dispose()
}
$src.Dispose()
$fs = [System.IO.File]::Create($out)
$bw = New-Object System.IO.BinaryWriter($fs)
$bw.Write([UInt16]0); $bw.Write([UInt16]1); $bw.Write([UInt16]$list.Count)
$offset = 6 + 16 * $list.Count
for ($i = 0; $i -lt $list.Count; $i++) {
    $s = $sizes[$i]
    $dim = if ($s -ge 256) { 0 } else { $s }
    $bw.Write([Byte]$dim); $bw.Write([Byte]$dim)
    $bw.Write([Byte]0); $bw.Write([Byte]0)
    $bw.Write([UInt16]1); $bw.Write([UInt16]32)
    $bw.Write([UInt32]$list[$i].Length)
    $bw.Write([UInt32]$offset)
    $offset += $list[$i].Length
}
foreach ($p in $list) { $bw.Write($p) }
$bw.Flush(); $bw.Close(); $fs.Close()
Remove-Item $tmp -Recurse -Force
Write-Host "icon written: $out"
Get-Item $out | Select-Object Name, Length
