#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-$(command -v adb || true)}"

if [[ -z "$ADB" ]]; then
  ADB="$SDK_ROOT/platform-tools/adb"
fi

"$ADB" wait-for-device
"$ADB" shell input keyevent KEYCODE_WAKEUP >/dev/null
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"$ADB" shell svc power stayon true >/dev/null 2>&1 || true
