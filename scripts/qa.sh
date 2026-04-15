#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-$(command -v adb || true)}"
if [[ -z "$ADB" ]]; then
  ADB="${SDK_ROOT}/platform-tools/adb"
fi

"$ROOT/gradlew" --project-dir "$ROOT" verify

if "$ADB" devices | grep -q "device$"; then
  "$ROOT/scripts/dev-reverse.sh"
  "$ROOT/gradlew" --project-dir "$ROOT" connectedDebugAndroidTest
else
  printf 'No connected Android device or emulator. Run scripts/emulator-start.sh, then rerun connectedDebugAndroidTest.\n'
fi
