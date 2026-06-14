# iOS First-Release Acceptance Matrix

This is the release-facing evidence checklist for the SwiftUI/KMP first release. Fixture tests and preview screenshots are necessary but do not prove a real school-system workflow. Record evidence without credentials, verification codes, tickets, tokens, cookies, or personal data.

## Evidence Levels

- `Automated`: repeatable test or CI result.
- `Preview`: simulator behavior using preview-safe dependencies.
- `Public`: live public-data behavior without an account.
- `Owner`: account owner performs the sensitive step; logs/screenshots remain sanitized.
- `Release`: archived Release build or TestFlight build, not a Debug validation scheme.

## Mandatory Gates

| Area | Scenario | Required evidence | Current status |
|---|---|---|---|
| Build | Full Gradle regression | `./gradlew check` | Passed 2026-06-14 |
| Build | Generated iOS project builds and archives | `xcodegen generate`, simulator tests/builds, then unsigned generic-device Release archive validation | Passed locally 2026-06-14; generic-device archive validated as `1.0.0 (1)`, iphoneos arm64, iPhone-only, with matching dSYM |
| Build | iOS XCTest suite | `xcodebuild test` on generated project | Local suite passed 22/22 on 2026-06-14: 20 unit/contract tests plus 2 preview UI smoke tests. KMP bridge contracts cover auth mapping and every first-release feature method through the generated framework; release-log-policy tests cover structural Debug metadata; remote CI pending |
| Build | Version and build-number policy | `iosApp/project.yml` inspection plus archive consistency check | `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` centralized as `1.0.0 (1)`; archive validator enforces major.minor.patch and positive build number |
| Build | App icon asset packaging | `Assets.xcassets` inspection plus archive validator | AppIcon generated from the existing Android launcher asset and bundled 2026-06-14. Current unsigned Release archive contains `Assets.car`, `AppIcon60x60@2x.png`, and processed `CFBundleIconName=AppIcon`; App Store Connect visual review pending |
| Security | Debug default launch is preview-safe and network-free | Preview launch plus dependency-mode check | Implemented and covered by launch-policy XCTest; UI smoke also proves default Debug launch stays on login screen |
| Security | Release has no preview data/debug auth logging | Launch-policy XCTest plus clean Release runtime/archive inspection | Policy tests passed and Debug/Release builds passed 2026-06-14. Post-fix simulator Release smoke stayed on real CAS login despite preview/public/debug launch args, and no `[DEBUG-AUTH-2FA]` log appeared. Current unsigned Release archive gate also proved the Debug-auth marker and known unreviewed crash-SDK markers absent. Signed archive/TestFlight runtime inspection pending |
| Security | ATS is strict except library direct-HTTP host | Generated and built `Info.plist` inspection | Passed 2026-06-14 |
| Privacy | Privacy manifest and required-reason APIs | `PrivacyInfo.xcprivacy` inspection plus Release bundle check | Manifest added and bundled 2026-06-14. It declares no tracking, no tracking domains, no collected data types, and UserDefaults required-reason API `CA92.1`. Local App Store privacy review and policy draft are documented; App Store Connect entry, public URLs, and release-owner confirmation are pending |
| Auth | Fresh CAS login | Owner validation: success plus sanitized state evidence | Previously passed |
| Auth | Saved credential restore and relaunch | Owner validation | Previously passed |
| Auth | Captcha, MFA/Safety Verify, account choice | Preview each; owner validation when naturally presented | Partial; deterministic AuthStore regression proves explicit fresh login clears stale authenticated-session context before account choice |
| Auth | Site expiry stays inside app shell | Owner validation of inline “补授权” and resumed feature load | Previously passed; AuthStore XCTest now covers site-verification staying inside an authenticated shell |
| Auth | Access-mode switch invalidates sessions | Owner validation for supported direct/WebVPN routes | Not complete |
| Cache | Only schedule/notices/empty rooms persist stale data | Unit tests plus UserDefaults inspection | Unit coverage added |
| Cache | Release cannot read Debug/preview stale feature cache | Cache namespace tests plus clean Release runtime smoke | Build/mode cache-prefix tests passed 2026-06-14. Clean Release `UserDefaults` was empty; Debug preview schedule smoke followed by Release overlay launch returned to real CAS login instead of preview shell. Preserved-container stale-cache proof on signed/device or TestFlight pending |
| Cache | Clear cache does not remove Keychain credentials | Unit/manual validation | Partial |
| Recovery | Retry, empty, malformed, offline, and stale fallback states | Automated/preview/public evidence per feature | Partial; AuthStore XCTest covers logout cleanup, session-context persistence, and site-verification-without-shell behavior; KMP bridge contracts cover preview-backed auth/feature mappings; preview UI smoke covers auto-login schedule rendering |
| Release | Signed archive, privacy disclosures, versioning, visual assets, TestFlight | Release evidence | Unsigned generic-device archive and release checklist passed locally, including AppIcon packaging and local privacy-review docs. Signed archive/export, App Store Connect visual/privacy entry, public URLs, and TestFlight not started |

## Feature Matrix

Each real feature must cover success, legitimate empty state, auth expiry/site verification where applicable, bounded paging/action behavior, and user-facing failure recovery.

| Feature | Fixture/preview | Live success | Empty/error/reauth | Paging/action | Release build |
|---|---|---|---|---|---|
| Homepage aggregation | Yes | Partial | Partial-campus-card degradation passed | N/A | No |
| Schedule/exams/textbooks | Yes | Yes | Site reauth and textbook degradation passed | Bounded textbook traversal | No |
| Grades and grade detail | Yes | Grade list passed | Reauth covered; detail live check missing | Detail is user-triggered | No |
| Campus card | Yes | Yes | Site reauth passed | Total-aware user paging passed | No |
| Public notices | Yes | Public repository available | Source failure and safe-link behavior covered | Total-aware user paging | No |
| Empty rooms | Yes | Public repository available | Empty/filter states need matrix run | Query is user-filtered | No |
| Library seats | Yes | Not recorded | Not recorded | Book/swap not live-validated | No |
| Coupons | Yes | Not recorded | Not recorded | Paging not live-validated | No |
| School-wide course search | Yes | Not recorded | Not recorded | Paging not live-validated | No |

## Execution Order

1. Run automated Gradle and iOS tests on every checkpoint.
2. Run preview/public-data scenarios without credentials.
3. Ask the account owner to perform only the required sensitive interactions for real-core validation.
4. Fix evidence-backed failures and rerun the affected row.
5. Repeat mandatory gates against an archived Release/TestFlight build before declaring release readiness.
