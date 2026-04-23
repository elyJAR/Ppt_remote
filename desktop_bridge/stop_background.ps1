# Stop all PowerPoint Bridge background processes

Write-Host "Stopping PowerPoint Bridge processes..."

# Find and stop all python processes running the bridge
$processes = Get-Process -Name "python*" -ErrorAction SilentlyContinue | Where-Object {
    $_.CommandLine -like "*run_background.py*" -or 
    $_.CommandLine -like "*bridge_service.py*" -or
    $_.CommandLine -like "*main.py*"
}

if ($processes) {
    foreach ($proc in $processes) {
        Write-Host "Stopping process $($proc.Id)..."
        Stop-Process -Id $proc.Id -Force
    }
    Write-Host "PowerPoint Bridge stopped successfully"
} else {
    Write-Host "No PowerPoint Bridge processes found running"
}
