# Builds the finished Reel app and wraps it in an installer you can run on any Windows PC.
#   .\make-installer.ps1
# Result: installer\output\ReelSetup-<version>.exe  (plus a plain zip as a fallback)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$version = ((Select-String -Path pubspec.yaml -Pattern '^version:\s*([0-9.]+)').Matches[0].Groups[1].Value)
Write-Host "Building Reel $version ..." -ForegroundColor Cyan

flutter pub get
if ($LASTEXITCODE -ne 0) { throw "flutter pub get failed" }
flutter build windows --release
if ($LASTEXITCODE -ne 0) { throw "Build failed, see the errors above." }

$release = "build\windows\x64\runner\Release"

# Make the app self-contained: ship Microsoft's Visual C++ runtime DLLs beside reel.exe, so it starts on a
# clean Windows PC with nothing else installed (this is allowed: they are the "app-local" redistributable files).
foreach ($dll in 'msvcp140.dll','vcruntime140.dll','vcruntime140_1.dll','msvcp140_1.dll','msvcp140_2.dll','concrt140.dll') {
    $src = Join-Path $env:WINDIR "System32\$dll"
    if (Test-Path $src) { Copy-Item $src $release -Force }
}
foreach ($need in 'reel.exe','flutter_windows.dll','vcruntime140.dll','msvcp140.dll') {
    if (-not (Test-Path (Join-Path $release $need))) { throw "Missing $need in $release - cannot make a self-contained installer." }
}
New-Item -ItemType Directory -Force installer\output | Out-Null

# Fallback that needs no installer tool: a zip of the app folder.
$zip = "installer\output\Reel-$version-portable.zip"
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path "$release\*" -DestinationPath $zip
Write-Host "Portable zip: $zip" -ForegroundColor Green

# The real installer, using Inno Setup (free). Installed once with:  winget install JRSoftware.InnoSetup
$iscc = @(
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $iscc) {
    Write-Host "Inno Setup isn't installed, so only the zip was made. Run:  winget install JRSoftware.InnoSetup  then run this again." -ForegroundColor Yellow
    explorer "installer\output"
    exit 0
}

& $iscc "/DAppVersion=$version" "installer\reel.iss"
if ($LASTEXITCODE -ne 0) { throw "Installer build failed, see the errors above." }
Write-Host "Installer: installer\output\ReelSetup-$version.exe" -ForegroundColor Green
explorer "installer\output"
