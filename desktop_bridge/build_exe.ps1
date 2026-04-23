$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

python -m pip install -r requirements.txt
python -m pip install pyinstaller==6.15.0

python -m PyInstaller --noconfirm --onefile --noconsole --name PptRemoteBridge bridge_service.py

Write-Host "Built: $PSScriptRoot\dist\PptRemoteBridge.exe"
