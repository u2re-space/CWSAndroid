#!/data/data/com.termux/files/usr/bin/bash
# Unified VBC Termux script (single entrypoint).
# - Supports: url-opener routing, adb connect helper, ssh-to-windows helper
# - Can be executed directly or sourced (set VBC_LIB_ONLY=1 to only load functions).
set -euo pipefail

# If sourced, default to "library-only" mode.
if [[ "${BASH_SOURCE[0]}" != "$0" ]]; then
  : "${VBC_LIB_ONLY:=1}"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

vbc_have() { command -v "$1" >/dev/null 2>&1; }

vbc_require() {
  local bin="$1" pkg="${2:-$1}"
  vbc_have "$bin" || {
    echo "Missing '$bin'. Install it (Termux): pkg install -y $pkg" >&2
    exit 1
  }
}

vbc_strip_cr() { tr -d '\r'; }
vbc_trim() { xargs; }

vbc_ps_escape_single_quotes() {
  # Escape single quotes for PowerShell single-quoted strings.
  # In PowerShell single-quoted strings, ' becomes ''.
  local s="${1:-}"
  printf '%s' "${s//\'/\'\'}"
}

vbc_url_decode() {
  # Minimal URL decoding for our custom handlers:
  # - '+' => space
  # - %XX => byte
  local s="${1:-}"
  s="${s//+/ }"
  if [[ "$s" == *%* ]]; then
    s="${s//%/\\x}"
    printf '%b' "$s"
  else
    printf '%s' "$s"
  fi
}

vbc_is_valid_addr() {
  local a="${1:-}" ip port o1 o2 o3 o4

  [[ -n "$a" ]] || return 1
  [[ "$a" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}$ ]] || return 1

  ip="${a%:*}"
  port="${a##*:}"
  [[ "$port" =~ ^[0-9]+$ ]] || return 1
  ((port >= 1 && port <= 65535)) || return 1

  IFS='.' read -r o1 o2 o3 o4 <<<"$ip"
  for o in "$o1" "$o2" "$o3" "$o4"; do
    [[ "$o" =~ ^[0-9]+$ ]] || return 1
    ((o >= 0 && o <= 255)) || return 1
  done
}

vbc_extract_first_valid_addr() {
  local s="${1:-}" a
  while IFS= read -r a; do
    if vbc_is_valid_addr "$a"; then
      printf '%s\n' "$a"
      return 0
    fi
  done < <(echo "$s" | vbc_strip_cr | grep -Eo '([0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}' || true)
  return 1
}

vbc_load_last_addr() {
  local file="${1:-}" a=""
  [[ -n "$file" ]] || return 1
  [[ -f "$file" ]] || return 1
  a="$(vbc_strip_cr < "$file" | head -n1 | vbc_trim || true)"
  vbc_is_valid_addr "$a" || return 1
  printf '%s\n' "$a"
}

vbc_persist_last_addr() {
  local file="${1:-}" a="${2:-}"
  [[ -n "$file" ]] || return 0
  vbc_is_valid_addr "$a" || return 0
  mkdir -p "$(dirname "$file")" 2>/dev/null || true
  printf '%s\n' "$a" > "$file" 2>/dev/null || true
}

vbc_infer_repo_basename() {
  local root=""
  if vbc_have git; then
    root="$(git rev-parse --show-toplevel 2>/dev/null || true)"
  fi
  if [[ -z "$root" ]]; then
    root="$(pwd)"
  fi
  root="${root%/}"
  printf '%s\n' "${root##*/}"
}

vbc_infer_windows_workdir() {
  # Best-effort default: <projectsDir>\<repoName>
  local projects_dir="${1:-C:\Projects}" repo=""
  repo="$(vbc_infer_repo_basename | vbc_strip_cr | vbc_trim || true)"
  [[ -n "$repo" ]] || return 1
  printf '%s\n' "${projects_dir%\\}\\$repo"
}

