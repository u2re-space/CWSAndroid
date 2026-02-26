#!/usr/bin/env bash

set -euo pipefail

SSH_HOST="${ANDROID_REMOTE_HOST:-${SSH_HOST:-U2RE@192.168.0.110}}"
ADB_TARGET="${ADB_REMOTE_TARGET:-192.168.0.110:5555}"
AVD_NAME="${ANDROID_REMOTE_AVD_NAME:-${REMOTE_AVD_NAME:-${2:-}}}"
DEFAULT_AVD_NAME="${ANDROID_REMOTE_DEFAULT_AVD:-${ANDROID_REMOTE_DEFAULT_AVD_NAME:-}}"

run_ssh() {
  local cmd="$1"
  ssh -o BatchMode=yes -o ConnectTimeout=10 "$SSH_HOST" "$cmd"
}

emulator_home_cmd='
if [ -n "$ANDROID_HOME" ] && [ -x "$ANDROID_HOME/emulator/emulator" ]; then
  EMULATOR_CMD="$ANDROID_HOME/emulator/emulator"
elif command -v emulator >/dev/null 2>&1; then
  EMULATOR_CMD="emulator"
else
  echo "Error: emulator binary not found on remote host."
  exit 1
fi
'

run_list() {
  run_ssh "$emulator_home_cmd; \"\$EMULATOR_CMD\" -list-avds"
}

resolve_avd_name() {
  if [[ -n "$AVD_NAME" ]]; then
    return
  fi

  if [[ -n "$DEFAULT_AVD_NAME" ]]; then
    AVD_NAME="$DEFAULT_AVD_NAME"
    return
  fi

  case "$SSH_HOST" in
    U2RE@192.168.0.110|192.168.0.110)
      AVD_NAME="Pixel_7"
      return
      ;;
  esac

  local first_avd
  first_avd="$(run_ssh "$emulator_home_cmd; \"\$EMULATOR_CMD\" -list-avds | sed -n '1p' | tr -d '\r'")"
  first_avd="${first_avd//[$'\r\n']/}"
  if [[ -n "$first_avd" ]]; then
    AVD_NAME="$first_avd"
    return
  fi
}

run_start() {
  local name="$1"
  local extra_opts="${REMOTE_EMULATOR_ARGS:-}"

  run_ssh "
    $emulator_home_cmd
    nohup \"\$EMULATOR_CMD\" -avd \"$name\" $extra_opts -no-snapshot-save >/tmp/android-emulator-${name}.log 2>&1 < /dev/null &
    echo \"Started emulator '$name' on remote host ${SSH_HOST}\""
}

run_connect() {
  adb connect "$ADB_TARGET"
  adb -s "$ADB_TARGET" wait-for-device
}

run_status() {
  echo "-- adb local --"
  adb devices -l
  echo "-- remote emulator processes --"
  if ssh -o BatchMode=yes -o ConnectTimeout=10 "$SSH_HOST" "pgrep -af 'qemu-system-x86_64' | sed 's/^/  /'"; then
    :
  else
    echo "  (no qemu-system-x86_64 processes)"
  fi
}

run_start_connect() {
  local name="$1"
  local wait="${EMULATOR_START_WAIT_SECONDS:-12}"
  run_start "$name"
  sleep "$wait"
  run_connect
}

ACTION="${1:-}"
case "$ACTION" in
  list)
    run_list
    ;;
  start)
    resolve_avd_name
    if [[ -z "${AVD_NAME}" ]]; then
      echo "Error: No AVD name available. Set ANDROID_REMOTE_AVD_NAME or pass: bash ./scripts/android-emulator-remote.sh start <avd_name>"
      exit 1
    fi
    run_start "$AVD_NAME"
    ;;
  start-connect)
    resolve_avd_name
    if [[ -z "${AVD_NAME}" ]]; then
      echo "Error: No AVD name available. Set ANDROID_REMOTE_AVD_NAME or pass: bash ./scripts/android-emulator-remote.sh start-connect <avd_name>"
      exit 1
    fi
    run_start_connect "$AVD_NAME"
    ;;
  connect)
    run_connect
    ;;
  status)
    run_status
    ;;
  *)
    echo "Usage: $0 <list|start|start-connect|connect|status> [avd_name_for_first_two_if env not set]"
    echo ""
    echo "Examples:"
    echo "  ANDROID_REMOTE_HOST=U2RE@192.168.0.110 ADB_REMOTE_TARGET=192.168.0.110:5555"
    echo "  ANDROID_REMOTE_DEFAULT_AVD=Pixel_7 ./scripts/android-emulator-remote.sh start-connect"
    echo "  ANDROID_REMOTE_AVD_NAME=Pixel_7 ./scripts/android-emulator-remote.sh start-connect"
    exit 1
    ;;
esac

