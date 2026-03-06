#!/data/data/com.termux/files/usr/bin/bash
# Compatibility shim for older scripts that still source `vbc-common.sh`.
# All helpers now live in `vbc.sh`.
#
# Intended to be sourced:
#   . "$(dirname "${BASH_SOURCE[0]}")/vbc-common.sh"
set -euo pipefail

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
  echo "Missing vbc.sh. Put it next to this file, or install it at ~/.termux/vbc.sh, ~/bin/vbc.sh, or \$PREFIX/bin/vbc.sh" >&2
  exit 1
fi

VBC_LIB_ONLY=1
# shellcheck source=/dev/null
. "$vbc_sh"
