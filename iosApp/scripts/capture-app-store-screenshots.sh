#!/bin/bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
ios_root="$repo_root/iosApp"
temp_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
temp_root="${temp_root%/}"

preferred_runtime="${IOS_APP_STORE_SCREENSHOT_RUNTIME:-com.apple.CoreSimulator.SimRuntime.iOS-18-6}"
device_type="${IOS_APP_STORE_SCREENSHOT_DEVICE_TYPE:-com.apple.CoreSimulator.SimDeviceType.iPhone-16-Pro-Max}"
wait_seconds="${IOS_APP_STORE_SCREENSHOT_WAIT_SECONDS:-5}"
keep_simulator="${IOS_APP_STORE_SCREENSHOT_KEEP_SIMULATOR:-0}"
screenshot_language="${IOS_APP_STORE_SCREENSHOT_LANGUAGE:-zh-Hans}"
screenshot_locale="${IOS_APP_STORE_SCREENSHOT_LOCALE:-zh_CN}"
timestamp="$(date +%Y%m%d-%H%M%S)"
output_dir="${IOS_APP_STORE_SCREENSHOT_OUTPUT_DIR:-$repo_root/build/app-store-screenshots/$timestamp}"
derived_data_path="${IOS_APP_STORE_SCREENSHOT_DERIVED_DATA_PATH:-$temp_root/XJTUToolboxIOS-app-store-screenshot-derived-data}"
app_path="$derived_data_path/Build/Products/Debug-iphonesimulator/XJTUToolboxIOS.app"
bundle_id="com.xjtu.toolbox.ios"
simulator_id=""

fail() {
  echo "error: $*" >&2
  exit 1
}

log() {
  echo
  echo "==> $*"
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "missing required command: $1"
}

cleanup() {
  if [[ -n "$simulator_id" && "$keep_simulator" != "1" ]]; then
    xcrun simctl shutdown "$simulator_id" >/dev/null 2>&1 || true
    xcrun simctl delete "$simulator_id" >/dev/null 2>&1 || true
  fi
}

select_runtime() {
  xcrun simctl list runtimes available -j | python3 -c '
import json
import sys

preferred = sys.argv[1]
runtimes = [
    runtime["identifier"]
    for runtime in json.load(sys.stdin)["runtimes"]
    if runtime.get("isAvailable") and "iOS" in runtime.get("name", "")
]
if not runtimes:
    raise SystemExit("No available iOS simulator runtime")
print(preferred if preferred in runtimes else sorted(runtimes)[-1])
' "$preferred_runtime"
}

capture() {
  local filename="$1"
  shift
  local destination="$output_dir/$filename"

  xcrun simctl terminate "$simulator_id" "$bundle_id" >/dev/null 2>&1 || true
  xcrun simctl launch "$simulator_id" "$bundle_id" \
    -AppleLanguages "($screenshot_language)" \
    -AppleLocale "$screenshot_locale" \
    "$@" >/dev/null
  sleep "$wait_seconds"
  xcrun simctl io "$simulator_id" screenshot --type=png --mask=ignored "$destination" >/dev/null

  local width
  local height
  width="$(sips -g pixelWidth "$destination" | awk '/pixelWidth/ {print $2}')"
  height="$(sips -g pixelHeight "$destination" | awk '/pixelHeight/ {print $2}')"
  case "${width}x${height}" in
    1260x2736|1290x2796|1320x2868) ;;
    *) fail "$filename has unsupported App Store 6.9-inch portrait dimensions: ${width}x${height}" ;;
  esac
  printf '%s\t%sx%s\n' "$filename" "$width" "$height" >>"$output_dir/manifest.tsv"
}

require_command awk
require_command date
require_command python3
require_command sips
require_command xcodebuild
require_command xcodegen
require_command xcrun

