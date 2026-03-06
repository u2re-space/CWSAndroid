param(
  [Parameter(Mandatory = $false)]
  [string]$Addr,

  [Parameter(Mandatory = $false)]
  [string]$Code
)

$ErrorActionPreference = "Stop"

function Require-Command([string]$name) {
  if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
    throw "Missing command '$name'. Install Android SDK Platform-Tools (adb) and ensure it's on PATH."
  }
}

Require-Command "adb"

if (-not $Addr -or $Addr.Trim().Length -eq 0) {
  $Addr = Read-Host "Enter pairing address (ip:port) from Android 'Pair device with pairing code'"
}
if (-not $Code -or $Code.Trim().Length -eq 0) {
  $Code = Read-Host "Enter pairing code"
}

Write-Host "Running: adb pair $Addr <code>"
adb pair $Addr $Code
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Paired. Current devices:"
adb devices -l
