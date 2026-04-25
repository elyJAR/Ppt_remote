$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

# ── Step 1: Build the EXE ────────────────────────────────────────────────────
Write-Host "Building EXE..."
.\build_exe.ps1

# ── Step 2: Find Inno Setup compiler ────────────────────────────────────────
$iscc = $null
$candidates = @(
    "C:\Program Files (x86)\Inno Setup 6\ISCC.exe",
    "C:\Program Files\Inno Setup 6\ISCC.exe",
    (Get-Command iscc -ErrorAction SilentlyContinue)?.Source
)
foreach ($c in $candidates) {
    if ($c -and (Test-Path $c)) { $iscc = $c; break }
}

if (-not $iscc) {
    Write-Error @"
Inno Setup not found. Install it from https://jrsoftware.org/isinfo.php
then re-run this script.
"@
    exit 1
}

Write-Host "Using Inno Setup: $iscc"

# ── Step 3: Compile the installer ───────────────────────────────────────────
Write-Host "Compiling installer..."
& $iscc installer.iss
if ($LASTEXITCODE -ne 0) { throw "Inno Setup compilation failed" }

$setupExe = Join-Path $PSScriptRoot "dist\PptRemoteBridgeSetup.exe"
if (-not (Test-Path $setupExe)) {
    throw "Installer not found at $setupExe after compilation"
}

Write-Host ""
Write-Host "Installer built: $setupExe"
Write-Host "Distribute this single file — no Python required on the target PC."
