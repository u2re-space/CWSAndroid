#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

DEFAULT_USER="U2RE"
DEFAULT_HOST="192.168.0.120"
DEFAULT_PORT="22"

usage() {
  cat >&2 <<'EOF'
Usage:
  vbc-ssh-to-windows.sh                     # ssh to default U2RE@192.168.0.120:22
  vbc-ssh-to-windows.sh <host>              # override host, keep default user/port
  vbc-ssh-to-windows.sh <user> <host> [port]
  vbc-ssh-to-windows.sh <user@host[:port]>  # single-arg form

Environment overrides:
  VBC_SSH_USER, VBC_SSH_HOST, VBC_SSH_PORT
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

user="${VBC_SSH_USER:-$DEFAULT_USER}"
host="${VBC_SSH_HOST:-$DEFAULT_HOST}"
port="${VBC_SSH_PORT:-$DEFAULT_PORT}"

if [[ $# -ge 1 ]]; then
  # Single-arg form: user@host[:port]
  if [[ "$1" == *@* && $# -eq 1 ]]; then
    target="$1"
    # Split optional :port (keep IPv4/hostname simple; we don't special-case IPv6 here)
    if [[ "$target" =~ ^([^:]+):([0-9]+)$ ]]; then
      target="${BASH_REMATCH[1]}"
      port="${BASH_REMATCH[2]}"
    fi
    user="${target%@*}"
    host="${target#*@}"
  elif [[ $# -eq 1 ]]; then
    # Host-only override
    host="$1"
  else
    # user host [port]
    user="$1"
    host="$2"
    port="${3:-$port}"
  fi
fi

if ! command -v ssh >/dev/null 2>&1; then
  echo "Missing ssh. Install: pkg install -y openssh" >&2
  exit 1
fi

exec ssh -p "$port" "$user@$host"


