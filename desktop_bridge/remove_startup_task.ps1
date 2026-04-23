$ErrorActionPreference = "Stop"

$taskName = "PptRemoteBridge"
if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    Write-Host "Removed task '$taskName'."
} else {
    Write-Host "Task '$taskName' not found."
}
