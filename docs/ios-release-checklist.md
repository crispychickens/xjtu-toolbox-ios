# iOS Release Checklist

Use this checklist for every build promoted beyond local preview validation. The unsigned archive gate is repeatable locally and in CI; signed archive, export, App Store Connect, and TestFlight gates require the release owner's Apple Developer credentials.

## Version Policy

- `iosApp/project.yml` is the source of truth for `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION`.
- `MARKETING_VERSION` uses `major.minor.patch`.
- `CURRENT_PROJECT_VERSION` is a positive integer and must increase for every App Store Connect upload.
- The first iOS release is intentionally iPhone-only. Do not add iPad to `TARGETED_DEVICE_FAMILY` until its orientations and layouts have their own acceptance evidence.
- Run `xcodegen generate` after changing project settings and commit the generated Xcode project with its source configuration.

## Automated Gate

Run the full shared and iOS regression suite, then produce and inspect a device archive without requiring local signing credentials:

```bash
iosApp/scripts/run-automated-release-gate.sh
```

The script selects an available iPhone simulator, preferring `iPhone 16`, and writes the XCTest result bundle and unsigned archive under `$RUNNER_TEMP`, `$TMPDIR`, or `/tmp`. Override these when needed:

```bash
IOS_PREFERRED_SIMULATOR="iPhone 16" \
IOS_RESULT_BUNDLE=/tmp/XJTUToolboxIOS-tests.xcresult \
IOS_ARCHIVE_PATH=/tmp/XJTUToolboxIOS.xcarchive \
iosApp/scripts/run-automated-release-gate.sh
```

The manual equivalent is:

```bash
bash -n iosApp/scripts/*.sh
./gradlew :shared:check
(cd iosApp && xcodegen generate)
xcodebuild test \
  -project iosApp/XJTUToolboxIOS.xcodeproj \
  -scheme XJTUToolboxIOS \
  -destination 'platform=iOS Simulator,name=iPhone 16,OS=18.6'

xcodebuild archive \
  -project iosApp/XJTUToolboxIOS.xcodeproj \
  -scheme XJTUToolboxIOS \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath /tmp/XJTUToolboxIOS.xcarchive \
  CODE_SIGNING_ALLOWED=NO

iosApp/scripts/validate-release-archive.sh /tmp/XJTUToolboxIOS.xcarchive
```

Remote validation is split by feedback speed:

- `.github/workflows/ios-validation.yml` runs only for relevant iOS/KMP paths on pull requests and pushes to `main`/`ios-kmp-migration`, plus manual `workflow_dispatch`. It runs `:shared:check` on Linux in parallel with shell syntax, XcodeGen, and the 31 iOS unit/contract tests on macOS.
- `.github/workflows/ios-release-gate.yml` runs on `main` pushes and manual `workflow_dispatch`; it invokes `iosApp/scripts/run-automated-release-gate.sh`, including all 38 XCTest cases (31 unit/contract plus 7 UI smoke), the unsigned generic-device Release archive, and archive validator. Use this workflow for release-owner evidence before signing/TestFlight work.

Both workflows upload the `.xcresult` bundle as `ios-xcresult` on failure when it exists.

