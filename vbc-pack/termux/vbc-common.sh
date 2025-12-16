#!/data/data/com.termux/files/usr/bin/bash
# Shared helpers for VBC Termux scripts.
# Intended to be sourced: `. "$(dirname "${BASH_SOURCE[0]}")/vbc-common.sh"`
set -euo pipefail

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
  # Guard: only attempt % decoding if we see a percent.
  if [[ "$s" == *%* ]]; then
    # Replace % with \x so printf '%b' decodes hex escapes.
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
  # Accept raw "ip:port" anywhere in the text (also handles "adb://ip:port" because of the match).
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


