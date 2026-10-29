# Builds Reel and installs it on the Fire TV. Run from this folder:
#   powershell -ExecutionPolicy Bypass -File .\build-and-install.ps1
# Another Fire TV:  ... -File .\build-and-install.ps1 -Ip 192.168.1.13
param([string]$Ip = "192.168.1.8")
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

# adb: use the Android SDK's platform-tools (installed by Android Studio), then fall back to PATH.
$adbCandidates = @(
    $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }),
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    $(Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source)
)
$adb = $adbCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $adb) { throw "Couldn't find adb.exe. Install Android SDK Platform-Tools from Android Studio's SDK Manager." }

Push-Location $PSScriptRoot
try {
    & $gradlew assembleDebug
    if ($LASTEXITCODE -ne 0) { throw "Build failed, see the errors above." }
} finally { Pop-Location }

Write-Host "Connecting to Fire TV at $fireTv..."
# adb connect exits 0 even when it fails, so check its output instead.
$connectOutput = & $adb connect $fireTv 2>&1 | Out-String
Write-Host $connectOutput
if ($connectOutput -notmatch "connected to") { throw "Could not connect to the Fire TV. Check that ADB debugging is enabled and the Fire Stick is on the same network." }

$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk"
& $adb -s $fireTv install -r $apk
if ($LASTEXITCODE -ne 0) { throw "Install failed. Check the Fire TV connection and the generated APK path." }

Write-Host "Done. Open Reel on the Fire TV."
