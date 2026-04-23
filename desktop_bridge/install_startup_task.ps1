[CmdletBinding()]
param(
    [ValidateSet("Auto", "Exe", "Python")]
    [string]$Mode = "Auto"
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$taskName = "PptRemoteBridge"
$exePath = Join-Path $PSScriptRoot "dist\PptRemoteBridge.exe"
$scriptPath = Join-Path $PSScriptRoot "bridge_service.py"

function Get-PythonLaunchAction {
    $pythonExe = (python -c "import sys; print(sys.executable)").Trim()
    if (-not $pythonExe) {
        throw "Could not determine python executable."
    }

    $pythonwExe = $pythonExe -replace "python\.exe$", "pythonw.exe"
    if (-not (Test-Path $pythonwExe)) {
        $pythonwExe = $pythonExe
    }

    return New-ScheduledTaskAction -Execute $pythonwExe -Argument "`"$scriptPath`""
}

$action = $null
if ($Mode -eq "Exe") {
    if (-not (Test-Path $exePath)) {
        throw "EXE not found at $exePath. Run .\build_exe.ps1 first or use -Mode Python."
    }
    $action = New-ScheduledTaskAction -Execute $exePath
} elseif ($Mode -eq "Python") {
    $action = Get-PythonLaunchAction
} else {
    if (Test-Path $exePath) {
        $action = New-ScheduledTaskAction -Execute $exePath
    } else {
        $action = Get-PythonLaunchAction
    }
}

$trigger = New-ScheduledTaskTrigger -AtLogOn
$currentUser = "$env:USERDOMAIN\$env:USERNAME"
$principal = New-ScheduledTaskPrincipal -UserId $currentUser -LogonType Interactive -RunLevel Limited
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable

if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
}

try {
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Description "Starts PPT Remote bridge in background at logon"
    Start-ScheduledTask -TaskName $taskName
    Write-Host "Startup task '$taskName' installed and started."
} catch {
    # Fallback for environments where scheduled task registration is restricted.
    $runKeyPath = "HKCU:\Software\Microsoft\Windows\CurrentVersion\Run"
    $runValueName = "PptRemoteBridge"

    if ($action.Arguments) {
        $commandLine = "`"$($action.Execute)`" $($action.Arguments)"
    } else {
        $commandLine = "`"$($action.Execute)`""
    }

    New-Item -Path $runKeyPath -Force | Out-Null
    Set-ItemProperty -Path $runKeyPath -Name $runValueName -Value $commandLine
    Write-Host "Scheduled task registration failed; installed HKCU startup entry '$runValueName' instead."
}
