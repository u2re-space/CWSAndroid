#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

APP_ID="${APP_ID:-space.u2re.cws}"
MAX_LOG_LINES="${MAX_LOG_LINES:-250}"

WIN_SSH_USER="${VBC_WIN_SSH_USER:-U2RE}"
WIN_SSH_HOST="${VBC_WIN_SSH_HOST:-192.168.0.120}"
WIN_SSH_PORT="${VBC_WIN_SSH_PORT:-22}"
# Best-effort default: this repo's usual Windows workspace path.
WIN_WORKDIR="${VBC_WIN_WORKDIR:-C:\\Projects\\IOClientAndroid}"

have() { command -v "$1" >/dev/null 2>&1; }

now_iso() {
  if have date; then
    date -Iseconds 2>/dev/null || date
  else
    echo "unknown-time"
  fi
}

section() {
  echo ""
  echo "## $1"
}

out="$(mktemp)"
{
  echo "# VBC Prompt Bundle (Termux)"
  echo "- time: $(now_iso)"
  echo "- appId: $APP_ID"

  section "Termux environment"
  echo "- uname: $(uname -a 2>/dev/null || true)"
  if have getprop; then
    echo "- model: $(getprop ro.product.model 2>/dev/null || true)"
    echo "- android: $(getprop ro.build.version.release 2>/dev/null || true) (sdk $(getprop ro.build.version.sdk 2>/dev/null || true))"
  fi
  if have ip; then
    echo "- ip:"
    ip -br addr 2>/dev/null || true
  fi

  if have adb; then
    section "ADB (if available)"
    adb version 2>/dev/null || true
    adb devices -l 2>/dev/null || true
  fi

  section "Windows SSH (Cursor/cursor-agent)"
  echo "- target: ${WIN_SSH_USER}@${WIN_SSH_HOST}:${WIN_SSH_PORT}"
  echo "- workspace (Windows): ${WIN_WORKDIR}"
  echo ""
  echo "Commands (examples):"
  echo "  ssh -p ${WIN_SSH_PORT} ${WIN_SSH_USER}@${WIN_SSH_HOST}"
  echo "  ssh -t -p ${WIN_SSH_PORT} ${WIN_SSH_USER}@${WIN_SSH_HOST} 'powershell -NoProfile -NoExit -Command \"Set-Location -LiteralPath ''${WIN_WORKDIR}''\"'"
  echo "  # If you installed the VBC Termux URL opener, these are clickable from many apps:"
  echo "  #   vbc://workdir              -> open Windows Explorer at VBC_WIN_WORKDIR (or inferred C:\\Projects\\<repo>)"
  echo "  #   vbc://workdir/IOClientAndroid -> open Windows Explorer at C:\\Projects\\IOClientAndroid"
  echo "  cursor-agent (example; adjust to your installed CLI):"
  echo "    cursor-agent ssh ${WIN_SSH_USER}@${WIN_SSH_HOST} --port ${WIN_SSH_PORT} --cwd \"${WIN_WORKDIR}\""
  echo ""
  echo "Env overrides (set on Termux before running this script):"
  echo "  VBC_WIN_SSH_USER / VBC_WIN_SSH_HOST / VBC_WIN_SSH_PORT / VBC_WIN_WORKDIR"

  section "Relevant logcat (best-effort)"
  pid=""
  if have pidof; then
    pid="$(pidof -s "$APP_ID" 2>/dev/null || true)"
  fi
  if have logcat; then
    if [[ -n "$pid" ]]; then
      echo "- pid: $pid"
      logcat --pid "$pid" -d -t "$MAX_LOG_LINES" 2>/dev/null || logcat -d -t "$MAX_LOG_LINES" 2>/dev/null || true
    else
      echo "- pid: (not found)"
      logcat -d -t "$MAX_LOG_LINES" 2>/dev/null || true
    fi
  else
    echo "logcat not available in this environment."
  fi
} >"$out"

if have termux-clipboard-set; then
  termux-clipboard-set <"$out"
  echo "Copied prompt bundle to clipboard."
else
  cat "$out"
fi
