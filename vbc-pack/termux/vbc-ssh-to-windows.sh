#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
vbc_common_loaded="0"
for f in \
  "$SCRIPT_DIR/vbc-common.sh" \
  "$HOME/.termux/vbc-common.sh" \
  "$HOME/bin/vbc-common.sh" \
  "${PREFIX:-}/bin/vbc-common.sh"
do
  if [[ -n "${f:-}" && -f "$f" ]]; then
    # shellcheck source=/dev/null
    . "$f"
    vbc_common_loaded="1"
    break
  fi
done
if [[ "$vbc_common_loaded" != "1" ]]; then
  echo "Missing vbc-common.sh. Keep it alongside this script, or install it in ~/.termux, ~/bin, or \$PREFIX/bin." >&2
  exit 1
fi

DEFAULT_USER="U2RE"
DEFAULT_HOST="192.168.0.120"
DEFAULT_PORT="22"
DEFAULT_WIN_PROJECTS_DIR='C:\Projects'

usage() {
  cat >&2 <<'EOF'
Usage:
  vbc-ssh-to-windows.sh                     # ssh to default U2RE@192.168.0.120:22
  vbc-ssh-to-windows.sh <host>              # override host, keep default user/port
  vbc-ssh-to-windows.sh <user> <host> [port]
  vbc-ssh-to-windows.sh <user@host[:port]>  # single-arg form
  vbc-ssh-to-windows.sh --cd <winPath>      # start remote shell in Windows directory
  vbc-ssh-to-windows.sh --no-cd             # disable any directory cd behavior

Environment overrides:
  VBC_SSH_USER, VBC_SSH_HOST, VBC_SSH_PORT
  VBC_WIN_WORKDIR (Windows path to the folder you opened in Cursor/VS Code)
  VBC_WIN_PROJECTS_DIR (defaults to C:\Projects; used to infer workdir if VBC_WIN_WORKDIR is unset)
EOF
}

user="${VBC_SSH_USER:-$DEFAULT_USER}"
host="${VBC_SSH_HOST:-$DEFAULT_HOST}"
port="${VBC_SSH_PORT:-$DEFAULT_PORT}"
win_workdir="${VBC_WIN_WORKDIR:-}"
win_projects_dir="${VBC_WIN_PROJECTS_DIR:-$DEFAULT_WIN_PROJECTS_DIR}"
no_cd="0"

# Parse flags first; keep positional args compatible with the existing host/user/port forms.
positional=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --cd|--workdir)
      win_workdir="${2:-}"
      shift 2
      ;;
    --no-cd)
      no_cd="1"
      shift
      ;;
    --)
      shift
      positional+=("$@")
      break
      ;;
    *)
      positional+=("$1")
      shift
      ;;
  esac
done

set -- "${positional[@]}"

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

if [[ "$no_cd" == "1" ]]; then
  win_workdir=""
fi

if [[ -z "$win_workdir" && "$no_cd" != "1" ]]; then
  win_workdir="$(vbc_infer_windows_workdir "$win_projects_dir" || true)"
fi

if [[ -n "$win_workdir" ]]; then
  # Escape single quotes for PowerShell single-quoted strings.
  ps_workdir="$(vbc_ps_escape_single_quotes "$win_workdir")"
  remote_cmd="powershell -NoProfile -NoExit -Command \"\$env:VBC_WIN_WORKDIR = '$ps_workdir'; Set-Location -LiteralPath '$ps_workdir'\""
  exec ssh -t -p "$port" "$user@$host" "$remote_cmd"
fi

exec ssh -p "$port" "$user@$host"


