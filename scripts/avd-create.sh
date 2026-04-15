#!/usr/bin/env bash
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
AVDMANAGER="${AVDMANAGER:-$(command -v avdmanager)}"
AVD_NAME="${AVD_NAME:-Waddle_API_36}"
SYSTEM_IMAGE="system-images;android-36;google_apis;arm64-v8a"
AVD_DIR="${ANDROID_AVD_HOME:-$HOME/.android/avd}/$AVD_NAME.avd"
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export AVDMANAGER_OPTS="${AVDMANAGER_OPTS:-"-Dcom.android.sdkmanager.toolsdir=$SDK_ROOT/cmdline-tools/latest"}"

set_avd_property() {
  local key="$1"
  local value="$2"
  local config="$AVD_DIR/config.ini"

  if grep -q "^${key}=" "$config"; then
    perl -0pi -e "s|^${key}=.*$|${key}=${value}|m" "$config"
  else
    printf '%s=%s\n' "$key" "$value" >>"$config"
  fi
}

apply_s24_ultra_profile() {
  local config="$AVD_DIR/config.ini"

  if [[ ! -f "$config" ]]; then
    printf 'AVD config not found: %s\n' "$config" >&2
    exit 1
  fi

  set_avd_property "hw.device.manufacturer" "Samsung"
  set_avd_property "hw.device.name" "Galaxy S24 Ultra"
  set_avd_property "hw.lcd.width" "1440"
  set_avd_property "hw.lcd.height" "3120"
  set_avd_property "hw.lcd.density" "505"
  set_avd_property "hw.lcd.depth" "32"
  set_avd_property "hw.initialOrientation" "portrait"
  set_avd_property "hw.keyboard" "yes"
  set_avd_property "hw.keyboard.charmap" "qwerty2"
  set_avd_property "hw.keyboard.lid" "yes"
  set_avd_property "hw.mainKeys" "no"
  set_avd_property "hw.gpu.enabled" "yes"
  set_avd_property "hw.gpu.mode" "auto"
  set_avd_property "hw.ramSize" "4096"
  set_avd_property "vm.heapSize" "512"
  set_avd_property "showDeviceFrame" "no"
  set_avd_property "skin.dynamic" "yes"
  set_avd_property "skin.name" "1440x3120"
  set_avd_property "skin.path" "1440x3120"
}

if "$AVDMANAGER" list avd | grep -q "Name: $AVD_NAME"; then
  apply_s24_ultra_profile
  printf 'AVD already exists: %s\n' "$AVD_NAME"
  printf 'Applied Galaxy S24 Ultra display profile and hardware keyboard support.\n'
  exit 0
fi

printf 'no\n' | "$AVDMANAGER" create avd \
  --name "$AVD_NAME" \
  --package "$SYSTEM_IMAGE" \
  --sdcard 2048M \
  --force

apply_s24_ultra_profile
printf 'Created AVD: %s using SDK root %s\n' "$AVD_NAME" "$SDK_ROOT"
printf 'Applied Galaxy S24 Ultra display profile and hardware keyboard support.\n'