[[ "$wait_seconds" =~ ^[1-9][0-9]*$ ]] || fail "IOS_APP_STORE_SCREENSHOT_WAIT_SECONDS must be a positive integer"
[[ "$output_dir" == /* ]] || fail "IOS_APP_STORE_SCREENSHOT_OUTPUT_DIR must be an absolute path: $output_dir"
if [[ -e "$output_dir" && -n "$(find "$output_dir" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  fail "output directory must be empty: $output_dir"
fi
mkdir -p "$output_dir"
: >"$output_dir/manifest.tsv"
trap cleanup EXIT

log "Create dedicated privacy-safe screenshot simulator"
runtime_id="$(select_runtime)"
simulator_name="XJTUToolbox-AppStore-$timestamp"
simulator_id="$(xcrun simctl create "$simulator_name" "$device_type" "$runtime_id")"
echo "simulator: $simulator_name ($simulator_id)"
echo "runtime: $runtime_id"
xcrun simctl boot "$simulator_id"
xcrun simctl bootstatus "$simulator_id" -b
xcrun simctl ui "$simulator_id" appearance light
xcrun simctl status_bar "$simulator_id" override \
  --time "9:41" \
  --dataNetwork wifi \
  --wifiMode active \
  --wifiBars 3 \
  --cellularMode active \
  --cellularBars 4 \
  --operatorName "" \
  --batteryState charged \
  --batteryLevel 100

log "Build and install Debug preview app"
(cd "$ios_root" && xcodegen generate)
(cd "$repo_root" && xcodebuild build \
  -project iosApp/XJTUToolboxIOS.xcodeproj \
  -scheme XJTUToolboxIOS \
  -configuration Debug \
  -destination "platform=iOS Simulator,id=$simulator_id" \
  -derivedDataPath "$derived_data_path")
[[ -d "$app_path" ]] || fail "built app not found: $app_path"
xcrun simctl install "$simulator_id" "$app_path"

log "Capture preview-only App Store screenshots"
capture "01-home.png" \
  -XJTURequireFreshLogin -XJTUPreviewAutoLogin -XJTUStartTab home
capture "02-schedule-courses.png" \
  -XJTURequireFreshLogin -XJTUPreviewAutoLogin -XJTUStartTab schedule -XJTUStartScheduleView courses
capture "03-schedule-exams.png" \
  -XJTURequireFreshLogin -XJTUPreviewAutoLogin -XJTUStartTab schedule -XJTUStartScheduleView exams
capture "04-grades.png" \
  -XJTURequireFreshLogin -XJTUPreviewAutoLogin -XJTUStartTab tools -XJTUStartTool grades
capture "05-campus-card.png" \
  -XJTURequireFreshLogin -XJTUPreviewAutoLogin -XJTUStartTab tools -XJTUStartTool campusCard
capture "06-library-seats.png" \
  -XJTURequireFreshLogin -XJTUPreviewAutoLogin -XJTUStartTab tools -XJTUStartTool librarySeats
capture "07-coupons.png" \
  -XJTURequireFreshLogin -XJTUPreviewAutoLogin -XJTUStartTab tools -XJTUStartTool coupons
capture "08-empty-rooms.png" \
  -XJTURequireFreshLogin -XJTUPreviewAutoLogin -XJTUStartTab tools -XJTUStartTool emptyRooms
capture "09-school-courses.png" \
  -XJTURequireFreshLogin -XJTUPreviewAutoLogin -XJTUStartTab tools -XJTUStartTool schoolCourses
capture "10-schedule-textbooks.png" \
  -XJTURequireFreshLogin -XJTUPreviewAutoLogin -XJTUStartTab schedule -XJTUStartScheduleView textbooks

cat >"$output_dir/README.txt" <<EOF
岱宗盒子 iOS App Store screenshot candidate set

- Generated from Debug preview dependencies in a new disposable simulator.
- No real CAS, JWAPP, ncard, library, coupon, or WebVPN request is made.
- No existing simulator credentials or app container are read.
- App launch language and locale are fixed to $screenshot_language / $screenshot_locale.
- Every PNG was checked against Apple's accepted 6.9-inch portrait dimensions.
- Review every image manually before App Store Connect upload.

Apple screenshot specifications:
https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications
EOF

log "App Store screenshot candidates captured"
echo "output: $output_dir"
cat "$output_dir/manifest.tsv"
