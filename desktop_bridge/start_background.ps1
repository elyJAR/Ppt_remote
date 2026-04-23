# Start the PowerPoint Bridge in the background (hidden window)
# This script starts the bridge service without showing a console window

$scriptPath = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonScript = Join-Path $scriptPath "run_background.py"

# Check if Python is available
if (-not (Get-Command python -ErrorAction SilentlyContinue)) {
    Write-Error "Python is not installed or not in PATH"
    exit 1
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
    Write-Host "The service is now running on http://localhost:8787"
    Write-Host "To stop it, use stop_background.ps1 or Task Manager"
} catch {
    Write-Error "Failed to start bridge: $_"
    exit 1
}
