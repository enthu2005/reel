# Builds Reel and installs it on the Fire TV. Run from this folder:
#   powershell -ExecutionPolicy Bypass -File .\build-and-install.ps1
# Another Fire TV:  ... -File .\build-and-install.ps1 -Ip 192.168.1.13
param([string]$Ip = "192.168.0.225")
$ErrorActionPreference = "Stop"
$fireTv = "${Ip}:5555"

# Java: use the one that ships with Android Studio
$jbr = @("C:\Program Files\Android\Android Studio\jbr", "$env:LOCALAPPDATA\Programs\Android Studio\jbr") |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $jbr) { throw "Couldn't find Android Studio's Java (jbr folder). Edit this script with its path." }
$env:JAVA_HOME = $jbr

# Use the project's Gradle wrapper so the app builds with the compatible version it was created for.
$gradlew = Join-Path $PSScriptRoot "gradlew.bat"
if (-not (Test-Path $gradlew)) { throw "Couldn't find gradlew.bat in $PSScriptRoot" }

# adb: prefer the downloaded platform-tools folder you already have, then fall back to the SDK path.
$adbCandidates = @(
    "C:\Users\enthu\Downloads\platform-tools-latest-windows\platform-tools\adb.exe",
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    ".\adb.exe"
)
$adb = $adbCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $adb) { throw "Couldn't find adb.exe. Put it in your platform-tools folder or update this script's path." }

& $gradlew assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Build failed, see the errors above." }

Write-Host "Connecting to Fire TV at $fireTv..."
& $adb connect $fireTv
if ($LASTEXITCODE -ne 0) { throw "Could not connect to the Fire TV. Check that ADB debugging is enabled and the Fire Stick is on the same network." }

& $adb -s $fireTv install -r "app\build\outputs\apk\debug\app-debug.apk"
if ($LASTEXITCODE -ne 0) { throw "Install failed. Check the Fire TV connection and the generated APK path." }

Write-Host "Done. Open Reel on the Fire TV."
