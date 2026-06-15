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
| Build | Shared Gradle regression | `./gradlew :shared:check` | Passed locally 2026-06-15 |
| Build | Generated iOS project builds and archives | `xcodegen generate`, simulator tests/builds, then unsigned generic-device Release archive validation | Passed locally 2026-06-15; generic-device archive validated as `1.0.0 (1)`, iphoneos arm64, iPhone-only, with matching dSYM. Remote workflow is triggerable and runs the same unsigned archive validator; passing GitHub evidence pending |
| Build | iOS XCTest suite | `xcodebuild test` on generated project | Local suite passed 32/32 on 2026-06-15: 27 unit/contract tests plus 5 preview UI smoke tests on iPhone 16 / iOS 18.6. KMP bridge contracts cover auth mapping and every first-release feature method through the generated framework; release-log-policy tests cover structural Debug metadata; cache coverage includes the settings-facing clear-cache path preserving Keychain credentials; AuthStore coverage includes settings-facing access-mode changes and opt-in rate-limited automatic site verification; launch-policy coverage includes Debug-only automatic site verification and real feature validation flags. UI smoke covers login plus the main first-release preview surfaces across home, schedule, grades, campus services, empty rooms, and school-course search. Remote workflow now records tool/simulator versions and uploads `ios-xcresult` on XCTest failure; passing GitHub evidence pending |
| Build | Version and build-number policy | `iosApp/project.yml` inspection plus archive consistency check | `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` centralized as `1.0.0 (1)`; archive validator enforces major.minor.patch and positive build number |
| Build | App icon asset packaging | `Assets.xcassets` inspection plus archive validator | AppIcon generated from the existing Android launcher asset and bundled 2026-06-14. Current unsigned Release archive contains `Assets.car`, `AppIcon60x60@2x.png`, and processed `CFBundleIconName=AppIcon`; App Store Connect visual review pending |
| Security | Debug default launch is preview-safe and network-free | Preview launch plus dependency-mode check | Implemented and covered by launch-policy XCTest; UI smoke also proves default Debug launch stays on login screen |
| Security | Release has no preview data/debug auth logging | Launch-policy XCTest plus clean Release runtime/archive inspection | Policy tests passed and Debug/Release builds passed 2026-06-14. Post-fix simulator Release smoke stayed on real CAS login despite preview/public/debug launch args, and no `[DEBUG-AUTH-2FA]` log appeared. Current unsigned Release archive gate also proved the Debug-auth marker and known unreviewed crash-SDK markers absent. Signed archive/TestFlight runtime inspection pending |
| Security | ATS is strict except library direct-HTTP host | Generated and built `Info.plist` inspection | Passed 2026-06-14 |
| Privacy | Privacy manifest and required-reason APIs | `PrivacyInfo.xcprivacy` inspection plus Release bundle check | Manifest added and bundled 2026-06-14. It declares no tracking, no tracking domains, no collected data types, and UserDefaults required-reason API `CA92.1`. Local App Store privacy review is documented; website privacy/support pages were built and browser-verified locally on 2026-06-15, and the `Website Pages` workflow now validates them remotely before `main` deployment. App Store Connect entry, successful public deployment/final URL verification, and release-owner confirmation are pending |
| Auth | Fresh CAS login | Owner validation: success plus sanitized state evidence | Previously passed; repeated 2026-06-15 with owner-entered credentials in `XJTUToolboxIOS-RealLoginValidation`, then reused saved credentials in `XJTUToolboxIOS-RealFirstReleaseCore` |
| Auth | Saved credential restore and relaunch | Owner validation | Previously passed |
| Auth | Captcha, MFA/Safety Verify, account choice | Preview each; owner validation when naturally presented | Partial; deterministic AuthStore regression proves explicit fresh login clears stale authenticated-session context before account choice |
| Auth | Site expiry stays inside app shell | Owner validation of inline “补授权” and resumed feature load | Previously passed; site verification remains user-driven by default. AuthStore XCTest covers site-verification staying inside an authenticated shell plus opt-in automatic site verification requiring both authenticated shell context and an explicit Debug flag. Diagnostic auto-attempts use the shared `AuthManager.beginSiteVerification` path and are limited to at least 1 second between attempts and one attempt per site per 5-minute window |
| Auth | Access-mode switch invalidates sessions | Owner validation for supported direct/WebVPN routes | Automated coverage added: shared `DefaultAuthManager` proves access-mode changes invalidate existing site sessions, and iOS `AuthStore` proves settings-facing changes forward to the auth manager without duplicate invalidations. Owner direct/WebVPN validation pending |
| Cache | Only schedule/notices/empty rooms persist stale data | Unit tests plus UserDefaults inspection | Unit coverage added |
| Cache | Release cannot read Debug/preview stale feature cache | Cache namespace tests plus clean Release runtime smoke | Build/mode cache-prefix tests passed 2026-06-14. Clean Release `UserDefaults` was empty; Debug preview schedule smoke followed by Release overlay launch returned to real CAS login instead of preview shell. Preserved-container stale-cache proof on signed/device or TestFlight pending |
| Cache | Clear cache does not remove Keychain credentials | Unit/manual validation | Unit coverage added for the settings-facing `FeatureStore.clearCachedData()` path preserving a test-only Keychain credential |
| Recovery | Retry, empty, malformed, offline, and stale fallback states | Automated/preview/public evidence per feature | Partial; AuthStore XCTest covers logout cleanup, session-context persistence, site-verification-without-shell behavior, and opt-in automatic site-verification guards/rate limits; KMP bridge contracts cover preview-backed auth/feature mappings; preview UI smoke covers auto-login schedule rendering |
| Release | Signed archive, privacy disclosures, versioning, visual assets, TestFlight | Release evidence | Current unsigned generic-device archive and release checklist passed locally on 2026-06-15, including AppIcon packaging, local privacy-review docs, locally rendered website privacy/support pages, and a local App Store listing draft. A repeatable signed archive/optional export gate exists; its first local attempt found a valid development identity but stopped because Xcode lacks an authenticated team account and matching provisioning profile. Signed archive/export, App Store Connect visual/privacy entry, public URL deployment, final screenshots, and TestFlight remain pending |