Remote baseline on 2026-06-19: [`iOS Validation #3`](https://github.com/crispychickens/xjtu-toolbox-ios/actions/runs/27811669219) passed at `cf307cd` in 6m54s. The earlier no-archive `#2` passed all 38 simulator tests in 11m38s, isolating the remaining Xcode 16.4 failure to the release archive/validator segment.

The archive validator checks:

- iOS shell-script syntax before long-running build/test/archive work;
- bundle identity, `major.minor.patch` marketing version, positive build number, minimum iOS version, and iPhone-only device family;
- Release device platform and arm64 executable;
- compiled `AppIcon` asset in `Assets.car`, generated AppIcon PNG, and processed `CFBundleIcons` metadata;
- privacy manifest declarations and scoped ATS exceptions for the library-seat HTTP entry plus its `org.xjtu.edu.cn` OAuth handoff redirect;
- absence of the debug-auth logging marker and known unreviewed crash-reporting SDK markers;
- matching app executable and dSYM UUIDs;
- signing identity/team and `codesign` verification when invoked with `REQUIRE_SIGNED=1`.

Use `docs/ios-crash-log-policy.md` for allowed Debug diagnostic metadata, Release restrictions, symbolication, and incident handling.
Use `docs/ios-app-store-privacy-review.md` for the local App Store privacy-questionnaire evidence. The publishable website pages live in `website/privacy.md` and `website/support.md`; `docs/ios-privacy-policy-draft.md` keeps the review copy for the privacy-policy text.
Use `docs/ios-app-store-listing-draft.md` as the local starting point for App Store listing copy, screenshot selection, and review notes.
Use `docs/ios-release-owner-evidence-record.md` as the release-owner template for recording private owner-account, signing, TestFlight, Pages, and App Store Connect evidence without committing secrets or personal data.
The `.github/workflows/website-pages.yml` workflow validates the VitePress website on relevant pull requests and `ios-kmp-migration` pushes. It deploys only from `main`; after the first successful `main` deployment, verify the expected public privacy/support URLs before entering them in App Store Connect.
Use `iosApp/scripts/capture-app-store-screenshots.sh` to create a Simplified-Chinese, preview-only 6.9-inch iPhone screenshot candidate set in a disposable simulator. The script validates accepted portrait dimensions and avoids existing simulator containers/credentials. The 2026-06-15 candidate set passed local dimension and visual review; the release owner must still repeat the review and capture if the final signed/TestFlight UI differs.

## Local Live-Account Diagnostic Gate

Use `iosApp/scripts/run-real-feature-validation.sh` only for controlled Debug validation with an owner-operated account. The script builds and launches `-XJTURealFirstReleaseCore -XJTURealFeatureValidation -XJTUAutoSiteVerification`, waits while the owner enters credentials if needed, and polls only the sanitized `debugRealFeatureValidationResults` key. Automatic site verification is Debug-only, limited to one attempt per site per 5-minute window, and falls back to the visible user-driven action. The runner probes library-seat read state, coupon load/filter states, and school-course first/next-page plus representative filter search state without performing booking, swap, or coupon actions. Set `IOS_REAL_FEATURE_RESULTS_PATH=/absolute/path/results.txt` when a local evidence artifact is needed; the script writes only the sanitized result lines to that file. Set `IOS_REAL_FEATURE_ACCESS_MODE=automatic`, `normal`, or `webvpn` to force the Debug launch access mode for direct/WebVPN route validation; leave it unset to use the app's persisted/default mode. `IOS_REAL_FEATURE_SKIP_BUILD=1 IOS_REAL_FEATURE_SKIP_INSTALL=1` is for reusing an already installed diagnostic build. Do not record account names, passwords, verification codes, cookies, CAS tickets, OAuth tokens, response bodies, screenshots containing personal data, or full app preference dumps.

## Signed Archive Gate

Before the first TestFlight upload:

1. Confirm the final App Store bundle identifier and assign the Apple Developer team in a release-owner-only configuration.
2. Confirm the distribution certificate and App Store provisioning profile are valid.
3. Sign into the release team in Xcode, then run `IOS_DEVELOPMENT_TEAM=<team-id> iosApp/scripts/run-signed-release-gate.sh`. The script injects the team only for the command, archives the main `XJTUToolboxIOS` Release scheme with automatic signing, and runs `REQUIRE_SIGNED=1` archive validation.
4. To export in the same gate, also set `IOS_EXPORT_OPTIONS_PLIST=/absolute/path/ExportOptions.plist`; use only a release-owner-approved export configuration.
5. Record sanitized archive/export evidence without certificates, profiles, account details, or credentials.
6. Confirm App Store Connect accepts the bundle identifier, version, build number, privacy manifest, and export.

## App Store And TestFlight Gate

1. Supply App Store listing assets from `docs/ios-app-store-listing-draft.md` and replace the bundled AppIcon only if the owner provides a final brand asset.
2. Complete App Store privacy questionnaire answers from `docs/ios-app-store-privacy-review.md`, then have the release owner confirm whether XJTU school-system traffic is treated as real-time user-request servicing or third-party partner collection.
3. Publish the privacy policy/support URLs from `website/privacy.md` and `website/support.md`, then provide review notes without credentials or personal data. With the current VitePress base and default non-clean URLs, the expected deployed routes are `/xjtu-toolbox-android/privacy.html` and `/xjtu-toolbox-android/support.html` unless the release owner changes the final domain, base path, or enables `cleanUrls`.
4. Upload the signed build and wait for App Store Connect processing.
5. Install from TestFlight on a real device and execute `docs/ios-first-release-acceptance.md`.
6. Verify upgrade behavior from the previous TestFlight build, including no Debug/preview stale-cache read.
7. Verify `docs/ios-crash-log-policy.md` on the TestFlight build and inspect device logs for credentials, cookies, tickets, verification codes, and debug-auth output.
8. Promote only after every mandatory acceptance gate has release evidence.
