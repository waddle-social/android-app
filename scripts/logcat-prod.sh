#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
APP_ID="${APP_ID:-social.waddle.android.debug}"
CLEAR_LOGCAT="${CLEAR_LOGCAT:-1}"
LAUNCH_APP="${LAUNCH_APP:-1}"
LOG_FILE="${LOG_FILE:-$ROOT/logcat-prod.log}"
ADB="${ADB:-$(command -v adb || true)}"

if [[ -z "$ADB" ]]; then
  ADB="$SDK_ROOT/platform-tools/adb"
fi

"$ADB" wait-for-device

if [[ "$CLEAR_LOGCAT" == "1" ]]; then
  "$ADB" logcat -c
fi

if [[ "$LAUNCH_APP" == "1" ]]; then
  "$ROOT/scripts/adb-wake.sh"
  "$ADB" shell am start \
    -n "$APP_ID/social.waddle.android.MainActivity" \
    --es social.waddle.android.environment prod >/dev/null
fi

printf 'Writing crash logs to %s. Stop with Ctrl-C.\n' "$LOG_FILE" >&2

"$ADB" logcat -v time \
  ActivityManager:I \
  AndroidRuntime:E \
  DEBUG:E \
  StrictMode:D \
  System.err:W \
  Waddle:D \
  '*:S' |
  tee "$LOG_FILE"
