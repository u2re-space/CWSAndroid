#!/usr/bin/env bash
set -euo pipefail

ANDROID_SDK_ROOT_CANDIDATES=(
  "${ANDROID_HOME:-}"
  "${ANDROID_SDK_ROOT:-}"
  "/home/u2re-dev/Android/Sdk"
)

for candidate in "${ANDROID_SDK_ROOT_CANDIDATES[@]}"; do
  if [ -n "${candidate}" ] && [ -d "${candidate}" ]; then
    export ANDROID_HOME="${candidate}"
    break
  fi
done

if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "${ANDROID_HOME}" ]; then
  echo "ERROR: ANDROID_HOME is not set. Set it manually or place SDK at /home/u2re-dev/Android/Sdk." >&2
  exit 1
fi

export GRADLE_OPTS="${GRADLE_OPTS:-} -Dhttps.protocols=TLSv1.3,TLSv1.2 -Dhttp.keepAlive=true"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dhttps.protocols=TLSv1.3,TLSv1.2"

RETRIES=3
i=0
while true; do
  if ./gradlew :app:assembleDebug "$@"; then
    exit 0
  fi
  i=$((i + 1))
  if [ "${i}" -ge "${RETRIES}" ]; then
    echo "Android build failed after ${RETRIES} attempts." >&2
    exit 1
  fi
  echo "Build failed, retrying (${i}/${RETRIES})..."
  sleep 2
done
