#!/bin/bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
ios_root="$repo_root/iosApp"
temp_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
temp_root="${temp_root%/}"

preferred_simulator="${IOS_PREFERRED_SIMULATOR:-iPhone 16}"
simulator_id="${IOS_SIMULATOR_ID:-}"
timeout_seconds="${IOS_REAL_FEATURE_TIMEOUT_SECONDS:-900}"
poll_interval_seconds="${IOS_REAL_FEATURE_POLL_INTERVAL_SECONDS:-5}"
skip_build="${IOS_REAL_FEATURE_SKIP_BUILD:-0}"
skip_install="${IOS_REAL_FEATURE_SKIP_INSTALL:-0}"
derived_data_path="${IOS_REAL_FEATURE_DERIVED_DATA_PATH:-$temp_root/XJTUToolboxIOS-real-feature-derived-data}"
app_path="${IOS_REAL_FEATURE_APP_PATH:-$derived_data_path/Build/Products/Debug-iphonesimulator/XJTUToolboxIOS.app}"
results_path="${IOS_REAL_FEATURE_RESULTS_PATH:-}"
bundle_id="com.xjtu.toolbox.ios"
diagnostic_key="debugRealFeatureValidationResults"
pending_key="debugRealFeatureValidationPendingFeature"

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

select_simulator() {
  if [[ -n "$simulator_id" ]]; then
    echo "$simulator_id"
    return
  fi

  xcrun simctl list devices available -j | python3 -c '
import json
import sys

preferred = sys.argv[1]
data = json.load(sys.stdin)
candidates = []

for runtime, devices in data["devices"].items():
    if "iOS" not in runtime:
        continue
    for device in devices:
        name = device.get("name", "")
        if device.get("isAvailable") and name.startswith("iPhone"):
            candidates.append((name != preferred, name, runtime, device["udid"]))

if not candidates:
    raise SystemExit("No available iPhone simulator")

print(sorted(candidates, key=lambda item: (item[0], item[1], item[2]))[0][3])
' "$preferred_simulator"
}

delete_default_key() {
  xcrun simctl spawn "$selected_simulator_id" defaults delete "$bundle_id" "$1" >/dev/null 2>&1 || true
}

read_results() {
  xcrun simctl spawn "$selected_simulator_id" defaults read "$bundle_id" "$diagnostic_key" 2>/dev/null || true
}

require_command xcodebuild
require_command xcodegen
require_command xcrun
require_command python3

[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ ]] || fail "IOS_REAL_FEATURE_TIMEOUT_SECONDS must be a positive integer"
[[ "$poll_interval_seconds" =~ ^[1-9][0-9]*$ ]] || fail "IOS_REAL_FEATURE_POLL_INTERVAL_SECONDS must be a positive integer"
if [[ -n "$results_path" ]]; then
  [[ "$results_path" == /* ]] || fail "IOS_REAL_FEATURE_RESULTS_PATH must be an absolute path: $results_path"
  mkdir -p "$(dirname "$results_path")"
  : >"$results_path"
fi

log "Select and boot simulator"
selected_simulator_id="$(select_simulator)"
echo "simulator: $selected_simulator_id"
xcrun simctl boot "$selected_simulator_id" >/dev/null 2>&1 || true
xcrun simctl bootstatus "$selected_simulator_id" -b

if [[ "$skip_build" != "1" ]]; then
  log "Generate iOS project and build Debug real-first-release app"
  (cd "$ios_root" && xcodegen generate)
  (cd "$repo_root" && xcodebuild build \
    -project iosApp/XJTUToolboxIOS.xcodeproj \
    -scheme XJTUToolboxIOS-RealFirstReleaseCore \
    -configuration Debug \
    -destination "platform=iOS Simulator,id=$selected_simulator_id" \
    -derivedDataPath "$derived_data_path")
fi

if [[ "$skip_install" != "1" ]]; then
  log "Install app without reading credentials"
  [[ -d "$app_path" ]] || fail "built app not found: $app_path"
  xcrun simctl install "$selected_simulator_id" "$app_path"
fi

log "Start sanitized real-feature diagnostics"
xcrun simctl terminate "$selected_simulator_id" "$bundle_id" >/dev/null 2>&1 || true
delete_default_key "$diagnostic_key"
delete_default_key "$pending_key"
xcrun simctl launch "$selected_simulator_id" "$bundle_id" \
  -XJTURealFirstReleaseCore \
  -XJTURealFeatureValidation \
  -XJTUAutoSiteVerification

log "Poll sanitized diagnostic results"
echo "If the app is on the login screen, enter credentials manually. The runner starts after authentication succeeds."
deadline=$((SECONDS + timeout_seconds))
last_results=""

while (( SECONDS < deadline )); do
  results="$(read_results)"
  if [[ -z "$results" ]]; then
    echo "waiting: no diagnostic results yet"
  elif [[ "$results" != "$last_results" ]]; then
    printf '%s\n' "$results"
    if [[ -n "$results_path" ]]; then
      printf '%s\n' "$results" >"$results_path"
    fi
    last_results="$results"
  fi

  if [[ "$results" == *"status=finished"* ]]; then
    log "Real-feature diagnostics finished"
    if [[ -n "$results_path" ]]; then
      echo "  sanitized results: $results_path"
    fi
    exit 0
  fi
  if [[ "$results" == *"status=failed errorPresent=true"* ]]; then
    fail "real-feature diagnostics reported a sanitized feature failure"
  fi

  sleep "$poll_interval_seconds"
done

fail "timed out waiting for sanitized real-feature diagnostics"
