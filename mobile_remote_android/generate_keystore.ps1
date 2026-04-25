# ============================================================
# generate_keystore.ps1
# Generates a self-signed Android release keystore.
# Run this ONCE locally, then add the outputs as GitHub secrets.
# ============================================================

param(
    [string]$KeyAlias      = "pptremote",
    [string]$StorePassword = "pptremote2025",
    [string]$KeyPassword   = "pptremote2025",
    [string]$OutputFile    = "pptremote-release.jks",
    [string]$Dname         = "CN=PPT Remote, OU=AntiGravity Projects, O=AntiGravity, L=Unknown, ST=Unknown, C=US",
    [int]   $Validity      = 10000
)

# Find keytool
$keytool = $null
$candidates = @(
    "$env:JAVA_HOME\bin\keytool.exe",
    (Get-Command keytool -ErrorAction SilentlyContinue)?.Source
) + (Get-ChildItem "C:\Program Files" -Recurse -Filter "keytool.exe" -ErrorAction SilentlyContinue -Depth 8 | Select-Object -First 1 -ExpandProperty FullName)

foreach ($c in $candidates) {
    if ($c -and (Test-Path $c)) { $keytool = $c; break }
}

if (-not $keytool) {
    Write-Error "keytool not found. Install JDK 17+ and ensure it is on PATH."
    exit 1
}

Write-Host "Using keytool: $keytool"

# Generate keystore
& $keytool -genkeypair `
    -alias $KeyAlias `
    -keyalg RSA `
    -keysize 2048 `
    -validity $Validity `
    -keystore $OutputFile `
    -storepass $StorePassword `
    -keypass $KeyPassword `
    -dname $Dname `
    -noprompt

if ($LASTEXITCODE -ne 0) {
    Write-Error "keytool failed"
    exit 1
}

# Base64-encode for GitHub secret
$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path $OutputFile)))

Write-Host ""
Write-Host "============================================================"
Write-Host "  Keystore generated: $OutputFile"
Write-Host "============================================================"
Write-Host ""
Write-Host "Add these as GitHub repository secrets:"
Write-Host "  Settings -> Secrets and variables -> Actions -> New secret"
Write-Host ""
Write-Host "  KEYSTORE_BASE64   = (see below)"
Write-Host "  KEYSTORE_PASSWORD = $StorePassword"
Write-Host "  KEY_ALIAS         = $KeyAlias"
Write-Host "  KEY_PASSWORD      = $KeyPassword"
Write-Host ""
Write-Host "--- KEYSTORE_BASE64 (copy everything between the lines) ---"
Write-Host $base64
Write-Host "-----------------------------------------------------------"
Write-Host ""
Write-Host "IMPORTANT: Do NOT commit $OutputFile to git!"
Write-Host "           It is already in .gitignore."
