# Start the PowerPoint Bridge in the background (hidden window)
# This script starts the bridge service without showing a console window

$scriptPath = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonScript = Join-Path $scriptPath "run_background.py"

# ── Guard: don't start a second instance ─────────────────────────────────────
$pidFile = Join-Path $scriptPath ".bridge.pid"

if (Test-Path $pidFile) {
    $existingPid = (Get-Content $pidFile -Raw).Trim()
    if ($existingPid -and (Get-Process -Id $existingPid -ErrorAction SilentlyContinue)) {
        Write-Host "PowerPoint Bridge is already running (PID: $existingPid). Skipping start."
        exit 0
    }
    # Stale PID file — remove it
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

# Also check by process name as a fallback
$running = Get-Process -Name "pythonw" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -like "*run_background*" -or $_.MainModule.FileName -like "*pythonw*" } |
    Select-Object -First 1

# Simpler fallback: check if port 8787 is already in use
$portInUse = $null
try {
    $portInUse = Get-NetTCPConnection -LocalPort 8787 -State Listen -ErrorAction SilentlyContinue
} catch {}

if ($portInUse) {
    Write-Host "Port 8787 is already in use — bridge appears to be running. Skipping start."
    exit 0
}
# ─────────────────────────────────────────────────────────────────────────────

# Check if Python is available
if (-not (Get-Command pythonw -ErrorAction SilentlyContinue)) {
    # Try python as fallback
    if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
        Write-Error "Python is not installed or not in PATH"
        exit 1
    }
}

# Start the process hidden
$processInfo = New-Object System.Diagnostics.ProcessStartInfo
$processInfo.FileName = "pythonw.exe"  # pythonw.exe runs without console window
$processInfo.Arguments = "`"$pythonScript`""
$processInfo.WorkingDirectory = $scriptPath
$processInfo.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
$processInfo.CreateNoWindow = $true

try {
    $process = [System.Diagnostics.Process]::Start($processInfo)
    Write-Host "PowerPoint Bridge started in background (PID: $($process.Id))"
    # Save PID for stop_background.ps1
    $pidFile = Join-Path $scriptPath ".bridge.pid"
    $process.Id | Out-File -FilePath $pidFile -Encoding ascii
    Write-Host "The service is now running on http://localhost:8787"
    Write-Host "To stop it, use stop_background.ps1 or Task Manager"
} catch {
    Write-Error "Failed to start bridge: $_"
    exit 1
}
