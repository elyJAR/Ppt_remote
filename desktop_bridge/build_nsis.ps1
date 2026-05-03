$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

Write-Host "Checking for NSIS (makensis)..."
$makensisPath = $null

# Try PATH first
$inPath = Get-Command makensis -ErrorAction SilentlyContinue
if ($inPath) {
    $makensisPath = $inPath.Source
}
else {
    # Fall back to default NSIS install locations
    $defaultPaths = @(
        "C:\Program Files (x86)\NSIS\makensis.exe",
        "C:\Program Files\NSIS\makensis.exe"
    )
    foreach ($p in $defaultPaths) {
        if (Test-Path $p) {
            $makensisPath = $p
            break
        }
    }
}

if (-not $makensisPath) {
    Write-Warning "NSIS (makensis.exe) not found. Download from: https://nsis.sourceforge.io/Download"
    exit 1
}

Write-Host "Found makensis at: $makensisPath"
Write-Host "Building NSIS Installer..."
& $makensisPath installer.nsi

if ($LASTEXITCODE -eq 0) {
    Write-Host "NSIS Installer built: dist\PptRemoteBridgeSetup_NSIS.exe"
}
else {
    throw "NSIS build failed"
}
