#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AVD_NAME="${AVD_NAME:-Waddle_API_36}"
APP_ID="${APP_ID:-social.waddle.android.debug}"
CLEAR_APP_DATA="${CLEAR_APP_DATA:-1}"
CLEAR_LOGCAT="${CLEAR_LOGCAT:-1}"
POST_LAUNCH_LOG_SECONDS="${POST_LAUNCH_LOG_SECONDS:-5}"
ADB_COMMAND_TIMEOUT_SECONDS="${ADB_COMMAND_TIMEOUT_SECONDS:-30}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${LOG_DIR:-$ROOT/logs/prod-run/$RUN_ID}"
ADB="${ADB:-$(command -v adb || true)}"
EMULATOR="${EMULATOR:-$SDK_ROOT/emulator/emulator}"

mkdir -p "$LOG_DIR"
exec > >(tee -a "$LOG_DIR/run-prod.log") 2>&1
printf 'Writing run diagnostics to %s\n' "$LOG_DIR"

if [[ -z "$ADB" ]]; then
  ADB="$SDK_ROOT/platform-tools/adb"
fi

if [[ ! -x "$EMULATOR" ]]; then
  EMULATOR="$(command -v emulator || true)"
fi

if [[ -z "$EMULATOR" || ! -x "$EMULATOR" ]]; then
  printf 'emulator not found. Set ANDROID_HOME or EMULATOR.\n' >&2
  exit 1
fi

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.gradle-user-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ROOT/.android}"

LOGCAT_PID=""

capture() {
  local file="$1"
  shift
  {
    printf '$'
    printf ' %q' "$@"
    printf '\n'
    "$@"
  } >"$LOG_DIR/$file" 2>&1 || true
}

run_with_timeout() {
  local timeout_seconds="$1"
  shift
  local command_pid=""
  local elapsed=0

  "$@" &
  command_pid="$!"
  while kill -0 "$command_pid" 2>/dev/null; do
    if (( elapsed >= timeout_seconds )); then
      printf 'Command timed out after %s seconds:' "$timeout_seconds" >&2
      printf ' %q' "$@" >&2
      printf '\n' >&2
      kill "$command_pid" 2>/dev/null || true
      wait "$command_pid" 2>/dev/null || true
      return 124
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  wait "$command_pid"
}

start_live_logcat() {
  if [[ "$CLEAR_LOGCAT" == "1" ]]; then
    "$ADB" logcat -c || true
  fi

  "$ADB" logcat -v threadtime \
    ActivityManager:I \
    AndroidRuntime:E \
    DEBUG:E \
    StrictMode:D \
    System.err:W \
    Waddle:D \
    '*:E' >"$LOG_DIR/logcat-live-errors.log" 2>&1 &
  LOGCAT_PID="$!"
}

stop_live_logcat() {
  if [[ -n "$LOGCAT_PID" ]]; then
    kill "$LOGCAT_PID" 2>/dev/null || true
    wait "$LOGCAT_PID" 2>/dev/null || true
    LOGCAT_PID=""
  fi
}

collect_diagnostics() {
  local status="$1"
  local app_pid=""
  set +e
  stop_live_logcat
  app_pid="$("$ADB" shell pidof "$APP_ID" 2>/dev/null | tr -d '\r' || true)"

  {
    printf 'status=%s\n' "$status"
    printf 'timestamp=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'root=%s\n' "$ROOT"
    printf 'app_id=%s\n' "$APP_ID"
    printf 'avd_name=%s\n' "$AVD_NAME"
    printf 'android_home=%s\n' "$ANDROID_HOME"
    printf 'android_user_home=%s\n' "$ANDROID_USER_HOME"
    printf 'gradle_user_home=%s\n' "$GRADLE_USER_HOME"
    printf 'clear_app_data=%s\n' "$CLEAR_APP_DATA"
    printf 'clear_logcat=%s\n' "$CLEAR_LOGCAT"
    printf 'post_launch_log_seconds=%s\n' "$POST_LAUNCH_LOG_SECONDS"
    printf 'adb_command_timeout_seconds=%s\n' "$ADB_COMMAND_TIMEOUT_SECONDS"
    printf '\n## java --version\n'
    java --version
    printf '\n## adb version\n'
    "$ADB" version
  } >"$LOG_DIR/environment.txt" 2>&1 || true

  capture adb-devices.txt "$ADB" devices -l
  capture app-pid.txt "$ADB" shell pidof "$APP_ID"
  capture activity-top.txt "$ADB" shell dumpsys activity top
  capture activity-processes.txt "$ADB" shell dumpsys activity processes
  capture package.txt "$ADB" shell dumpsys package "$APP_ID"
  capture window.txt "$ADB" shell dumpsys window
  if [[ -n "$app_pid" ]]; then
    capture logcat-app-tail.txt "$ADB" logcat -d -v threadtime --pid "$app_pid" -t 5000
  fi
  capture logcat-waddle-tail.txt "$ADB" logcat -d -v threadtime -t 5000 Waddle:D AndroidRuntime:E System.err:W '*:S'
  capture logcat-errors-tail.txt "$ADB" logcat -d -v threadtime -t 3000 ActivityManager:I AndroidRuntime:E DEBUG:E StrictMode:D System.err:W Waddle:D '*:E'
  capture logcat-full-tail.txt "$ADB" logcat -d -v threadtime -t 3000
  capture dropbox-data-app-crash.txt "$ADB" shell dumpsys dropbox --print data_app_crash
  capture dropbox-system-app-crash.txt "$ADB" shell dumpsys dropbox --print system_app_crash

  if [[ -f "$ROOT/.emulator.log" ]]; then
    capture emulator-log-tail.txt tail -300 "$ROOT/.emulator.log"
  fi
}

cleanup() {
  local status="$?"
  collect_diagnostics "$status"
  if [[ "$status" == "0" ]]; then
    printf 'Prod run diagnostics written to %s\n' "$LOG_DIR"
  else
    printf 'Prod run failed with exit %s. Diagnostics written to %s\n' "$status" "$LOG_DIR" >&2
  fi
  return "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM HUP

device_connected() {
  "$ADB" devices | awk 'NR > 1 && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'
}

boot_completed() {
  "$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | grep -q '^1$'
}

if ! "$EMULATOR" -list-avds | grep -qx "$AVD_NAME"; then
  "$ROOT/scripts/avd-create.sh"
fi

if ! device_connected; then
  args=(-avd "$AVD_NAME" -netdelay none -netspeed full)
  if [[ "${EMULATOR_HEADLESS:-0}" == "1" ]]; then
    args+=(-no-window -no-audio -gpu swiftshader_indirect)
  fi
  nohup "$EMULATOR" "${args[@]}" >"$ROOT/.emulator.log" 2>&1 &
fi

"$ADB" wait-for-device
until boot_completed; do
  sleep 2
done
"$ROOT/scripts/adb-wake.sh"
start_live_logcat

"$ROOT/gradlew" --project-dir "$ROOT" :app:installDebug

if [[ "$CLEAR_APP_DATA" == "1" ]]; then
  "$ADB" shell pm clear "$APP_ID" >/dev/null
fi
"$ROOT/scripts/adb-wake.sh"

run_with_timeout "$ADB_COMMAND_TIMEOUT_SECONDS" "$ADB" shell am start \
  -n "$APP_ID/social.waddle.android.MainActivity" \
  --es social.waddle.android.environment prod

printf 'Launched %s against https://xmpp.waddle.social\n' "$APP_ID"

if [[ "$POST_LAUNCH_LOG_SECONDS" != "0" ]]; then
  sleep "$POST_LAUNCH_LOG_SECONDS"
fi
