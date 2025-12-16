#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

# Back-compat wrapper. All logic moved to `vbc.sh`.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

find_vbc_sh() {
  local p=""
  for p in \
    "$SCRIPT_DIR/vbc.sh" \
    "$HOME/.termux/vbc.sh" \
    "$HOME/bin/vbc.sh" \
    "${PREFIX:-}/bin/vbc.sh"
  do
    if [[ -n "$p" && -f "$p" ]]; then
      printf '%s\n' "$p"
      return 0
    fi
  done
  if command -v vbc.sh >/dev/null 2>&1; then
    command -v vbc.sh
    return 0
  fi
  return 1
}

vbc_sh="$(find_vbc_sh || true)"
if [[ -z "$vbc_sh" ]]; then
  echo "Missing vbc.sh. Put it next to this script, or install it at ~/.termux/vbc.sh, ~/bin/vbc.sh, or \$PREFIX/bin/vbc.sh" >&2
  exit 1
fi

exec /data/data/com.termux/files/usr/bin/bash "$vbc_sh" ssh-to-windows "$@"
