# Fatbo VPNhub - Windows one-click: grab a free Oracle ARM instance.
# Auto: install Python + oci SDK, make an SSH key, download grabber, start grabbing.
# The ONLY manual step is creating an API key in the Oracle Console (Oracle requires it).
# (Pure ASCII on purpose, so PowerShell on any Windows locale parses it correctly.)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
function Say($m) { Write-Host ""; Write-Host ">>> $m" -ForegroundColor Cyan }
function Warn($m) { Write-Host "!!! $m" -ForegroundColor Yellow }

$GRAB_URL = "https://raw.githubusercontent.com/focsob/Fatbo-vpn-hub/main/tools/oci-grab-free-arm.py"

# 1. Python ---------------------------------------------------------------
Say "Checking Python ..."
$py = $null
foreach ($c in @("py", "python")) { if (Get-Command $c -ErrorAction SilentlyContinue) { $py = $c; break } }
if (-not $py) {
    Say "No Python found. Installing via winget ..."
    foreach ($id in @("Python.Python.3.12", "Python.Python.3.13", "Python.Python.3.11")) {
        try { winget install -e --id $id --accept-source-agreements --accept-package-agreements; break } catch {}
    }
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
    foreach ($c in @("py", "python")) { if (Get-Command $c -ErrorAction SilentlyContinue) { $py = $c; break } }
}
if (-not $py) {
    Warn "Could not install Python automatically. Opening python.org - install it (tick 'Add python.exe to PATH'), then run this again."
    Start-Process "https://www.python.org/downloads/"
    Read-Host "Press Enter to exit"; exit
}
Say "Python = $py"

# 2. oci SDK (also pulls in 'cryptography', used for the key below) --------
Say "Installing oci SDK (first time takes a minute or two) ..."
& $py -m pip install --upgrade pip 2>&1 | Out-Null
& $py -m pip install --upgrade oci

# 3. SSH key (generated with Python = reliable, no passphrase, no prompts) -
$sshDir = Join-Path $HOME ".ssh"
$keyPath = Join-Path $sshDir "id_ed25519"
$pubPath = "$keyPath.pub"
if (-not (Test-Path $pubPath)) {
    Say "Creating SSH key ..."
    if (-not (Test-Path $sshDir)) { New-Item -ItemType Directory -Path $sshDir | Out-Null }
    $kg = Join-Path $env:TEMP "fatbo_keygen.py"
    $code = @'
import sys
from cryptography.hazmat.primitives.asymmetric import ed25519
from cryptography.hazmat.primitives import serialization as s
priv_path, pub_path = sys.argv[1], sys.argv[2]
k = ed25519.Ed25519PrivateKey.generate()
open(priv_path, "wb").write(k.private_bytes(s.Encoding.PEM, s.PrivateFormat.OpenSSH, s.NoEncryption()))
open(pub_path, "wb").write(k.public_key().public_bytes(s.Encoding.OpenSSH, s.PublicFormat.OpenSSH) + b" fatbo\n")
print("key created")
'@
    Set-Content -Path $kg -Value $code -Encoding ASCII
    & $py $kg $keyPath $pubPath
    Remove-Item $kg -ErrorAction SilentlyContinue
}
Say "SSH public key = $pubPath"

# 4. OCI config (manual: create an API key in the console) ----------------
$ociDir = Join-Path $HOME ".oci"
$ociCfg = Join-Path $ociDir "config"
if (-not (Test-Path $ociCfg)) {
    Warn "No OCI config yet. You must create an API key in the Oracle Console:"
    Write-Host "  1) Top-right avatar -> 'My profile' (or Identity -> your user) -> 'API keys' -> 'Add API key'"
    Write-Host "  2) 'Generate API key pair' -> 'Download private key' (save it into:  $ociDir )  -> Add"
    Write-Host "  3) It shows a config block -> click Copy -> paste it into this file:  $ociCfg"
    Write-Host "  4) In that config, set  key_file=  to the FULL path of the .pem you just downloaded."
    if (-not (Test-Path $ociDir)) { New-Item -ItemType Directory -Path $ociDir | Out-Null }
    Start-Process "https://cloud.oracle.com/"
    do { Read-Host "After you saved the config file, press Enter to continue" } until (Test-Path $ociCfg)
}
Say "Found OCI config"

# 5. Pick shape size ------------------------------------------------------
Write-Host ""
Write-Host "Which shape?   A = 4 OCPU / 24 GB (max, hardest to get)    B = 1 OCPU / 6 GB (easy, plenty for an exit)"
$pick = Read-Host "Enter A or B (default A)"
if ($pick -match "^[Bb]") { $env:OCI_OCPUS = "1"; $env:OCI_MEM_GB = "6" } else { $env:OCI_OCPUS = "4"; $env:OCI_MEM_GB = "24" }

# 6. Download grabber + run ----------------------------------------------
$grab = Join-Path $PSScriptRoot "oci-grab-free-arm.py"
try {
    Say "Downloading the grabber ..."
    Invoke-WebRequest -UseBasicParsing $GRAB_URL -OutFile $grab
} catch {
    if (-not (Test-Path $grab)) { Warn "Download failed and no local copy found. Check network and retry."; Read-Host "Press Enter to exit"; exit }
    Warn "Download failed; using the local copy."
}

Say "Grabbing $($env:OCI_OCPUS) OCPU / $($env:OCI_MEM_GB) GB. It stops and prints the SSH line when it lands. Ctrl-C to stop."
Write-Host "Tip: keep this window open; set Power to 'Never sleep' (plugged in) so it can keep retrying." -ForegroundColor DarkGray
& $py -X utf8 $grab
Read-Host "Done. Press Enter to exit"
