#!/bin/bash

set -euo pipefail

archive_path="${1:-}"
expected_bundle_id="${EXPECTED_BUNDLE_ID:-com.xjtu.toolbox.ios}"
require_signed="${REQUIRE_SIGNED:-0}"

fail() {
  echo "error: $*" >&2
  exit 1
}

require_file() {
  [[ -f "$1" ]] || fail "missing required file: $1"
}

plist_value() {
  plutil -extract "$2" raw "$1" 2>/dev/null || fail "missing plist key '$2' in $1"
}

plist_json_value() {
  plutil -extract "$2" json -o - "$1" 2>/dev/null || fail "missing or unreadable plist key '$2' in $1"
}

[[ -n "$archive_path" ]] || fail "usage: $0 <path-to-xcarchive>"
[[ -d "$archive_path" ]] || fail "archive does not exist: $archive_path"

archive_info="$archive_path/Info.plist"
app_path="$archive_path/Products/Applications/XJTUToolboxIOS.app"
app_info="$app_path/Info.plist"
privacy_manifest="$app_path/PrivacyInfo.xcprivacy"
assets_car="$app_path/Assets.car"
app_icon_png="$app_path/AppIcon60x60@2x.png"
executable="$app_path/XJTUToolboxIOS"
dsym="$archive_path/dSYMs/XJTUToolboxIOS.app.dSYM"

require_file "$archive_info"
require_file "$app_info"
require_file "$privacy_manifest"
require_file "$assets_car"
require_file "$app_icon_png"
require_file "$executable"
require_file "$dsym/Contents/Resources/DWARF/XJTUToolboxIOS"

bundle_id="$(plist_value "$app_info" CFBundleIdentifier)"
marketing_version="$(plist_value "$app_info" CFBundleShortVersionString)"
build_number="$(plist_value "$app_info" CFBundleVersion)"
primary_icon="$(plist_value "$app_info" CFBundleIcons.CFBundlePrimaryIcon.CFBundleIconName)"
minimum_os="$(plist_value "$app_info" MinimumOSVersion)"
platform_name="$(plist_value "$app_info" DTPlatformName)"
device_family="$(plist_json_value "$app_info" UIDeviceFamily)"

