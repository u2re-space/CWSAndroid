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

VBC_ADB_SSH_ENABLE="${VBC_ADB_SSH_ENABLE:-0}"
VBC_ADB_SSH_TARGET="${VBC_ADB_SSH_TARGET:-U2RE@192.168.0.120}"
VBC_ADB_SSH_PORT="${VBC_ADB_SSH_PORT:-22}"
VBC_ADB_LAST_FILE="${VBC_ADB_LAST_FILE:-$HOME/.vbc_last_adb_addr}"
VBC_ADB_SSH_NO_CD="${VBC_ADB_SSH_NO_CD:-0}"
VBC_ADB_SSH_WORKDIR="${VBC_ADB_SSH_WORKDIR:-${VBC_WIN_WORKDIR:-}}"
VBC_WIN_PROJECTS_DIR="${VBC_WIN_PROJECTS_DIR:-C:\Projects}"

# Supports optional "adb via Windows over SSH" mode (see README).

if [[ "$VBC_ADB_SSH_ENABLE" == "1" ]]; then
  vbc_require ssh openssh
else
  vbc_require adb android-tools
fi

clip=""
if command -v termux-clipboard-get >/dev/null 2>&1; then
  clip="$(termux-clipboard-get 2>/dev/null || true)"
fi

addr="$(vbc_extract_first_valid_addr "$clip" || true)"
if [[ -z "$addr" ]]; then
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
  echo "adb via SSH: ${VBC_ADB_SSH_TARGET}:${VBC_ADB_SSH_PORT}"
  echo "remote: adb connect $addr"

  win_workdir=""
  if [[ "$VBC_ADB_SSH_NO_CD" != "1" ]]; then
    win_workdir="$(vbc_strip_cr <<<"${VBC_ADB_SSH_WORKDIR:-}" | vbc_trim || true)"
    if [[ -z "$win_workdir" ]]; then
      win_workdir="$(vbc_infer_windows_workdir "$VBC_WIN_PROJECTS_DIR" || true)"
    fi
  fi
  if [[ -n "$win_workdir" ]]; then
    echo "remote: cd $win_workdir"
  fi

  # Escape single quotes for PowerShell single-quoted strings.
  ps_workdir="$(vbc_ps_escape_single_quotes "$win_workdir")"
  ssh -p "$VBC_ADB_SSH_PORT" "$VBC_ADB_SSH_TARGET" \
    powershell -NoProfile -Command "\
if ('$ps_workdir' -ne '') { Set-Location -LiteralPath '$ps_workdir' }; \
adb connect $addr; \
if (\$LASTEXITCODE -ne 0) { exit \$LASTEXITCODE }; \
echo ''; \
adb devices -l"
else
  echo "adb connect $addr"
  adb connect "$addr"
  echo ""
  adb devices -l
fi

# Persist last successful address for future runs.
vbc_persist_last_addr "$VBC_ADB_LAST_FILE" "$addr"
