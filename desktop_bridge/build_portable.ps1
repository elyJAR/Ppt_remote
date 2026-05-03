$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$distDir = Join-Path $PSScriptRoot "dist\PptRemoteBridge"
$zipFile = Join-Path $PSScriptRoot "dist\PptRemoteBridge_Portable.zip"

if (-not (Test-Path $distDir)) {
    throw "Build folder not found at $distDir. Run build_exe.ps1 first."
}

Write-Host "Creating Portable ZIP: $zipFile"
if (Test-Path $zipFile) {
    Remove-Item $zipFile
}

Compress-Archive -Path "$distDir\*" -DestinationPath $zipFile -Force

Write-Host "Portable build created successfully!"
