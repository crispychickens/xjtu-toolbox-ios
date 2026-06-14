#!/bin/bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
ios_root="$repo_root/iosApp"
temp_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
temp_root="${temp_root%/}"

preferred_simulator="${IOS_PREFERRED_SIMULATOR:-iPhone 16}"
simulator_id="${IOS_SIMULATOR_ID:-}"
result_bundle="${IOS_RESULT_BUNDLE:-$temp_root/XJTUToolboxIOS-tests.xcresult}"
archive_path="${IOS_ARCHIVE_PATH:-$temp_root/XJTUToolboxIOS.xcarchive}"

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

require_absolute_path() {
  [[ "$2" == /* ]] || fail "$1 must be an absolute path: $2"
}

require_path_suffix() {
  [[ "$2" == *"$3" ]] || fail "$1 must end with $3: $2"
}

select_simulator() {
  if [[ -n "$simulator_id" ]]; then
    echo "id=$simulator_id"
    echo "name=<provided>"
    echo "runtime=<provided>"
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
            candidates.append((name != preferred, name, runtime, device))

if not candidates:
    raise SystemExit("No available iPhone simulator")

_, _, runtime, device = sorted(candidates, key=lambda item: (item[0], item[1], item[2]))[0]
print("id={}".format(device["udid"]))
print("name={}".format(device["name"]))
print("runtime={}".format(runtime))
' "$preferred_simulator"
}

require_command java
require_command xcodebuild
require_command xcodegen
require_command xcrun
require_command python3

require_absolute_path "IOS_RESULT_BUNDLE" "$result_bundle"
require_absolute_path "IOS_ARCHIVE_PATH" "$archive_path"
require_path_suffix "IOS_RESULT_BUNDLE" "$result_bundle" ".xcresult"
require_path_suffix "IOS_ARCHIVE_PATH" "$archive_path" ".xcarchive"

log "Tool versions"
sw_vers
xcodebuild -version
java -version
"$repo_root/gradlew" --version
xcodegen --version
xcrun simctl list runtimes available

log "Run shared checks"
(cd "$repo_root" && ./gradlew :shared:check)

log "Generate iOS project"
(cd "$ios_root" && xcodegen generate)

log "Select simulator"
selection="$(select_simulator)"
printf '%s\n' "$selection"
selected_simulator_id="$(printf '%s\n' "$selection" | awk -F= '$1 == "id" {print $2}')"
[[ -n "$selected_simulator_id" ]] || fail "simulator selection did not return an id"

log "Boot selected simulator"
xcrun simctl boot "$selected_simulator_id" || true
xcrun simctl bootstatus "$selected_simulator_id" -b

log "Run iOS tests"
rm -rf "$result_bundle"
(cd "$ios_root" && xcodebuild test \
  -project XJTUToolboxIOS.xcodeproj \
  -scheme XJTUToolboxIOS \
  -destination "platform=iOS Simulator,id=$selected_simulator_id" \
  -resultBundlePath "$result_bundle" \
  -parallel-testing-enabled NO)

log "Build unsigned Release archive"
rm -rf "$archive_path"
(cd "$repo_root" && xcodebuild archive \
  -project iosApp/XJTUToolboxIOS.xcodeproj \
  -scheme XJTUToolboxIOS \
  -configuration Release \
  -destination "generic/platform=iOS" \
  -archivePath "$archive_path" \
  CODE_SIGNING_ALLOWED=NO)

log "Validate unsigned Release archive"
"$repo_root/iosApp/scripts/validate-release-archive.sh" "$archive_path"

log "iOS automated release gate passed"
echo "  XCTest result bundle: $result_bundle"
echo "  unsigned archive: $archive_path"
