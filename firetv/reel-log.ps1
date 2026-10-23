# Captures what Reel says about previews (and any crash) into reel-log.txt next to this script.
#   powershell -ExecutionPolicy Bypass -File .\reel-log.ps1
param([string]$Name = "reel-log", [int]$Seconds = 25)
$ErrorActionPreference = "Stop"
$fireTv = "192.168.0.225:5555"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source }
if (-not $adb) { $adb = "$env:LOCALAPPDATA\Reel\platform-tools\adb.exe" }
if (-not (Test-Path $adb)) { throw "Can't find adb. Run install-firetv.ps1 once (it fetches it)." }

& $adb connect $fireTv | Out-Null
& $adb -s $fireTv logcat -c
$out = Join-Path $PSScriptRoot "$Name.txt"
if (Test-Path $out) { Remove-Item $out }
$p = Start-Process $adb -ArgumentList "-s $fireTv logcat -v time" `
  -RedirectStandardOutput $out -NoNewWindow -PassThru
Write-Host ""
Write-Host "Recording for $Seconds seconds (everything the stick logs). On the Fire Stick: play the film for about 20 seconds (pause it once and skip forward once too)."
Start-Sleep -Seconds $Seconds
Stop-Process $p -Force
Write-Host "Saved to $out"