vbc_usage() {
  cat >&2 <<'EOF'
Usage:
  vbc.sh url-opener <raw-url>
  vbc.sh adb-connect [ip:port]
  vbc.sh ssh-to-windows [args...]
  vbc.sh help

Subcommands:
  url-opener:
    Routes:
      adb://<ip:port>        -> adb connect (or via Windows SSH if VBC_ADB_SSH_ENABLE=1)
      adbssh://<ip:port>     -> adb connect via Windows SSH (always)
      ssh://user@host[:port] -> ssh into Windows (auto-cd to workdir if set/inferred)
      vbc://workdir[/Repo]   -> open Explorer on Windows via SSH

  adb-connect:
    Reads ip:port from argument, clipboard, prompt, or last saved address.

  ssh-to-windows:
    Compatible with vbc-ssh-to-windows.sh:
      vbc.sh ssh-to-windows
      vbc.sh ssh-to-windows <host>
      vbc.sh ssh-to-windows <user> <host> [port]
      vbc.sh ssh-to-windows <user@host[:port]>
      vbc.sh ssh-to-windows --cd <winPath>
      vbc.sh ssh-to-windows --no-cd
EOF
}

cmd_ssh_to_windows() {
  local DEFAULT_USER="U2RE"
  local DEFAULT_HOST="192.168.0.120"
  local DEFAULT_PORT="22"
  local DEFAULT_WIN_PROJECTS_DIR='C:\Projects'

  local user="${VBC_SSH_USER:-$DEFAULT_USER}"
  local host="${VBC_SSH_HOST:-$DEFAULT_HOST}"
  local port="${VBC_SSH_PORT:-$DEFAULT_PORT}"
  local win_workdir="${VBC_WIN_WORKDIR:-}"
  local win_projects_dir="${VBC_WIN_PROJECTS_DIR:-$DEFAULT_WIN_PROJECTS_DIR}"
  local no_cd="0"

  # Parse flags first; keep positional args compatible with the existing host/user/port forms.
  local positional=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -h|--help)
        vbc_usage
        return 0
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
      local target="$1"
      if [[ "$target" =~ ^([^:]+):([0-9]+)$ ]]; then
        target="${BASH_REMATCH[1]}"
        port="${BASH_REMATCH[2]}"
      fi
      user="${target%@*}"
      host="${target#*@}"
    elif [[ $# -eq 1 ]]; then
      host="$1"
    else
      user="$1"
      host="$2"
      port="${3:-$port}"
    fi
  fi

  vbc_require ssh openssh

  if [[ "$no_cd" == "1" ]]; then
    win_workdir=""
  fi

  if [[ -z "$win_workdir" && "$no_cd" != "1" ]]; then
    win_workdir="$(vbc_infer_windows_workdir "$win_projects_dir" || true)"
  fi

  if [[ -n "$win_workdir" ]]; then
    local ps_workdir
    ps_workdir="$(vbc_ps_escape_single_quotes "$win_workdir")"
    local remote_cmd
    remote_cmd="powershell -NoProfile -NoExit -Command \"\$env:VBC_WIN_WORKDIR = '$ps_workdir'; Set-Location -LiteralPath '$ps_workdir'\""
    exec ssh -t -p "$port" "$user@$host" "$remote_cmd"
  fi

  exec ssh -p "$port" "$user@$host"
}

adb_connect_local() {
  local addr="$1"
  vbc_require adb android-tools
  echo "adb connect $addr"
  adb connect "$addr"
}

adb_connect_via_ssh() {
  local addr="$1"
  local VBC_ADB_SSH_TARGET="${VBC_ADB_SSH_TARGET:-U2RE@192.168.0.120}"
  local VBC_ADB_SSH_PORT="${VBC_ADB_SSH_PORT:-22}"
  local VBC_ADB_SSH_NO_CD="${VBC_ADB_SSH_NO_CD:-0}"
  local VBC_ADB_SSH_WORKDIR="${VBC_ADB_SSH_WORKDIR:-${VBC_WIN_WORKDIR:-}}"
  local VBC_WIN_PROJECTS_DIR="${VBC_WIN_PROJECTS_DIR:-C:\Projects}"

  vbc_require ssh openssh
  echo "adb via SSH: ${VBC_ADB_SSH_TARGET}:${VBC_ADB_SSH_PORT}"
  echo "remote: adb connect $addr"

  local win_workdir=""
  if [[ "$VBC_ADB_SSH_NO_CD" != "1" ]]; then
    win_workdir="$(vbc_strip_cr <<<"${VBC_ADB_SSH_WORKDIR:-}" | vbc_trim || true)"
    if [[ -z "$win_workdir" ]]; then
      win_workdir="$(vbc_infer_windows_workdir "$VBC_WIN_PROJECTS_DIR" || true)"
    fi
  fi
  if [[ -n "$win_workdir" ]]; then
    echo "remote: cd $win_workdir"
  fi

  local ps_workdir
  ps_workdir="$(vbc_ps_escape_single_quotes "$win_workdir")"
  ssh -p "$VBC_ADB_SSH_PORT" "$VBC_ADB_SSH_TARGET" \
    powershell -NoProfile -Command "\
if ('$ps_workdir' -ne '') { Set-Location -LiteralPath '$ps_workdir' }; \
adb connect $addr; \
if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }; \
echo ''; \
adb devices -l"
}

cmd_adb_connect() {
  local VBC_ADB_SSH_ENABLE="${VBC_ADB_SSH_ENABLE:-0}"
  local VBC_ADB_LAST_FILE="${VBC_ADB_LAST_FILE:-$HOME/.vbc_last_adb_addr}"

  local addr="${1:-}"
  if [[ -n "$addr" ]]; then
    addr="$(vbc_extract_first_valid_addr "$addr" || true)"
  fi

  if [[ -z "$addr" ]]; then
    local clip=""
    if command -v termux-clipboard-get >/dev/null 2>&1; then
      clip="$(termux-clipboard-get 2>/dev/null || true)"
    fi
    addr="$(vbc_extract_first_valid_addr "$clip" || true)"
  fi

  if [[ -z "$addr" ]]; then
    local clip=""
    read -r -p "Paste adb ip:port (blank = use last): " clip
    addr="$(vbc_extract_first_valid_addr "$clip" || true)"
  fi

  if [[ -z "$addr" ]]; then
    addr="$(vbc_load_last_addr "$VBC_ADB_LAST_FILE" || true)"
    if [[ -n "$addr" ]]; then
      echo "Using last saved address: $addr"
    fi
  fi

  if [[ -z "$addr" ]]; then
    echo "Could not find a valid ip:port in clipboard/input, and no last saved address at: $VBC_ADB_LAST_FILE" >&2
    exit 2
  fi

  if [[ "$VBC_ADB_SSH_ENABLE" == "1" ]]; then
    adb_connect_via_ssh "$addr"
  else
    adb_connect_local "$addr"
    echo ""
    adb devices -l
  fi

  vbc_persist_last_addr "$VBC_ADB_LAST_FILE" "$addr"
}

win_open_workdir_via_ssh() {
  local workdir="${1:-}"
  local repo_override="${2:-}"
  local win_projects_dir="${VBC_WIN_PROJECTS_DIR:-C:\Projects}"

  if [[ -z "$workdir" ]]; then
    if [[ -n "$repo_override" ]]; then
      workdir="${win_projects_dir%\\}\\$repo_override"
    else
      workdir="$(vbc_infer_windows_workdir "$win_projects_dir" || true)"
    fi
  fi
  workdir="$(vbc_strip_cr <<<"$workdir" | vbc_trim || true)"
  if [[ -z "$workdir" ]]; then
    echo "No workdir found. Set VBC_WIN_WORKDIR, or use vbc://workdir/<RepoName>." >&2
    exit 2
  fi

  # Prefer the envs used by vbc-ai-prompt.sh and vbc-ssh-to-windows.sh.
  local win_ssh_user="${VBC_WIN_SSH_USER:-${VBC_SSH_USER:-}}"
  local win_ssh_host="${VBC_WIN_SSH_HOST:-${VBC_SSH_HOST:-}}"
  local win_ssh_port="${VBC_WIN_SSH_PORT:-${VBC_SSH_PORT:-22}}"

  # Back-compat fallback: use the adb-via-ssh target.
  if [[ -z "$win_ssh_user" || -z "$win_ssh_host" ]]; then
    local fallback_target="${VBC_ADB_SSH_TARGET:-U2RE@192.168.0.120}"
    win_ssh_user="${fallback_target%@*}"
    win_ssh_host="${fallback_target#*@}"
    win_ssh_port="${VBC_ADB_SSH_PORT:-$win_ssh_port}"
  fi

  vbc_require ssh openssh
  local ps_workdir
  ps_workdir="$(vbc_ps_escape_single_quotes "$workdir")"
  echo "open workdir: $workdir"
  echo "via SSH: ${win_ssh_user}@${win_ssh_host}:${win_ssh_port}"
  ssh -p "$win_ssh_port" "${win_ssh_user}@${win_ssh_host}" \
    powershell -NoProfile -Command "Start-Process explorer.exe -ArgumentList '$ps_workdir'"
}

cmd_url_opener() {
  local raw="${1:-}"
  raw="${raw//$'\r'/}"
  if [[ -z "$raw" ]]; then
    echo "No input." >&2
    exit 2
  fi

  local VBC_ADB_SSH_ENABLE="${VBC_ADB_SSH_ENABLE:-0}"
  local VBC_ADB_LAST_FILE="${VBC_ADB_LAST_FILE:-$HOME/.vbc_last_adb_addr}"

  if [[ "$raw" =~ ^vbc://workdir ]]; then
    local rest repo_override
    rest="${raw#vbc://workdir}"
    rest="${rest#\/}"
    rest="${rest%%\?*}"
    repo_override="$(vbc_url_decode "$rest" | vbc_trim || true)"
    win_open_workdir_via_ssh "${VBC_WIN_WORKDIR:-}" "$repo_override"
    exit 0
  fi

  if [[ "$raw" =~ ^adbssh:// ]]; then
    local addr
    addr="$(vbc_extract_first_valid_addr "${raw#adbssh://}" || true)"
    if [[ -z "$addr" ]]; then
      addr="$(vbc_load_last_addr "$VBC_ADB_LAST_FILE" || true)"
      [[ -n "$addr" ]] || { echo "Invalid adbssh:// address (expected ip:port)." >&2; exit 2; }
      echo "Invalid adbssh:// address; using last saved address: $addr"
    fi
    adb_connect_via_ssh "$addr"
    vbc_persist_last_addr "$VBC_ADB_LAST_FILE" "$addr"
    exit 0
  fi

  if [[ "$raw" =~ ^adb:// ]]; then
    local addr
    addr="$(vbc_extract_first_valid_addr "${raw#adb://}" || true)"
    if [[ -z "$addr" ]]; then
      addr="$(vbc_load_last_addr "$VBC_ADB_LAST_FILE" || true)"
      [[ -n "$addr" ]] || { echo "Invalid adb:// address (expected ip:port)." >&2; exit 2; }
      echo "Invalid adb:// address; using last saved address: $addr"
    fi
    if [[ "$VBC_ADB_SSH_ENABLE" == "1" ]]; then
      adb_connect_via_ssh "$addr"
    else
      adb_connect_local "$addr"
    fi
    vbc_persist_last_addr "$VBC_ADB_LAST_FILE" "$addr"
    exit 0
  fi

  if [[ "$raw" =~ ^ssh:// ]]; then
    local target
    target="${raw#ssh://}"
    target="${target%%/*}"
    echo "ssh-to-windows $target"
    cmd_ssh_to_windows "$target"
    exit 0
  fi

  if command -v termux-open >/dev/null 2>&1; then
    exec termux-open "$raw"
  fi

  echo "$raw"
}

main() {
  local cmd="${1:-url-opener}"
  shift || true
  case "$cmd" in
    help|-h|--help)
      vbc_usage
      ;;
    url-opener)
      cmd_url_opener "${1:-}"
      ;;
    adb-connect)
      cmd_adb_connect "${1:-}"
      ;;
    ssh-to-windows)
      cmd_ssh_to_windows "$@"
      ;;
    *)
      echo "Unknown command: $cmd" >&2
      vbc_usage
      exit 2
      ;;
  esac
}

if [[ "${VBC_LIB_ONLY:-0}" == "1" ]]; then
  return 0 2>/dev/null || true
fi

main "$@"
