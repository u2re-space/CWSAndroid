#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <windowsUser> <windowsHostOrIP> [port]" >&2
  exit 2
fi

user="$1"
host="$2"
port="${3:-22}"

if ! command -v ssh >/dev/null 2>&1; then
  echo "Missing ssh. Install: pkg install -y openssh" >&2
  exit 1
fi

exec ssh -p "$port" "$user@$host"


