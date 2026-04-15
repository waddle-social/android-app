#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="${ADB:-$(command -v adb || true)}"
if [[ -z "$ADB" ]]; then
  ADB="${SDK_ROOT}/platform-tools/adb"
fi

"$ADB" reverse tcp:3000 tcp:3000
"$ADB" reverse tcp:5222 tcp:5222

printf 'Reversed emulator ports: 3000 and 5222\n'
