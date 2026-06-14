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
| Build | Generated iOS project builds | `xcodegen generate` then simulator Debug and Release builds | Passed 2026-06-14 |
| Build | iOS unit tests | `xcodebuild test` on generated project | Local cache/store and launch/dependency-policy suite passed 10/10 on 2026-06-14; remote CI pending |
| Security | Debug default launch is preview-safe and network-free | Preview launch plus dependency-mode check | Implemented and covered by launch-policy XCTest; repeat before release |
| Security | Release has no preview data/debug auth logging | Launch-policy XCTest plus clean Release runtime/archive inspection | Policy tests passed and Debug/Release builds passed 2026-06-14. Post-fix simulator Release smoke stayed on real CAS login despite preview/public/debug launch args, and no `[DEBUG-AUTH-2FA]` log appeared. Signed archive/TestFlight inspection pending |
| Security | ATS is strict except library direct-HTTP host | Generated and built `Info.plist` inspection | Passed 2026-06-14 |
| Auth | Fresh CAS login | Owner validation: success plus sanitized state evidence | Previously passed |
| Auth | Saved credential restore and relaunch | Owner validation | Previously passed |
| Auth | Captcha, MFA/Safety Verify, account choice | Preview each; owner validation when naturally presented | Partial |
| Auth | Site expiry stays inside app shell | Owner validation of inline “补授权” and resumed feature load | Previously passed |
| Auth | Access-mode switch invalidates sessions | Owner validation for supported direct/WebVPN routes | Not complete |
| Cache | Only schedule/notices/empty rooms persist stale data | Unit tests plus UserDefaults inspection | Unit coverage added |
| Cache | Release cannot read Debug/preview stale feature cache | Cache namespace tests plus clean Release runtime smoke | Build/mode cache-prefix tests passed 2026-06-14. Clean Release `UserDefaults` was empty; Debug preview schedule smoke followed by Release overlay launch returned to real CAS login instead of preview shell. Preserved-container stale-cache proof on signed/device or TestFlight pending |
| Cache | Clear cache does not remove Keychain credentials | Unit/manual validation | Partial |
| Recovery | Retry, empty, malformed, offline, and stale fallback states | Automated/preview/public evidence per feature | Partial |
| Release | Signed archive, privacy disclosures, versioning, TestFlight | Release evidence | Simulator Release smoke passed; signed archive/TestFlight not started |

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
