# iOS First-Release Acceptance Matrix

This is the release-facing evidence checklist for the SwiftUI/KMP first release. Fixture tests and preview screenshots are necessary but do not prove a real school-system workflow. Record evidence without credentials, verification codes, tickets, tokens, cookies, or personal data. Use `docs/ios-release-owner-evidence-record.md` as the release-owner template for private owner-account, signing, TestFlight, Pages, and App Store Connect evidence.

## Evidence Levels

- `Automated`: repeatable test or CI result.
- `Preview`: simulator behavior using preview-safe dependencies.
- `Public`: live public-data behavior without an account.
- `Owner`: account owner performs the sensitive step; logs/screenshots remain sanitized.
- `Release`: archived Release build or TestFlight build, not a Debug validation scheme.

## Mandatory Gates

| Area | Scenario | Required evidence | Current status |
|---|---|---|---|
| Build | Shared Gradle regression | `./gradlew :shared:check` | Passed locally on 2026-06-19 as part of `iosApp/scripts/run-automated-release-gate.sh` |
| Build | Generated iOS project builds and archives | `xcodegen generate`, simulator tests/builds, then unsigned generic-device Release archive validation | Passed locally on 2026-06-19 with macOS 26.4.1 and Xcode 26.2. The current unsigned archive at `/tmp/XJTUToolboxIOS-goal.xcarchive` is `1.0.0 (1)`, iphoneos arm64, iPhone-only, with matching dSYM. The remote full archive validator now lives in `iOS Release Gate` for `main` and manual release-owner runs. The first owner-fork run on 2026-06-19 failed inside the automated release gate on GitHub's Xcode 16.4 runner after 12m29s; signed-in logs are needed to isolate the archive-stage root cause |
| Build | iOS XCTest suite | `xcodebuild test` on generated project | Local suite passed 38/38 on 2026-06-19: 31 unit/contract tests plus 7 preview UI smoke tests on iPhone 16 / iOS 18.6; result bundle: `/tmp/XJTUToolboxIOS-goal-tests.xcresult`. Coverage includes KMP auth/feature bridge contracts, cache policy, authenticated-state error cleanup, settings-facing access-mode changes, Debug-only validation/access-mode launch policy, automatic site verification, release-log policy, preview auth challenges, recovery states, and the main first-release surfaces. The remote path-filtered `iOS Validation` workflow runs the 31 unit/contract tests and uploads `ios-xcresult` on failure; the 7 UI smoke tests remain in the full `iOS Release Gate`. Passing GitHub evidence is pending |
| Build | Version and build-number policy | `iosApp/project.yml` inspection plus archive consistency check | `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` centralized as `1.0.0 (1)`; archive validator enforces major.minor.patch and positive build number |
| Build | App icon asset packaging | `Assets.xcassets` inspection plus archive validator | AppIcon generated from the existing Android launcher asset and bundled 2026-06-14. Current unsigned Release archive contains `Assets.car`, `AppIcon60x60@2x.png`, and processed `CFBundleIconName=AppIcon`; App Store Connect visual review pending |
| Security | Debug default launch is preview-safe and network-free | Preview launch plus dependency-mode check | Implemented and covered by launch-policy XCTest; UI smoke also proves default Debug launch stays on login screen |
| Security | Release has no preview data/debug auth logging | Launch-policy XCTest plus clean Release runtime/archive inspection | Policy tests passed and Debug/Release builds passed 2026-06-14. Post-fix simulator Release smoke stayed on real CAS login despite preview/public/debug launch args, and no `[DEBUG-AUTH-2FA]` log appeared. Current unsigned Release archive gate also proved the Debug-auth marker and known unreviewed crash-SDK markers absent. Signed archive/TestFlight runtime inspection pending |
| Security | ATS is strict except the library direct-HTTP host and its required OAuth handoff redirect host | Generated and built `Info.plist` inspection plus live library-seat validation | Passed locally for `rg.lib.xjtu.edu.cn`; 2026-06-16 live validation showed the library login chain also redirects through `http://org.xjtu.edu.cn`, so the scoped `org.xjtu.edu.cn` exception and archive-validator check were added. The 2026-06-19 unsigned archive validation passed with both scoped exceptions and no global arbitrary loads |
| Privacy | Privacy manifest and required-reason APIs | `PrivacyInfo.xcprivacy` inspection plus Release bundle check | Manifest added and bundled 2026-06-14. It declares no tracking, no tracking domains, no collected data types, and UserDefaults required-reason API `CA92.1`. Local App Store privacy review is documented; website privacy/support pages were built and browser-verified locally on 2026-06-15, and the `Website Pages` workflow now validates them remotely before `main` deployment. App Store Connect entry, successful public deployment/final URL verification, and release-owner confirmation are pending |
| Auth | Fresh CAS login | Owner validation: success plus sanitized state evidence | Previously passed; repeated 2026-06-15 with owner-entered credentials in `XJTUToolboxIOS-RealLoginValidation`, then reused saved credentials in `XJTUToolboxIOS-RealFirstReleaseCore` |
| Auth | Saved credential restore and relaunch | Owner validation | Previously passed |
| Auth | Captcha, MFA/Safety Verify, account choice | Preview each; owner validation when naturally presented | Preview UI smoke now completes captcha, MFA, and account-choice flows end to end. Deterministic AuthStore regression proves explicit fresh login clears stale authenticated-session context before account choice. Owner validation remains only when these challenges naturally appear on the real CAS account |
| Auth | Site expiry stays inside app shell | Owner validation of inline “补授权” and resumed feature load | Previously passed; site verification remains user-driven by default. AuthStore XCTest covers site-verification staying inside an authenticated shell plus opt-in automatic site verification requiring both authenticated shell context and an explicit Debug flag. Diagnostic auto-attempts use the shared `AuthManager.beginSiteVerification` path and are limited to at least 1 second between attempts and one attempt per site per 5-minute window |
| Auth | Access-mode switch invalidates sessions | Owner validation for supported direct/WebVPN routes | Automated coverage added: shared `DefaultAuthManager` proves access-mode changes invalidate existing site sessions, and iOS `AuthStore` proves settings-facing changes forward to the auth manager without duplicate invalidations. 2026-06-16 controlled Debug runner forced `IOS_REAL_FEATURE_ACCESS_MODE=normal` and completed the read-only real sequence; forced `webvpn` authenticated the library site but then hit the Debug auto-site-verification rate limit before feature reads completed. Evidence: `/tmp/xjtu-real-priority/real-feature-results-access-normal.txt`, `/tmp/xjtu-real-priority/real-feature-results-access-webvpn.txt` |
| Cache | Only schedule/notices/empty rooms persist stale data | Unit tests plus UserDefaults inspection | Unit coverage added |
| Cache | Release cannot read Debug/preview stale feature cache | Cache namespace tests plus clean Release runtime smoke | Build/mode cache-prefix tests passed 2026-06-14. Clean Release `UserDefaults` was empty; Debug preview schedule smoke followed by Release overlay launch returned to real CAS login instead of preview shell. Preserved-container stale-cache proof on signed/device or TestFlight pending |
| Cache | Clear cache does not remove Keychain credentials | Unit/manual validation | Unit coverage added for the settings-facing `FeatureStore.clearCachedData()` path preserving a test-only Keychain credential |
| Recovery | Retry, empty, malformed, offline, and stale fallback states | Automated/preview/public evidence per feature | Partial but stronger; AuthStore XCTest covers logout cleanup, session-context persistence, site-verification-without-shell behavior, and opt-in automatic site-verification guards/rate limits; KMP bridge contracts cover preview-backed auth/feature mappings; preview UI smoke covers auto-login schedule rendering plus deterministic empty-state and first-failure retry recovery. Shared fixture tests and cache tests cover malformed/offline/stale fallback behavior below the UI; full per-feature live recovery matrix remains pending |
| Release | Signed archive, privacy disclosures, versioning, visual assets, TestFlight | Release evidence | Current unsigned generic-device archive and release checklist passed locally on 2026-06-19, including AppIcon packaging, privacy/ATS policy, Debug-marker checks, and matching dSYM. A repeatable signed archive/optional export gate exists; its first local attempt found a valid development identity but stopped because Xcode lacks an authenticated team account and matching provisioning profile. Signed archive/export, App Store Connect visual/privacy entry, public URL deployment, final screenshots, and TestFlight remain pending |

