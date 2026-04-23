$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$taskName = "PptRemoteBridge"
$exePath = Join-Path $PSScriptRoot "dist\PptRemoteBridge.exe"

if (-not (Test-Path $exePath)) {
    throw "EXE not found at $exePath. Run .\build_exe.ps1 first."
}

$action = New-ScheduledTaskAction -Execute $exePath
$trigger = New-ScheduledTaskTrigger -AtLogOn
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable

if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
}

Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Description "Starts PPT Remote bridge in background at logon"
Start-ScheduledTask -TaskName $taskName

Write-Host "Startup task '$taskName' installed and started."
