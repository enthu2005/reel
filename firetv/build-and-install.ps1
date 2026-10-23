# Builds Reel and installs it on the Fire TV. Run from this folder:
#   powershell -ExecutionPolicy Bypass -File .\build-and-install.ps1
$ErrorActionPreference = "Stop"
$fireTv = "192.168.0.225:5555"

# Java: use the one that ships with Android Studio
$jbr = @("C:\Program Files\Android\Android Studio\jbr", "$env:LOCALAPPDATA\Programs\Android Studio\jbr") |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $jbr) { throw "Couldn't find Android Studio's Java (jbr folder). Edit this script with its path." }
$env:JAVA_HOME = $jbr

# Gradle: reuse the copy Android Studio already downloaded
$gradle = Get-ChildItem "$HOME\.gradle\wrapper\dists\gradle-8.9-bin\*\gradle-8.9\bin\gradle.bat" -ErrorAction SilentlyContinue |
    Select-Object -First 1
if (-not $gradle) { throw "Couldn't find Gradle 8.9 in $HOME\.gradle. Build once in Android Studio first." }

# adb: from the Android SDK
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = ".\adb.exe" }

& $gradle.FullName assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Build failed, see the errors above." }

& $adb connect $fireTv
& $adb -s $fireTv install -r "app\build\outputs\apk\debug\app-debug.apk"
Write-Host "Done. Open Reel on the Fire TV."
