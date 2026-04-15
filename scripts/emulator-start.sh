#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AVD_NAME="${AVD_NAME:-Waddle_API_36}"
EMULATOR="${EMULATOR:-${SDK_ROOT}/emulator/emulator}"

if [[ ! -x "$EMULATOR" ]]; then
  EMULATOR="$(command -v emulator || true)"
fi

if [[ -z "$EMULATOR" || ! -x "$EMULATOR" ]]; then
  printf 'emulator not found. Set ANDROID_HOME or EMULATOR.\n' >&2
  exit 1
fi

args=(-avd "$AVD_NAME" -netdelay none -netspeed full)
if [[ "${EMULATOR_HEADLESS:-0}" == "1" ]]; then
  args+=(-no-window -no-audio -gpu swiftshader_indirect)
fi

exec "$EMULATOR" "${args[@]}" "$@"
