$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "Installing dependencies..."
python -m pip install -r requirements.txt
if ($LASTEXITCODE -ne 0) {
	throw "Failed to install requirements"
}

Write-Host "Installing PyInstaller..."
python -m pip install pyinstaller==6.15.0
if ($LASTEXITCODE -ne 0) {
	throw "Failed to install PyInstaller"
}

Write-Host "Building executable with PyInstaller..."
# Build with console enabled - we'll hide it using start_background.ps1 instead
python -m PyInstaller --noconfirm --onefile --console --name PptRemoteBridge --version-file version_info.txt bridge_service.py
if ($LASTEXITCODE -ne 0) {
	throw "PyInstaller build failed"
}

$exePath = Join-Path $PSScriptRoot "dist\PptRemoteBridge.exe"
if (-not (Test-Path $exePath)) {
	throw "Build completed but EXE not found at $exePath"
}

Write-Host "Built: $exePath"
