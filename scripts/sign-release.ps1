# Post-release Windows code-signing for DiskSpace.
#
# What it does:
#   1. Downloads the unsigned bare DiskSpace.exe from a published GitHub release.
#   2. Signs it with our Certum SimplySign-backed cert.
#   3. Builds the Inno Setup installer locally, wrapping the *signed* bare exe.
#   4. Signs the installer.
#   5. Uploads bare exe (clobbering the unsigned one) and the new installer.
#
# Why the installer is built locally rather than in CI: if Inno Setup wraps the
# unsigned bare exe (as it would in CI, where signing hasn't happened yet),
# users running the installer end up with an unsigned binary in Program Files --
# which AV/SmartScreen rescans can still flag as scary, defeating the point of
# signing. Building the installer here, around an already-signed binary, means
# the installed file on the user's machine is signed too.
#
# Why signing isn't in CI at all: Certum's cloud signing requires SimplySign
# Desktop + an OTP push from the mobile app, with no clean unattended path on
# GitHub-hosted runners (GUI-driven; the container path needs VNC tunnelling).
# For DiskSpace's monthly-ish cadence, signing on the maintainer's machine is
# simpler and more reliable than wrestling SimplySign through CI.
#
# Preconditions:
#   - SimplySign Desktop logged in (cert visible in Cert:\CurrentUser\My).
#   - Inno Setup 6 installed (default path: C:\Program Files (x86)\Inno Setup 6).
#   - gh CLI authenticated against thegreystone/diskspace.
#   - Windows SDK signtool.exe present (auto-detected under Windows Kits 10).
#   - $env:DISKSPACE_SIGN_THUMBPRINT set to the SHA1 thumbprint of the Certum
#     cert (or -Thumbprint passed). The thumbprint is NOT secret -- it's
#     readable from any signed binary -- but lives in your $PROFILE rather than
#     this file so cert renewal next year is a one-line edit, not a repo commit.

[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Tag,

    [string]$Thumbprint = $env:DISKSPACE_SIGN_THUMBPRINT,

    [string]$SignTool,

    [string]$Iscc
)

$ErrorActionPreference = 'Stop'

# Run from the repo root no matter where the user invoked the script from,
# so the Inno Setup script can find ..\LICENSE and ..\src\windows\assets\icon.ico.
Set-Location (Join-Path $PSScriptRoot '..')

if (-not $Thumbprint) {
    throw "No thumbprint. Set `$env:DISKSPACE_SIGN_THUMBPRINT in your `$PROFILE or pass -Thumbprint."
}

if (-not $SignTool) {
    $SignTool = Get-ChildItem 'C:\Program Files (x86)\Windows Kits\10\bin' -Recurse -Filter signtool.exe -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\x64\\' } |
        Sort-Object FullName -Descending |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $SignTool) {
        throw "signtool.exe not found under 'C:\Program Files (x86)\Windows Kits\10\bin'. Install the Windows SDK or pass -SignTool."
    }
}

if (-not $Iscc) {
    # winget's `JRSoftware.InnoSetup` installs per-user under %LOCALAPPDATA%\Programs;
    # the official MSI installer puts it system-wide under Program Files (x86). Try both.
    $isccCandidates = @(
        'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
        "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe"
    )
    $Iscc = $isccCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $Iscc) {
        $cmd = Get-Command iscc -ErrorAction SilentlyContinue
        if ($cmd) { $Iscc = $cmd.Source }
    }
    if (-not $Iscc) {
        throw "Inno Setup compiler not found. Install Inno Setup 6 (winget install JRSoftware.InnoSetup, or https://jrsoftware.org/isdl.php) or pass -Iscc."
    }
}
elseif (-not (Test-Path $Iscc)) {
    throw "Inno Setup compiler not found at '$Iscc'."
}

# Verify SimplySign Desktop is actually authenticated before we download anything.
$cert = Get-ChildItem Cert:\CurrentUser\My | Where-Object { $_.Thumbprint -eq $Thumbprint }
if (-not $cert) {
    throw "Cert $Thumbprint not found in Cert:\CurrentUser\My. Open SimplySign Desktop and log in (OTP from your mobile app), then retry."
}

