param(
  [Parameter(Mandatory = $true, ParameterSetName = "Text")]
  [string]$Text,

  # Generate + use ADB Wireless Debugging pairing QR (in terminal) using 3rd-party tools.
  # Prefers `adb-wireless` (cargo) then `adb-wifi` / `adb-wifi-py` (pip).
  [Parameter(Mandatory = $true, ParameterSetName = "AdbQr")]
  [switch]$AdbQr,

  [Parameter(Mandatory = $false)]
  [string]$OutFile = ".\vbc-pack\out\vbc-qr.png",

  [Parameter(Mandatory = $false)]
  [switch]$NoOpen
)

$ErrorActionPreference = "Stop"

function Require-Command([string]$name) {
  if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
    throw "Missing command '$name'."
  }
}

function Have-Command([string]$name) {
  return [bool](Get-Command $name -ErrorAction SilentlyContinue)
}

if ($PSCmdlet.ParameterSetName -eq "AdbQr") {
  # Pair using QR code (tool prints QR in terminal and drives pairing).
  if (Have-Command "adb-wireless") {
    Write-Host "Using: adb-wireless pair"
    & adb-wireless pair
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    exit 0
  }

  if (Have-Command "adb-wifi") {
    Write-Host "Using: adb-wifi"
    & adb-wifi
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    exit 0
  }

  # Some installs expose it as a python module.
  if (Have-Command "python") {
    Write-Host "Trying: python -m adb_wifi"
    & python -m adb_wifi
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    exit 0
  }

  throw @"
No ADB QR tool found.

Install one of:
  - adb-wireless (Rust/cargo):  cargo install adb-wireless
  - adb-wifi-py  (Python/pip):  python -m pip install adb-wifi-py

Then rerun:
  pwsh -NoProfile -File .\vbc-pack\windows\vbc-make-qr.ps1 -AdbQr
"@
}

Require-Command "python"

$outDir = Split-Path -Parent $OutFile
if ($outDir -and -not (Test-Path $outDir)) {
  New-Item -ItemType Directory -Path $outDir | Out-Null
}

$py = @"
import sys
text = sys.argv[1]
out = sys.argv[2]
try:
  import qrcode
except Exception:
  sys.stderr.write("Missing python package 'qrcode'. Install: python -m pip install qrcode[pil]\\n")
  sys.exit(2)

img = qrcode.make(text)
img.save(out)
print(out)
"@

Write-Host "Generating QR for text:"
Write-Host $Text
Write-Host ""

$tmp = New-TemporaryFile
Set-Content -Path $tmp -Value $py -Encoding UTF8

$generated = & python $tmp $Text $OutFile
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Wrote: $generated"
if (-not $NoOpen) {
  try { Start-Process $generated | Out-Null } catch {}
}
