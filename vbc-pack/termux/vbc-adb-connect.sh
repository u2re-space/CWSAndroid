#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

VBC_ADB_SSH_ENABLE="${VBC_ADB_SSH_ENABLE:-0}"
VBC_ADB_SSH_TARGET="${VBC_ADB_SSH_TARGET:-U2RE@192.168.0.120}"
VBC_ADB_SSH_PORT="${VBC_ADB_SSH_PORT:-22}"
VBC_ADB_LAST_FILE="${VBC_ADB_LAST_FILE:-$HOME/.vbc_last_adb_addr}"

# Supports optional "adb via Windows over SSH" mode (see README).

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing '$1'. Install it (Termux): pkg install -y $2" >&2
    exit 1
  }
}

is_valid_addr() {
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

extract_first_valid_addr() {
  local s="${1:-}" a
  # Accept raw "ip:port" anywhere in the text (also handles "adb://ip:port" because of the match).
  while IFS= read -r a; do
    if is_valid_addr "$a"; then
      printf '%s\n' "$a"
      return 0
    fi
  done < <(echo "$s" | tr -d '\r' | grep -Eo '([0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{1,5}' || true)
  return 1
}

load_last_addr() {
  local a=""
  [[ -f "$VBC_ADB_LAST_FILE" ]] || return 1
  a="$(tr -d '\r' < "$VBC_ADB_LAST_FILE" | head -n1 | xargs || true)"
  is_valid_addr "$a" || return 1
  printf '%s\n' "$a"
}

if [[ "$VBC_ADB_SSH_ENABLE" == "1" ]]; then
  require ssh openssh
else
  require adb android-tools
fi

clip=""
if command -v termux-clipboard-get >/dev/null 2>&1; then
  clip="$(termux-clipboard-get 2>/dev/null || true)"
fi

addr="$(extract_first_valid_addr "$clip" || true)"
if [[ -z "$addr" ]]; then
  read -r -p "Paste adb ip:port (blank = use last): " clip
  addr="$(extract_first_valid_addr "$clip" || true)"
fi

if [[ -z "$addr" ]]; then
  addr="$(load_last_addr || true)"
  if [[ -n "$addr" ]]; then
    echo "Using last saved address: $addr"
  fi
fi

if [[ -z "$addr" ]]; then
  echo "Could not find a valid ip:port in clipboard/input, and no last saved address at: $VBC_ADB_LAST_FILE" >&2
  exit 2
fi

if [[ "$VBC_ADB_SSH_ENABLE" == "1" ]]; then
  echo "adb via SSH: ${VBC_ADB_SSH_TARGET}:${VBC_ADB_SSH_PORT}"
  echo "remote: adb connect $addr"
  ssh -p "$VBC_ADB_SSH_PORT" "$VBC_ADB_SSH_TARGET" \
    powershell -NoProfile -Command "adb connect $addr; if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }; echo ''; adb devices -l"
else
  echo "adb connect $addr"
  adb connect "$addr"
  echo ""
  adb devices -l
fi

# Persist last successful address for future runs.
mkdir -p "$(dirname "$VBC_ADB_LAST_FILE")" 2>/dev/null || true
printf '%s\n' "$addr" > "$VBC_ADB_LAST_FILE" 2>/dev/null || true