$version = $Tag -replace '^v',''
$bareName = "diskspace-$version-windows-x86_64.exe"
$setupName = "diskspace-$version-windows-x86_64-setup.exe"

$workdir = New-Item -ItemType Directory -Path (Join-Path $env:TEMP "diskspace-sign-$Tag-$(Get-Random)") -Force
Write-Host "Working in: $workdir"

# Helper: sign $path with our pinned cert, then verify Status=Valid + a non-null
# timestamp. Fails loud if anything is off -- better than silently shipping an
# unsigned or untimestamped binary.
function Invoke-SignAndVerify([string]$path, [string]$label) {
    Write-Host ""
    Write-Host "Signing $label..."
    & $SignTool sign /tr http://time.certum.pl /td sha256 /fd sha256 /sha1 $Thumbprint $path
    if ($LASTEXITCODE -ne 0) {
        throw "signtool failed for $label (exit $LASTEXITCODE)"
    }

    $sig = Get-AuthenticodeSignature $path
    if ($sig.Status -ne 'Valid') {
        throw "Signature on $label is $($sig.Status): $($sig.StatusMessage)"
    }
    if (-not $sig.TimeStamperCertificate) {
        throw "Signature on $label has no timestamp -- without it, the signature expires when the cert does."
    }
    Write-Host "  Subject:    $($sig.SignerCertificate.Subject)"
    Write-Host "  Timestamp:  $($sig.TimeStamperCertificate.Subject)"
}

try {
    # ── 1. Download unsigned bare exe ────────────────────────────────────
    Write-Host "Downloading unsigned $bareName from release $Tag..."
    gh release download $Tag --pattern $bareName --dir $workdir
    if ($LASTEXITCODE -ne 0) {
        throw "gh release download failed for $bareName (exit $LASTEXITCODE)"
    }
    $bareDownload = Join-Path $workdir $bareName

    # ── 2. Sign the bare exe ─────────────────────────────────────────────
    Invoke-SignAndVerify $bareDownload $bareName

    # ── 3. Build the installer locally around the signed bare exe ────────
    # Inno Setup expects diskspace.exe sitting next to the .iss file (see
    # installer/diskspace.iss [Files] section).
    Write-Host ""
    Write-Host "Building Inno Setup installer wrapping the signed binary..."
    Copy-Item -Force $bareDownload 'installer/diskspace.exe'
    & $Iscc "/DMyVersion=$version" 'installer/diskspace.iss'
    if ($LASTEXITCODE -ne 0) {
        throw "Inno Setup compiler failed (exit $LASTEXITCODE)"
    }
    $setupBuilt = "installer/Output/$setupName"
    if (-not (Test-Path $setupBuilt)) {
        throw "Inno Setup ran but '$setupBuilt' wasn't produced -- check ISS output above."
    }

    # ── 4. Sign the installer ────────────────────────────────────────────
    Invoke-SignAndVerify $setupBuilt $setupName

    # ── 5. Upload both back to the release (--clobber overwrites unsigned bare) ─
    Write-Host ""
    Write-Host "Uploading signed artifacts back to release $Tag..."
    gh release upload $Tag $bareDownload --clobber
    if ($LASTEXITCODE -ne 0) {
        throw "gh release upload failed for $bareName (exit $LASTEXITCODE)"
    }
    gh release upload $Tag $setupBuilt --clobber
    if ($LASTEXITCODE -ne 0) {
        throw "gh release upload failed for $setupName (exit $LASTEXITCODE)"
    }

    Write-Host ""
    Write-Host "Done. Release $Tag now has:" -ForegroundColor Green
    Write-Host "  - $bareName  (signed)"
    Write-Host "  - $setupName  (signed, wrapping signed binary)"
}
finally {
    Remove-Item -Recurse -Force $workdir -ErrorAction SilentlyContinue
    # Leave installer/diskspace.exe and installer/Output in place: they're
    # .gitignored build artifacts and useful for inspection if something failed.
}
