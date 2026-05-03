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
# Build using the spec file to ensure hiddenimports and other configs are applied
python -m PyInstaller --noconfirm PptRemoteBridge.spec
if ($LASTEXITCODE -ne 0) {
	throw "PyInstaller build failed"
}

$exePath = Join-Path $PSScriptRoot "dist\PptRemoteBridge\PptRemoteBridge.exe"
if (-not (Test-Path $exePath)) {
	throw "Build completed but EXE not found at $exePath"
}

Write-Host "Built: $exePath"
