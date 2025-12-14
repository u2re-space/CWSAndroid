#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

APP_ID="${APP_ID:-com.u2re.ioclient}"
MAX_LOG_LINES="${MAX_LOG_LINES:-250}"

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


