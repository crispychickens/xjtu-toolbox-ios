#!/bin/bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
ios_root="$repo_root/iosApp"
temp_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
temp_root="${temp_root%/}"

development_team="${IOS_DEVELOPMENT_TEAM:-}"
archive_path="${IOS_SIGNED_ARCHIVE_PATH:-$temp_root/XJTUToolboxIOS-signed.xcarchive}"
allow_provisioning_updates="${IOS_ALLOW_PROVISIONING_UPDATES:-1}"
export_options_plist="${IOS_EXPORT_OPTIONS_PLIST:-}"
export_path="${IOS_EXPORT_PATH:-$temp_root/XJTUToolboxIOS-export}"

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

require_command bash
require_command security
require_command xcodebuild
require_command xcodegen

[[ "$development_team" =~ ^[A-Z0-9]{10}$ ]] || fail "IOS_DEVELOPMENT_TEAM must be a 10-character Apple team id"
[[ "$allow_provisioning_updates" == "0" || "$allow_provisioning_updates" == "1" ]] || fail "IOS_ALLOW_PROVISIONING_UPDATES must be 0 or 1"
require_absolute_path "IOS_SIGNED_ARCHIVE_PATH" "$archive_path"
require_path_suffix "IOS_SIGNED_ARCHIVE_PATH" "$archive_path" ".xcarchive"
if [[ -n "$export_options_plist" ]]; then
  require_absolute_path "IOS_EXPORT_OPTIONS_PLIST" "$export_options_plist"
  [[ -f "$export_options_plist" ]] || fail "export options plist does not exist: $export_options_plist"
  require_absolute_path "IOS_EXPORT_PATH" "$export_path"
fi

valid_identities="$(security find-identity -v -p codesigning)"
printf '%s\n' "$valid_identities"
grep -Eq '[0-9]+ valid identities found' <<<"$valid_identities" || fail "could not inspect code-signing identities"
if grep -q '0 valid identities found' <<<"$valid_identities"; then
  fail "no valid Apple code-signing identity is available in the login keychain"
fi

log "Validate iOS shell scripts"
for shell_script in "$ios_root"/scripts/*.sh; do
  bash -n "$shell_script"
done

log "Generate iOS project"
(cd "$ios_root" && xcodegen generate)

archive_args=(
  archive
  -project "$ios_root/XJTUToolboxIOS.xcodeproj"
  -scheme XJTUToolboxIOS
  -configuration Release
  -destination "generic/platform=iOS"
  -archivePath "$archive_path"
  DEVELOPMENT_TEAM="$development_team"
  CODE_SIGN_STYLE=Automatic
)
if [[ "$allow_provisioning_updates" == "1" ]]; then
  archive_args+=(-allowProvisioningUpdates)
fi

log "Build signed Release archive"
rm -rf "$archive_path"
if ! xcodebuild "${archive_args[@]}"; then
  fail "signed archive failed; confirm Xcode is signed into team $development_team and an App Development provisioning profile exists for com.xjtu.toolbox.ios"
fi

log "Validate signed Release archive"
REQUIRE_SIGNED=1 "$script_dir/validate-release-archive.sh" "$archive_path"

if [[ -n "$export_options_plist" ]]; then
  export_args=(
    -exportArchive
    -archivePath "$archive_path"
    -exportPath "$export_path"
    -exportOptionsPlist "$export_options_plist"
  )
  if [[ "$allow_provisioning_updates" == "1" ]]; then
    export_args+=(-allowProvisioningUpdates)
  fi

  log "Export signed archive"
  rm -rf "$export_path"
  xcodebuild "${export_args[@]}"
  ipa_path="$(find "$export_path" -maxdepth 1 -type f -name '*.ipa' -print -quit)"
  [[ -n "$ipa_path" ]] || fail "archive export completed without producing an IPA in $export_path"
  echo "  exported IPA: $ipa_path"
fi

log "iOS signed release gate passed"
echo "  team: $development_team"
echo "  signed archive: $archive_path"
