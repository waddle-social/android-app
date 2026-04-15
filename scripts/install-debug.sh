#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-$(command -v adb || true)}"
if [[ -z "$ADB" ]]; then
  ADB="${SDK_ROOT}/platform-tools/adb"
fi

ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"

"$ROOT/gradlew" --project-dir "$ROOT" :app:installDebug
"$ROOT/scripts/adb-wake.sh"
"$ADB" shell monkey -p social.waddle.android.debug 1
