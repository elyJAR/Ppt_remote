# Remove PowerPoint Bridge from automatic startup

$taskName = "PowerPointBridgeBackground"

$existingTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue

if ($existingTask) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    Write-Host "PowerPoint Bridge background startup removed successfully"
} else {
    Write-Host "No background startup task found"
}

Write-Host "Note: This does not stop currently running instances."
Write-Host "To stop the service, run: stop_background.ps1"
