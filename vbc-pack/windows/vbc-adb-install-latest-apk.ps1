param(
  [Parameter(Mandatory = $false)]
  [string]$Serial,

  [Parameter(Mandatory = $false)]
  [string]$SearchRoot = ".",

  [Parameter(Mandatory = $false)]
  [switch]$Reinstall
)

$ErrorActionPreference = "Stop"

function Require-Command([string]$name) {
  if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
    throw "Missing command '$name'. Install Android SDK Platform-Tools (adb) and ensure it's on PATH."
  }
}

Require-Command "adb"

$apk = Get-ChildItem -Path $SearchRoot -Recurse -File -Filter *.apk -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -match '\\platforms\\android\\' -and $_.FullName -match '\\outputs\\apk\\' } |
  Sort-Object LastWriteTime -Descending |
  Select-Object -First 1

if (-not $apk) {
  throw "No APK found under '$SearchRoot'. Build first (e.g. npm run build or ns build android)."
}

$argsList = @()
if ($Serial -and $Serial.Trim().Length -gt 0) {
  $argsList += @("-s", $Serial)
}

$installArgs = @("install")
if ($Reinstall) { $installArgs += "-r" }
$installArgs += @("$($apk.FullName)")

Write-Host "Installing latest APK:"
Write-Host "  $($apk.FullName)"
Write-Host ""
Write-Host "Running: adb $($argsList -join ' ') $($installArgs -join ' ')"
adb @argsList @installArgs
