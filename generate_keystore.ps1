# generate_keystore.ps1
# Run this ONCE to create a stable release keystore.
# Then add the three outputs as GitHub Secrets so every CI build uses the same key.

$ErrorActionPreference = "Stop"

$keystorePath  = "$PSScriptRoot\release.jks"
$keystoreAlias = "pptremote"
$password      = "pptremote-release-2024"   # change this to something private

if (Test-Path $keystorePath) {
    Write-Host "Keystore already exists at $keystorePath — skipping generation." -ForegroundColor Yellow
} else {
    Write-Host "Generating release keystore..."
    & keytool -genkeypair `
        -alias $keystoreAlias `
        -keyalg RSA `
        -keysize 2048 `
        -validity 36500 `
        -storetype JKS `
        -keystore $keystorePath `
        -storepass $password `
        -keypass  $password `
        -dname "CN=PPT Remote, OU=AntiGravity, O=AntiGravity, L=Unknown, ST=Unknown, C=US" `
        -noprompt
    Write-Host "Keystore created." -ForegroundColor Green
}

$base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))

Write-Host ""
Write-Host "══════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host "  Add these 3 values as GitHub Secrets in your repository:"   -ForegroundColor Cyan
Write-Host "  Settings → Secrets and variables → Actions → New secret"    -ForegroundColor Cyan
Write-Host "══════════════════════════════════════════════════════════════" -ForegroundColor Cyan
Write-Host ""
Write-Host "Secret name : KEYSTORE_BASE64"  -ForegroundColor Yellow
Write-Host "Secret value: (see file below)" -ForegroundColor White
$base64 | Set-Clipboard
Write-Host "  → Copied to clipboard! Paste it as the secret value."       -ForegroundColor Green
$base64 | Out-File -FilePath "$PSScriptRoot\keystore_base64.txt" -Encoding ascii
Write-Host "  → Also saved to: keystore_base64.txt"                       -ForegroundColor Green
Write-Host ""
Write-Host "Secret name : KEYSTORE_PASSWORD"  -ForegroundColor Yellow
Write-Host "Secret value: $password"           -ForegroundColor White
Write-Host ""
Write-Host "Secret name : KEY_ALIAS"         -ForegroundColor Yellow
Write-Host "Secret value: $keystoreAlias"    -ForegroundColor White
Write-Host ""
Write-Host "Secret name : KEY_PASSWORD"      -ForegroundColor Yellow
Write-Host "Secret value: $password"         -ForegroundColor White
Write-Host ""
Write-Host "Keep release.jks and keystore_base64.txt safe — do NOT commit them to git!" -ForegroundColor Red
