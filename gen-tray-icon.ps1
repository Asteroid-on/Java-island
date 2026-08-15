<#
  Generate the tray icon from the ORIGINAL 512px favicon PNG (no cropping).
  Steps: extract favicon.pub.zip -> scale 512px PNG to 128x128 -> save to
  src\main\resources\icons\tray-icon.png
#>
Add-Type -AssemblyName System.Drawing

$zip = 'd:\Java-island\Java-island\src\main\resources\icons\favicon.pub.zip'
$tmp = Join-Path $env:TEMP 'favicon-extract4'
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
Expand-Archive -Path $zip -DestinationPath $tmp -Force

$pngPath = Join-Path $tmp 'android-chrome-512x512.png'
$outPath = 'd:\Java-island\Java-island\src\main\resources\icons\tray-icon.png'
$size = 128

$src = [System.Drawing.Bitmap]::FromFile($pngPath)
$out = New-Object System.Drawing.Bitmap $size, $size
$g = [System.Drawing.Graphics]::FromImage($out)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.DrawImage($src, 0, 0, $size, $size)
$g.Dispose()
$out.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)

$out.Dispose(); $src.Dispose()
Remove-Item $tmp -Recurse -Force
Write-Host "saved: $outPath"
Get-Item $outPath | Select-Object Name, Length
