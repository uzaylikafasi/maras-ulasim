#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")"

task_cache="${XDG_CACHE_HOME:-$HOME/.cache}/kahramankart-ulasim-api/android-tools"
task_sdk="${ANDROID_HOME:-$task_cache/sdk}"
task_aapt="${AAPT2_OVERRIDE:-$task_cache/linux-arm64/aapt2}"
if [[ ! -f "$task_sdk/platforms/android-35/android.jar" || ! -x "$task_aapt" ]]; then
  echo 'Android SDK 35 ve ARM64 AAPT2 gerekli. README.md içindeki Pi kurulum notuna bak.' >&2
  exit 1
fi
printf 'sdk.dir=%s\n' "$task_sdk" > local.properties
task_args=("$@")
if [[ ${#task_args[@]} -eq 0 ]]; then task_args=(:app:assembleDebug :app:testDebugUnitTest); fi
task_command=(./gradlew "-Pandroid.aapt2FromMavenOverride=$task_aapt" "${task_args[@]}")
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
export DBUS_SESSION_BUS_ADDRESS="${DBUS_SESSION_BUS_ADDRESS:-unix:path=$XDG_RUNTIME_DIR/bus}"
if systemctl --user show-environment >/dev/null 2>&1; then
  exec systemd-run --user --scope -p MemoryMax=2300M -p CPUQuota=220% "${task_command[@]}"
fi
exec "${task_command[@]}"
