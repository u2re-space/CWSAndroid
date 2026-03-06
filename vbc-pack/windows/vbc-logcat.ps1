param(
  [Parameter(Mandatory = $false)]
  [string]$Filter = "AndroidRuntime:V *:S",

  [Parameter(Mandatory = $false)]
  [string]$Serial
)

$ErrorActionPreference = "Stop"

function Require-Command([string]$name) {
  if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
    throw "Missing command '$name'. Install Android SDK Platform-Tools (adb) and ensure it's on PATH."
  }
}

Require-Command "adb"

$argsList = @()
if ($Serial -and $Serial.Trim().Length -gt 0) {
  $argsList += @("-s", $Serial)
}

# Convert "Tag:V Tag2:D" into "-s Tag:V Tag2:D"
$filterParts = $Filter.Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)

Write-Host "Running: adb $($argsList -join ' ') logcat -s $Filter"
adb @argsList logcat -s @filterParts
