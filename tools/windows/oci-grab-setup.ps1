# Fatbo VPNhub — Windows 一鍵：搶 Oracle 免費 ARM 機
# 自動裝 Python + oci SDK、整 SSH key、下載 grabber、開始搶。
# 唯一要人手做嘅係喺 Oracle 控制台整 API key（Oracle 規定，繞唔到）——
# 行到嗰步會自動開網頁、停低等你，做完撳 Enter 就繼續。

$ErrorActionPreference = "Stop"
function Say($m) { Write-Host "`n>>> $m" -ForegroundColor Cyan }
function Warn($m) { Write-Host "!!! $m" -ForegroundColor Yellow }

$GRAB_URL = "https://raw.githubusercontent.com/focsob/Fatbo-vpn-hub/main/tools/oci-grab-free-arm.py"

# ── 1. Python ───────────────────────────────────────────────
Say "檢查 Python ..."
$py = $null
foreach ($c in @("py", "python")) { if (Get-Command $c -ErrorAction SilentlyContinue) { $py = $c; break } }
if (-not $py) {
    Say "冇 Python，試用 winget 自動裝 ..."
    foreach ($id in @("Python.Python.3.12", "Python.Python.3.13", "Python.Python.3.11")) {
        try { winget install -e --id $id --accept-source-agreements --accept-package-agreements; break } catch {}
    }
    $env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
    foreach ($c in @("py", "python")) { if (Get-Command $c -ErrorAction SilentlyContinue) { $py = $c; break } }
}
if (-not $py) {
    Warn "自動裝唔到 Python。我開個下載頁，請裝（記得剔 Add python.exe to PATH），裝完重新 double-click 一次。"
    Start-Process "https://www.python.org/downloads/"
    Read-Host "撳 Enter 離開"; exit
}
Say "Python = $py"

# ── 2. oci SDK ──────────────────────────────────────────────
Say "安裝 oci SDK（第一次要一兩分鐘）..."
& $py -m pip install --upgrade pip 2>&1 | Out-Null
& $py -m pip install --upgrade oci

# ── 3. SSH key ──────────────────────────────────────────────
$sshDir = Join-Path $HOME ".ssh"
$keyPath = Join-Path $sshDir "id_ed25519"
$pubPath = "$keyPath.pub"
if (-not (Test-Path $pubPath)) {
    Say "整緊 SSH key（無密碼）..."
    if (-not (Test-Path $sshDir)) { New-Item -ItemType Directory -Path $sshDir | Out-Null }
    & ssh-keygen -t ed25519 -f $keyPath -N '""' -q
}
Say "SSH 公鑰 = $pubPath"

# ── 4. OCI API key / config（要人手喺控制台做）─────────────
$ociDir = Join-Path $HOME ".oci"
$ociCfg = Join-Path $ociDir "config"
if (-not (Test-Path $ociCfg)) {
    Warn "未搵到 $ociCfg —— 要你喺 Oracle 控制台整一個 API key："
    Write-Host ""
    Write-Host "  1) 右上角頭像 -> 'My profile'（或 Identity -> 你個 user）-> 'API keys' -> 'Add API key'"
    Write-Host "  2) 揀 'Generate API key pair' -> 'Download private key'（存落 $ociDir）-> Add"
    Write-Host "  3) 佢會彈出一段 config，撳 'Copy' -> 貼入檔案  $ociCfg"
    Write-Host "  4) 把 config 入面  key_file=  嗰行改成你頭先下載嗰個 .pem 嘅完整路徑"
    Write-Host ""
    if (-not (Test-Path $ociDir)) { New-Item -ItemType Directory -Path $ociDir | Out-Null }
    Start-Process "https://cloud.oracle.com/"
    do {
        Read-Host "整好 $ociCfg 之後，撳 Enter 繼續"
    } until (Test-Path $ociCfg)
}
Say "搵到 OCI config"

# ── 5. 揀機 size ────────────────────────────────────────────
Write-Host ""
Write-Host "搶邊款？ A = 4 OCPU / 24 GB（最強，最難搶）   B = 1 OCPU / 6 GB（易搶，做出口夠用）"
$pick = Read-Host "輸入 A 或 B（預設 A）"
if ($pick -match "^[Bb]") { $env:OCI_OCPUS = "1"; $env:OCI_MEM_GB = "6" } else { $env:OCI_OCPUS = "4"; $env:OCI_MEM_GB = "24" }

# ── 6. 下載 grabber + 跑 ────────────────────────────────────
$grab = Join-Path $PSScriptRoot "oci-grab-free-arm.py"
try {
    Say "下載最新 grabber ..."
    Invoke-WebRequest -UseBasicParsing $GRAB_URL -OutFile $grab
} catch {
    if (-not (Test-Path $grab)) { Warn "下載唔到，而且本機都冇 grabber。檢查網絡後再試。"; Read-Host "Enter 離開"; exit }
    Warn "下載唔到，用返本機舊版。"
}

Say "開始搶（$($env:OCI_OCPUS) OCPU / $($env:OCI_MEM_GB) GB）。搶到會自己停並印出 IP。Ctrl-C 可停。"
Write-Host "提示：唔好閂呢個窗；去『設定 -> 電源』校『永不睡眠』(插住電)，等佢可以一直試。" -ForegroundColor DarkGray
& $py -X utf8 $grab
Read-Host "`n完咗。撳 Enter 離開"