## Feature Matrix

Each real feature must cover success, legitimate empty state, auth expiry/site verification where applicable, bounded paging/action behavior, and user-facing failure recovery.

| Feature | Fixture/preview | Live success | Empty/error/reauth | Paging/action | Release build |
|---|---|---|---|---|---|
| Homepage aggregation | Yes | Partial | Partial-campus-card degradation passed | N/A | No |
| Schedule/exams/textbooks | Yes | Yes | Site reauth and textbook degradation passed | Bounded textbook traversal | No |
| Grades and grade detail | Yes | Grade list and detail passed | Reauth covered | Detail is user-triggered and returned 200 in real-core validation | No |
| Campus card | Yes | Yes | Site reauth passed | Total-aware user paging passed | No |
| Public notices | Yes | Public repository available | Source failure and safe-link behavior covered | Total-aware user paging | No |
| Empty rooms | Yes | Public repository available | Empty/filter states need matrix run | Query is user-filtered | No |
| Library seats | Yes | Blocked before feature success | Debug-only structural runner added with `iosApp/scripts/run-real-feature-validation.sh`; it can be launched before manual login, starts after authentication, and resumes from the interrupted feature after site verification completes. The first 2026-06-15 run stopped at `siteVerificationRequired site=schedule`; the post-fix controlled rerun built/installed/launched successfully but timed out before owner authentication, so rerun with owner interaction is pending | Book/swap not live-validated | No |
| Coupons | Yes | Blocked before feature success | Debug-only structural runner added with `iosApp/scripts/run-real-feature-validation.sh`; it can be launched before manual login and resumes after automatic/manual site verification. The post-fix controlled rerun timed out before owner authentication and did not reach this feature | Paging not live-validated | No |
| School-wide course search | Yes | Blocked before feature success | Debug-only structural runner added with `iosApp/scripts/run-real-feature-validation.sh`; it can be launched before manual login and resumes after automatic/manual site verification. The post-fix controlled rerun timed out before owner authentication and did not reach this feature | Paging not live-validated | No |

## Execution Order

1. Run automated Gradle and iOS tests on every checkpoint.
2. Run preview/public-data scenarios without credentials.
3. Ask the account owner to perform only the required sensitive interactions for real-core validation.
4. Fix evidence-backed failures and rerun the affected row.
5. Repeat mandatory gates against an archived Release/TestFlight build before declaring release readiness.
