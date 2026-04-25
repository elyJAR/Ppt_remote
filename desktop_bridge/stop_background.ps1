# Stop all PowerPoint Bridge background processes
Write-Host "Stopping PowerPoint Bridge processes..."

$scriptPath = Split-Path -Parent $MyInvocation.MyCommand.Path
$pidFile = Join-Path $scriptPath ".bridge.pid"

$stopped = $false

# Method 1: Use saved PID file (most reliable)
if (Test-Path $pidFile) {
    $savedPid = Get-Content $pidFile -ErrorAction SilentlyContinue
    if ($savedPid -match '^\d+$') {
        $proc = Get-Process -Id ([int]$savedPid) -ErrorAction SilentlyContinue
        if ($proc) {
            Write-Host "Stopping process $savedPid (from PID file)..."
            Stop-Process -Id ([int]$savedPid) -Force -ErrorAction SilentlyContinue
            $stopped = $true
        }
    }
    Remove-Item $pidFile -Force -ErrorAction SilentlyContinue
}

# Method 2: Use WMI to find by CommandLine (works without admin for own processes)
$wmiProcs = Get-WmiObject Win32_Process -Filter "Name LIKE 'python%.exe'" -ErrorAction SilentlyContinue |
    Where-Object {
        $_.CommandLine -like "*run_background.py*" -or
        $_.CommandLine -like "*bridge_service.py*" -or
        $_.CommandLine -like "*PptRemoteBridge*"
    }

if ($wmiProcs) {
    foreach ($p in $wmiProcs) {
        Write-Host "Stopping process $($p.ProcessId) via WMI..."
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
        $stopped = $true
    }
}

# Method 3: Kill PptRemoteBridge.exe by name if running
$exeProcs = Get-Process -Name "PptRemoteBridge" -ErrorAction SilentlyContinue
if ($exeProcs) {
    foreach ($proc in $exeProcs) {
        Write-Host "Stopping PptRemoteBridge.exe (PID: $($proc.Id))..."
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        $stopped = $true
    }
}

if ($stopped) {
    Write-Host "PowerPoint Bridge stopped successfully."
} else {
    Write-Host "No PowerPoint Bridge processes found running."
}
