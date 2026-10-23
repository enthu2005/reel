# Builds and runs Reel for Windows.
#   .\build-windows.ps1            run it at full speed (optimised, like the finished app)
#   .\build-windows.ps1 debug      run the slow debug version (more error detail, hot reload)
#   .\build-windows.ps1 release    build the finished app and open its folder
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Get-Command flutter -ErrorAction SilentlyContinue)) {
    Write-Host "Flutter isn't installed (or isn't on PATH). See README.md, step 1." -ForegroundColor Yellow
    exit 1
}

# First time only: let Flutter add the Windows project files around our code (it never overwrites ours).
if (-not (Test-Path "windows")) {
    flutter create --platforms=windows --org com.enthu --project-name reel .
    if (Test-Path "test\widget_test.dart") { Remove-Item "test\widget_test.dart" }
}

flutter pub get
if ($LASTEXITCODE -ne 0) { throw "flutter pub get failed" }

if ($args -contains 'release') {
    flutter build windows --release
    if ($LASTEXITCODE -ne 0) { throw "Build failed, see the errors above." }
    explorer "build\windows\x64\runner\Release"
} elseif ($args -contains 'debug') {
    flutter run -d windows
} else {
    # Optimised build: the debug version is several times slower, which is most of the lag you saw.
    flutter run -d windows --profile
}
