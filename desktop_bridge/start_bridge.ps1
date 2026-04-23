$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

python -m pip install -r requirements.txt
python -m uvicorn main:app --host 0.0.0.0 --port 8787
