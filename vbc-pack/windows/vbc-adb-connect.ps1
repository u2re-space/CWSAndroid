param(
  [Parameter(Mandatory = $false)]
  [string]$Addr
)

$ErrorActionPreference = "Stop"

function Require-Command([string]$name) {
  if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
    throw "Missing command '$name'. Install Android SDK Platform-Tools (adb) and ensure it's on PATH."
  }
}

Require-Command "adb"

if (-not $Addr -or $Addr.Trim().Length -eq 0) {
  try {
    $clip = Get-Clipboard -Raw -ErrorAction Stop
  } catch {
    $clip = ""
  }

  if ($clip -match '(\d{1,3}\.){3}\d{1,3}:\d{2,5}') {
    $Addr = $Matches[0]
    Write-Host "Using clipboard address: $Addr"
  } else {
    $Addr = Read-Host "Enter connect address (ip:port) from Android 'Wireless debugging'"
  }
}

Write-Host "Running: adb connect $Addr"
adb connect $Addr
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host ""
Write-Host "Connected. Current devices:"
adb devices -l


