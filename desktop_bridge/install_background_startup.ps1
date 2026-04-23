# Install PowerPoint Bridge to start automatically in background on Windows login
# This creates a scheduled task that runs at user logon

$taskName = "PowerPointBridgeBackground"
$scriptPath = Split-Path -Parent $MyInvocation.MyCommand.Path
$startScript = Join-Path $scriptPath "start_background.ps1"

# Check if task already exists
$existingTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue

if ($existingTask) {
    Write-Host "Removing existing task..."
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
}

# Create the action
$action = New-ScheduledTaskAction -Execute "powershell.exe" `
    -Argument "-WindowStyle Hidden -ExecutionPolicy Bypass -File `"$startScript`""

# Create the trigger (at logon)
$trigger = New-ScheduledTaskTrigger -AtLogOn

# Create the principal (run as current user)
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive

# Create settings
$settings = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -RunOnlyIfNetworkAvailable

# Register the task
Register-ScheduledTask `
    -TaskName $taskName `
    -Action $action `
    -Trigger $trigger `
    -Principal $principal `
    -Settings $settings `
    -Description "Starts PowerPoint Bridge service in background at login"

Write-Host "PowerPoint Bridge background startup installed successfully!"
Write-Host "The service will start automatically when you log in to Windows."
Write-Host "To start it now, run: start_background.ps1"