## Feature Matrix

Each real feature must cover success, legitimate empty state, auth expiry/site verification where applicable, bounded paging/action behavior, and user-facing failure recovery.

| Feature | Fixture/preview | Live success | Empty/error/reauth | Paging/action | Release build |
|---|---|---|---|---|---|
| Homepage aggregation | Yes | Partial | Partial-campus-card degradation passed | N/A | No |
| Schedule/exams/textbooks | Yes | Yes | Site reauth and textbook degradation passed | Bounded textbook traversal | No |
| Grades and grade detail | Yes | Grade list and detail passed | Reauth covered | Detail is user-triggered and returned 200 in real-core validation | No |
| Campus card | Yes | Yes | Site reauth passed | Total-aware user paging passed | No |
| Public notices | Yes | Public repository available | Source failure and safe-link behavior covered | Total-aware user paging | No |
| Empty rooms | Yes | Public repository available | Deterministic preview empty state now covered; public/live filter matrix still needs final acceptance run | Query is user-filtered | No |
| Library seats | Yes | Yes: 2026-06-16 Debug real-feature runs loaded `areas=13 seats=330` with available-seat counts and the UI rendered the same real seat summary | ATS library-login chain validated with scoped direct-HTTP exceptions; booking absence handled (`bookingPresent=false`) | Book/swap actions not live-validated | No |
| Coupons | Yes | Yes: 2026-06-16 Debug real-feature run completed coupon site verification, resumed the pending feature, and validated filters: `available=2`, `usable=0`, `usedUp=12`, `expired=15` | Automatic Debug site verification resumed with `errorPresent=false`; all current filter totals fit page 1 (`canLoadMore=false`) | True multi-page coupon account not live-validated | No |
| School-wide course search | Yes | Yes: 2026-06-16 Debug real-feature runs loaded page 1 (`loaded=20 total=4490`), page 2 (`loaded=40 total=4490`), and representative filters (`courseNameCommon total=66`, `campus1 total=3791`, `weekday1 total=1304`, `teacherNoMatch total=0`) | Site reauth not triggered in these school-course runs | Combined exhaustive filter matrix not live-validated | No |

## Execution Order

1. Run automated Gradle and iOS tests on every checkpoint.
2. Run preview/public-data scenarios without credentials.
3. Ask the account owner to perform only the required sensitive interactions for real-core validation.
4. Fix evidence-backed failures and rerun the affected row.
5. Repeat mandatory gates against an archived Release/TestFlight build before declaring release readiness.
