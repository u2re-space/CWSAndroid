#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing '$1'. Install it (Termux): pkg install -y $2" >&2
    exit 1
  }
}

require adb android-tools

clip=""
if command -v termux-clipboard-get >/dev/null 2>&1; then
  clip="$(termux-clipboard-get 2>/dev/null || true)"
fi

if [[ -z "${clip// /}" ]]; then
  read -r -p "Paste adb ip:port: " clip
fi

addr="$(echo "$clip" | tr -d '\r' | grep -Eo '([0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{2,5}' | head -n1 || true)"
if [[ -z "$addr" ]]; then
  # also accept "adb://ip:port"
  addr="$(echo "$clip" | tr -d '\r' | sed -E 's#^adb://##' | grep -Eo '([0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]{2,5}' | head -n1 || true)"
fi

if [[ -z "$addr" ]]; then
  echo "Could not find ip:port in clipboard/input." >&2
  exit 2
fi

echo "adb connect $addr"
adb connect "$addr"
echo ""
adb devices -l


