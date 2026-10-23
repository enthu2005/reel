# Runs Reel and restarts it by itself whenever a source file changes.
# Start this once and leave it open:
#   powershell -ExecutionPolicy Bypass -File .\watch-reel.ps1
# Then every change is picked up on its own, with no key to press.
#   .\watch-reel.ps1 debug     the slow debug build (more error detail)
#   .\watch-reel.ps1 release   build the finished app and open its folder instead
param([string]$Mode = 'profile')
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

if (-not (Get-Command flutter -ErrorAction SilentlyContinue)) {
    Write-Host "Flutter isn't installed (or isn't on PATH). See README.md." -ForegroundColor Yellow
    exit 1
}

if ($Mode -eq 'release') {
    flutter build windows --release
    if ($LASTEXITCODE -ne 0) { throw "Build failed, see the errors above." }
    explorer "build\windows\x64\runner\Release"
    exit 0
}

flutter pub get | Out-Null

$flag = if ($Mode -eq 'debug') { '' } else { '--profile' }
Write-Host "Starting Reel ($Mode). Watching lib\ for changes - it will restart on its own." -ForegroundColor Cyan
Write-Host "Press Ctrl+C here to stop." -ForegroundColor DarkGray

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = 'cmd.exe'
$psi.Arguments = "/c flutter run -d windows $flag"
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.WorkingDirectory = $PSScriptRoot
$proc = [System.Diagnostics.Process]::Start($psi)

# One restart at a time, and only once a burst of file writes has settled.
$script:last = [DateTime]::MinValue
$script:pending = $false

$watcher = New-Object System.IO.FileSystemWatcher
$watcher.Path = (Join-Path $PSScriptRoot 'lib')
$watcher.Filter = '*.dart'
$watcher.IncludeSubdirectories = $true
$watcher.EnableRaisingEvents = $true

$onChange = {
    $script:last = Get-Date
    $script:pending = $true
}
Register-ObjectEvent $watcher Changed -Action $onChange | Out-Null
Register-ObjectEvent $watcher Created -Action $onChange | Out-Null
Register-ObjectEvent $watcher Renamed -Action $onChange | Out-Null

try {
    while (-not $proc.HasExited) {
        Start-Sleep -Milliseconds 400
        if ($script:pending -and ((Get-Date) - $script:last).TotalMilliseconds -gt 1200) {
            $script:pending = $false
            Write-Host "`nChange spotted - restarting Reel..." -ForegroundColor Green
            try { $proc.StandardInput.WriteLine('R') } catch { }
        }
    }
} finally {
    Get-EventSubscriber | Unregister-Event -ErrorAction SilentlyContinue
    $watcher.Dispose()
    if (-not $proc.HasExited) { $proc.Kill() }
}
