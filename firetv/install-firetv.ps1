# Installs the Reel Fire TV app (an .apk file) onto a Fire Stick over your network.
#
# Before running, on the Fire Stick:
#   Settings > My Fire TV > Developer Options > turn ON "ADB debugging"
#     (no Developer Options? Settings > My Fire TV > About, press Select on the device name 7 times)
#   Settings > My Fire TV > About > Network shows its IP address.
#
# Run:   powershell -ExecutionPolicy Bypass -File .\install-firetv.ps1
#   or:  powershell -ExecutionPolicy Bypass -File .\install-firetv.ps1 -Ip 192.168.1.50 -Apk C:\path\app-debug.apk

param([string]$Ip = '192.168.1.8', [string]$Apk)
$ErrorActionPreference = 'Stop'

if (-not $Ip) { $Ip = Read-Host 'Fire Stick IP address' } else { Write-Host "Fire Stick: $Ip" }

# Find the app file: the one you pass in, or the newest .apk in the usual places.
$built = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not $Apk -and (Test-Path $built)) { $Apk = $built; Write-Host "Using $Apk" }
if (-not $Apk) {
  $places = @("C:\Users\enthu\Downloads\ReelProMax\Reel", "$env:USERPROFILE\Downloads", "$env:USERPROFILE\Desktop", "$env:USERPROFILE\source", "$env:USERPROFILE\OneDrive")
  $found = $places | Where-Object { Test-Path $_ } |
    ForEach-Object { Get-ChildItem $_ -Recurse -Filter *.apk -ErrorAction SilentlyContinue } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if ($found) {
    $Apk = $found.FullName
    Write-Host "Using $Apk"
  } else {
    $Apk = Read-Host 'Path to the Reel .apk file'
  }
}
if (-not (Test-Path $Apk)) { throw "Can't find $Apk" }

# adb (Android's install tool): use the one on this PC, or fetch Google's small official package once.
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
  $dir = "$env:LOCALAPPDATA\Reel\platform-tools"
  $adb = "$dir\adb.exe"
  if (-not (Test-Path $adb)) {
    Write-Host 'Getting adb (about 7 MB, from Google)...'
    $zip = "$env:TEMP\platform-tools.zip"
    Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
    Expand-Archive $zip -DestinationPath "$env:LOCALAPPDATA\Reel" -Force
    Remove-Item $zip
  }
}

Write-Host "Connecting to $Ip ... (accept the 'Allow USB debugging?' prompt on the TV, tick Always allow)"
& $adb connect "${Ip}:5555"
Start-Sleep -Seconds 2
& $adb devices

Write-Host 'Installing Reel...'
& $adb -s "${Ip}:5555" install -r $Apk
if ($LASTEXITCODE -ne 0) { throw 'The install did not go through. Check ADB debugging is on and you accepted the prompt on the TV, then run this again.' }

Write-Host ''
Write-Host 'Done. On the Fire Stick: Your Apps & Channels > Reel. You can switch ADB debugging off again.'
