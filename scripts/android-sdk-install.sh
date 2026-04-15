#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
SDKMANAGER="${SDKMANAGER:-$(command -v sdkmanager)}"

mkdir -p "$SDK_ROOT"

"$SDKMANAGER" --sdk_root="$SDK_ROOT" --install \
  "platform-tools" \
  "emulator" \
  "platforms;android-36" \
  "build-tools;36.0.0" \
  "system-images;android-36;google_apis;arm64-v8a"

"$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses

printf 'Android SDK ready at %s\n' "$SDK_ROOT"
