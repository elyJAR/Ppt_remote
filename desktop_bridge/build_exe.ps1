$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

function Invoke-CheckedPython([string]$Arguments) {
	python $Arguments
	if ($LASTEXITCODE -ne 0) {
		throw "Python command failed: python $Arguments"
	}
}

Invoke-CheckedPython "-m pip install -r requirements.txt"
Invoke-CheckedPython "-m pip install pyinstaller==6.15.0"
Invoke-CheckedPython "-m PyInstaller --noconfirm --onefile --noconsole --name PptRemoteBridge bridge_service.py"

$exePath = Join-Path $PSScriptRoot "dist\PptRemoteBridge.exe"
if (-not (Test-Path $exePath)) {
	throw "Build completed but EXE not found at $exePath"
}

Write-Host "Built: $exePath"