[[ "$bundle_id" == "$expected_bundle_id" ]] || fail "unexpected bundle id: $bundle_id"
[[ "$marketing_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "marketing version must use major.minor.patch: $marketing_version"
[[ "$build_number" =~ ^[1-9][0-9]*$ ]] || fail "build number must be a positive integer: $build_number"
[[ "$primary_icon" == "AppIcon" ]] || fail "processed Info.plist must declare primary AppIcon: $primary_icon"
[[ "$minimum_os" == "16.0" ]] || fail "unexpected minimum iOS version: $minimum_os"
[[ "$platform_name" == "iphoneos" ]] || fail "archive is not an iphoneos build: $platform_name"
[[ "$device_family" == "[1]" ]] || fail "first release must remain iPhone-only: $device_family"

archive_bundle_id="$(plist_value "$archive_info" ApplicationProperties.CFBundleIdentifier)"
archive_version="$(plist_value "$archive_info" ApplicationProperties.CFBundleShortVersionString)"
archive_build="$(plist_value "$archive_info" ApplicationProperties.CFBundleVersion)"
archive_architectures="$(plist_json_value "$archive_info" ApplicationProperties.Architectures)"

[[ "$archive_bundle_id" == "$bundle_id" ]] || fail "archive and app bundle ids differ"
[[ "$archive_version" == "$marketing_version" ]] || fail "archive and app marketing versions differ"
[[ "$archive_build" == "$build_number" ]] || fail "archive and app build numbers differ"
[[ "$archive_architectures" == '["arm64"]' ]] || fail "archive architecture must be arm64: $archive_architectures"
[[ "$(lipo -archs "$executable")" == "arm64" ]] || fail "app executable must contain only arm64"
if grep -aRFq '[DEBUG-AUTH-2FA]' "$app_path"; then
  fail "Release app bundle contains the debug-auth logging marker"
fi
for crash_sdk in Sentry Crashlytics FirebaseCrashlytics PLCrashReporter; do
  if grep -aRFq "$crash_sdk" "$app_path"; then
    fail "Release app bundle contains an unreviewed crash-reporting SDK marker: $crash_sdk"
  fi
done

plutil -lint "$privacy_manifest" >/dev/null
[[ "$(plist_value "$privacy_manifest" NSPrivacyTracking)" == "false" ]] || fail "privacy manifest must disable tracking"
[[ "$(plist_json_value "$privacy_manifest" NSPrivacyTrackingDomains)" == "[]" ]] || fail "privacy manifest must not declare tracking domains"
[[ "$(plist_json_value "$privacy_manifest" NSPrivacyCollectedDataTypes)" == "[]" ]] || fail "privacy manifest must not declare collected data types"
[[ "$(plist_value "$privacy_manifest" NSPrivacyAccessedAPITypes.0.NSPrivacyAccessedAPIType)" == "NSPrivacyAccessedAPICategoryUserDefaults" ]] || fail "privacy manifest must declare the UserDefaults API category"
[[ "$(plist_value "$privacy_manifest" NSPrivacyAccessedAPITypes.0.NSPrivacyAccessedAPITypeReasons.0)" == "CA92.1" ]] || fail "privacy manifest must declare UserDefaults reason CA92.1"
if plutil -extract NSPrivacyAccessedAPITypes.1 raw "$privacy_manifest" >/dev/null 2>&1; then
  fail "privacy manifest contains an unreviewed required-reason API category"
fi

if arbitrary_loads="$(plutil -extract NSAppTransportSecurity.NSAllowsArbitraryLoads raw "$app_info" 2>/dev/null)"; then
  [[ "$arbitrary_loads" == "false" ]] || fail "global ATS arbitrary loads must remain disabled"
fi
library_http_allowed="$(/usr/libexec/PlistBuddy -c 'Print :NSAppTransportSecurity:NSExceptionDomains:rg.lib.xjtu.edu.cn:NSExceptionAllowsInsecureHTTPLoads' "$app_info" 2>/dev/null)" || fail "library HTTP ATS exception is missing"
library_subdomains="$(/usr/libexec/PlistBuddy -c 'Print :NSAppTransportSecurity:NSExceptionDomains:rg.lib.xjtu.edu.cn:NSIncludesSubdomains' "$app_info" 2>/dev/null)" || fail "library ATS subdomain policy is missing"
[[ "$library_http_allowed" == "true" ]] || fail "library HTTP ATS exception is missing"
[[ "$library_subdomains" == "false" ]] || fail "library ATS exception must not include subdomains"
library_oauth_http_allowed="$(/usr/libexec/PlistBuddy -c 'Print :NSAppTransportSecurity:NSExceptionDomains:org.xjtu.edu.cn:NSExceptionAllowsInsecureHTTPLoads' "$app_info" 2>/dev/null)" || fail "library OAuth redirect ATS exception is missing"
library_oauth_subdomains="$(/usr/libexec/PlistBuddy -c 'Print :NSAppTransportSecurity:NSExceptionDomains:org.xjtu.edu.cn:NSIncludesSubdomains' "$app_info" 2>/dev/null)" || fail "library OAuth redirect ATS subdomain policy is missing"
[[ "$library_oauth_http_allowed" == "true" ]] || fail "library OAuth redirect ATS exception is missing"
[[ "$library_oauth_subdomains" == "false" ]] || fail "library OAuth redirect ATS exception must not include subdomains"

binary_uuids="$(dwarfdump --uuid "$executable" | awk '{print $2 ":" $3}' | sort)"
dsym_uuids="$(dwarfdump --uuid "$dsym" | awk '{print $2 ":" $3}' | sort)"
[[ -n "$binary_uuids" ]] || fail "app executable has no UUID"
[[ "$binary_uuids" == "$dsym_uuids" ]] || fail "archive dSYM UUID does not match the app executable"

if [[ "$require_signed" == "1" ]]; then
  codesign --verify --deep --strict "$app_path" || fail "signed archive failed codesign verification"
  [[ -n "$(plist_value "$archive_info" ApplicationProperties.SigningIdentity)" ]] || fail "signed archive has no signing identity"
  [[ -n "$(plist_value "$archive_info" ApplicationProperties.Team)" ]] || fail "signed archive has no development team"
fi

echo "Validated iOS archive:"
echo "  bundle: $bundle_id"
echo "  version: $marketing_version ($build_number)"
echo "  platform: $platform_name / arm64 / iPhone-only"
echo "  assets: AppIcon compiled"
echo "  privacy: no tracking or collected data; UserDefaults CA92.1"
echo "  diagnostics: debug-auth logging and unreviewed crash SDK markers absent"
echo "  dSYM: UUID matches app executable"
echo "  signing required: $require_signed"
