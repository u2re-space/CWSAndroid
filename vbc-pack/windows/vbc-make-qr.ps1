param(
  [Parameter(Mandatory = $true)]
  [string]$Text,

  [Parameter(Mandatory = $false)]
  [string]$OutFile = ".\vbc-pack\out\vbc-qr.png"
)

$ErrorActionPreference = "Stop"

function Require-Command([string]$name) {
  if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
    throw "Missing command '$name'."
  }
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
try { Start-Process $generated | Out-Null } catch {}


