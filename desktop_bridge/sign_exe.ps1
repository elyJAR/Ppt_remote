# ============================================================
# sign_exe.ps1
# Self-signs PptRemoteBridge.exe using a locally generated
# self-signed certificate stored in the Windows certificate store.
#
# Run ONCE to create the cert, then run again to sign the EXE.
# The cert is valid for 10 years and stored in CurrentUser\My.
#
# NOTE: Self-signed certs will still show a SmartScreen warning
# on first run, but the EXE will be signed and verifiable.
# ============================================================

$ErrorActionPreference = "Stop"
$scriptPath = Split-Path -Parent $MyInvocation.MyCommand.Path
$exePath    = Join-Path $scriptPath "dist\PptRemoteBridge.exe"
$certSubject = "CN=PPT Remote, O=AntiGravity Projects"
$certStore   = "Cert:\CurrentUser\My"

# ── Step 1: Find or create the self-signed certificate ──────────────────────
$cert = Get-ChildItem $certStore | Where-Object { $_.Subject -eq $certSubject } | Select-Object -First 1

if ($cert) {
    Write-Host "Found existing certificate: $($cert.Thumbprint)"
} else {
    Write-Host "Creating new self-signed code-signing certificate..."
    $cert = New-SelfSignedCertificate `
        -Subject $certSubject `
        -CertStoreLocation $certStore `
        -KeyUsage DigitalSignature `
        -Type CodeSigningCert `
        -NotAfter (Get-Date).AddYears(10) `
        -HashAlgorithm SHA256
    Write-Host "Certificate created: $($cert.Thumbprint)"
}

# ── Step 2: Trust the certificate (add to Trusted Publishers + Root) ────────
$alreadyTrusted = Get-ChildItem "Cert:\CurrentUser\TrustedPublisher" |
    Where-Object { $_.Thumbprint -eq $cert.Thumbprint }

if (-not $alreadyTrusted) {
    Write-Host "Adding certificate to Trusted Publishers store..."
    $store = New-Object System.Security.Cryptography.X509Certificates.X509Store(
        "TrustedPublisher", "CurrentUser"
    )
    $store.Open("ReadWrite")
    $store.Add($cert)
    $store.Close()
    Write-Host "Certificate trusted for current user"
} else {
    Write-Host "Certificate already in Trusted Publishers"
}

# ── Step 3: Sign the EXE ────────────────────────────────────────────────────
if (-not (Test-Path $exePath)) {
    Write-Error "EXE not found at $exePath — run build_exe.ps1 first"
    exit 1
}

Write-Host "Signing $exePath ..."
$result = Set-AuthenticodeSignature `
    -FilePath $exePath `
    -Certificate $cert `
    -TimestampServer "http://timestamp.digicert.com" `
    -HashAlgorithm SHA256

if ($result.Status -eq "Valid") {
    Write-Host ""
    Write-Host "EXE signed successfully!"
    Write-Host "  Status    : $($result.Status)"
    Write-Host "  Signer    : $($result.SignerCertificate.Subject)"
    Write-Host "  Thumbprint: $($result.SignerCertificate.Thumbprint)"
} elseif ($result.Status -eq "UnknownError" -and $result.StatusMessage -match "timestamp") {
    # Timestamp server unreachable — sign without timestamp
    Write-Warning "Timestamp server unreachable — signing without timestamp"
    $result = Set-AuthenticodeSignature `
        -FilePath $exePath `
        -Certificate $cert `
        -HashAlgorithm SHA256
    Write-Host "EXE signed (no timestamp): $($result.Status)"
} else {
    Write-Error "Signing failed: $($result.Status) — $($result.StatusMessage)"
    exit 1
}

# ── Step 4: Verify ──────────────────────────────────────────────────────────
$verify = Get-AuthenticodeSignature -FilePath $exePath
Write-Host ""
Write-Host "Verification: $($verify.Status)"
Write-Host "To check: right-click PptRemoteBridge.exe -> Properties -> Digital Signatures"
