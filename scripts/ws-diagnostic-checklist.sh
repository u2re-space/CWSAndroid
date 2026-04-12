#!/usr/bin/env bash

set -euo pipefail

print_help() {
  cat <<'EOF'
Mini ws-diagnostic observer for reverse gateway logs.

Usage:
  ./scripts/ws-diagnostic-checklist.sh --android-log PATH --airpad-log PATH [--mode all|offline|mixed|rollback] [--strict]

Options:
  --android-log PATH   path to Android daemon/logcat excerpt containing [ws-state] lines
  --airpad-log PATH    path to AirPad log file containing [ws-state] lines
  --mode MODE          all | offline | mixed | rollback (default: all)
  --strict             strict mode: fail on missed ordered checkpoints
  -h, --help           show this help
EOF
}

ANDROID_LOG=""
AIRPAD_LOG=""
MODE="all"
STRICT=0

require_file() {
  local path="$1"
  local label="$2"
  if [[ -z "$path" || ! -f "$path" ]]; then
    echo "[SKIP] $label log not found: ${path:-<empty>}"
    return 1
  fi
  return 0
}

next_match_after_line() {
  local file="$1"
  local min_line="$2"
  local regex="$3"
  awk -v min="$min_line" -v re="$regex" 'NR>min && $0 ~ re { print NR; exit }' "$file"
}

match_lines() {
  local file="$1"
  shift
  local min_line=0
  local all_ok=1
  local patterns=("$@")
  local i=1
  for pattern in "${patterns[@]}"; do
    local line
    line=$(next_match_after_line "$file" "$min_line" "$pattern")
    if [[ -z "$line" ]]; then
      all_ok=0
      if [[ $STRICT -eq 1 ]]; then
        echo "  [ ] step $i pattern: $pattern"
      else
        echo "  [~] step $i pattern: $pattern (not found)"
      fi
      ((i++))
      continue
    fi
    echo "  [x] step $i pattern: $pattern (line $line)"
    min_line="$line"
    ((i++))
  done
  if [[ $all_ok -eq 1 ]]; then
    return 0
  fi
  return 1
}

check_case() {
  local mode="$1"
  local android_file="$2"
  local airpad_file="$3"

  local android_ok=1
  local airpad_ok=1

  local android_patterns=()
  local airpad_patterns=()

  case "$mode" in
    offline)
      android_patterns=(
        "[ws-state].*event=connecting.*candidate=1/2.*scheme=wss"
        "[ws-state].*event=(disconnected|failure).*candidate=1/2"
        "[ws-state].*event=connecting.*candidate=2/2.*scheme=ws"
        "[ws-state].*event=connected.*candidate=2/2"
      )
      airpad_patterns=(
        "\\[ws-state\\] event=connecting.*candidate=1/2.*transport=https"
        "\\[ws-state\\] event=connect-failed.*candidate=1/2"
        "\\[ws-state\\] event=connecting.*candidate=2/2.*transport=ws"
        "\\[ws-state\\] event=connected.*candidate=2/2"
      )
      ;;
    mixed)
      android_patterns=(
        "[ws-state].*event=connecting.*candidate=1/2.*scheme=wss"
        "[ws-state].*event=connect-failed.*candidate=1/2.*(tls|certificate|reason|candidate_url)"
        "[ws-state].*event=connecting.*candidate=2/2.*scheme=ws"
        "[ws-state].*event=connected.*candidate=2/2"
      )
      airpad_patterns=(
        "\\[ws-state\\] event=connecting.*candidate=1/2.*transport=https"
        "\\[ws-state\\] event=connect-failed.*candidate=1/2"
        "\\[ws-state\\] event=connecting.*candidate=2/2.*transport=ws"
        "\\[ws-state\\] event=connected.*candidate=2/2"
      )
      ;;
    rollback)
      android_patterns=(
        "[ws-state].*event=(connected|hello|ready).*candidate=2/2.*scheme=ws"
        "[ws-state].*event=(disconnected|failure).*candidate=2/2"
        "[ws-state].*event=connecting.*candidate=1/2"
        "[ws-state].*event=connecting.*candidate=2/2"
      )
      airpad_patterns=(
        "\\[ws-state\\] event=connected.*candidate=2/2"
        "\\[ws-state\\] event=(disconnected|engine-close|connect-failed).*candidate=2/2"
        "\\[ws-state\\] event=connecting.*candidate=1/2"
        "\\[ws-state\\] event=connecting.*candidate=2/2"
      )
      ;;
    *)
      return 0
      ;;
  esac

  echo "=== ${mode^^} CASE ==="
  if require_file "$android_file" "Android"; then
    if match_lines "$android_file" "${android_patterns[@]}"; then
      echo "  Android: PASS"
    else
      echo "  Android: FAIL"
      android_ok=0
    fi
  else
    android_ok=0
  fi

  if require_file "$airpad_file" "AirPad"; then
    if match_lines "$airpad_file" "${airpad_patterns[@]}"; then
      echo "  AirPad: PASS"
    else
      echo "  AirPad: FAIL"
      airpad_ok=0
    fi
  else
    airpad_ok=0
  fi

  if [[ $android_ok -eq 1 && $airpad_ok -eq 1 ]]; then
    echo "MODE:$mode RESULT: PASS"
    return 0
  fi

  if [[ $STRICT -eq 1 ]]; then
    echo "MODE:$mode RESULT: FAIL"
    return 1
  fi

  echo "MODE:$mode RESULT: WARN"
  return 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --android-log)
      ANDROID_LOG="${2:-}"
      shift 2
      ;;
    --airpad-log)
      AIRPAD_LOG="${2:-}"
      shift 2
      ;;
    --mode)
      MODE="${2:-all}"
      shift 2
      ;;
    --strict)
      STRICT=1
      shift
      ;;
    -h|--help)
      print_help
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      print_help
      exit 2
      ;;
  esac
done

if [[ -z "$ANDROID_LOG" ]] && [[ -z "$AIRPAD_LOG" ]]; then
  echo "No logs provided. Use --android-log and/or --airpad-log."
  print_help
  exit 2
fi

if [[ "$MODE" != "all" && "$MODE" != "offline" && "$MODE" != "mixed" && "$MODE" != "rollback" ]]; then
  echo "Invalid mode: $MODE"
  exit 2
fi

case "$MODE" in
  all)
    failed=0
    check_case offline "$ANDROID_LOG" "$AIRPAD_LOG" || failed=1
    check_case mixed "$ANDROID_LOG" "$AIRPAD_LOG" || failed=1
    check_case rollback "$ANDROID_LOG" "$AIRPAD_LOG" || failed=1
    ;;
  offline)
    check_case offline "$ANDROID_LOG" "$AIRPAD_LOG"
    failed=$?
    ;;
  mixed)
    check_case mixed "$ANDROID_LOG" "$AIRPAD_LOG"
    failed=$?
    ;;
  rollback)
    check_case rollback "$ANDROID_LOG" "$AIRPAD_LOG"
    failed=$?
    ;;
esac

exit ${failed:-0}
