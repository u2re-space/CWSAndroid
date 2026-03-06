param()

$ErrorActionPreference = "Stop"

function Assert-Admin {
  $id = [Security.Principal.WindowsIdentity]::GetCurrent()
  $p = New-Object Security.Principal.WindowsPrincipal($id)
  if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run this script from an elevated (Administrator) PowerShell."
  }
}

Assert-Admin

Write-Host "Enabling OpenSSH Server capability (if needed)..."
try {
  $cap = Get-WindowsCapability -Online | Where-Object Name -like "OpenSSH.Server*"
  if ($cap -and $cap.State -ne "Installed") {
    Add-WindowsCapability -Online -Name $cap.Name | Out-Null
  }
} catch {
  Write-Warning "Could not query/install OpenSSH capability automatically. You may need to enable it via Windows Settings → Optional features."
}

Write-Host "Starting and enabling sshd service..."
try {
  Set-Service -Name sshd -StartupType Automatic
  Start-Service sshd
} catch {
  throw "Failed to start sshd. Ensure OpenSSH Server is installed."
}

Write-Host "Adding firewall rule for SSH (port 22) if missing..."
try {
  if (-not (Get-NetFirewallRule -Name "OpenSSH-Server-In-TCP" -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -Name "OpenSSH-Server-In-TCP" -DisplayName "OpenSSH Server (sshd)" -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22 | Out-Null
  }
} catch {
  Write-Warning "Could not add firewall rule automatically."
}

Write-Host ""
Write-Host "SSH server should be running."
Write-Host "Find your LAN IP via: ipconfig"
Write-Host "From Termux: ssh <windowsUser>@<windowsIP>"
