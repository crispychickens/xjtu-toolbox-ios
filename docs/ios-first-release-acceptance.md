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
| Build | Generated iOS project builds | `xcodegen generate` then simulator Debug build | Passed 2026-06-14 |
| Build | iOS unit tests | `xcodebuild test` on generated project | Local cache/store suite passed 4/4 on 2026-06-14; remote CI pending |
| Security | Default launch is preview-safe and network-free | Preview launch plus dependency-mode check | Implemented; repeat before release |
| Security | Release has no preview data/debug auth logging | Release archive inspection | Not started |
| Security | ATS is strict except library direct-HTTP host | Generated and built `Info.plist` inspection | Passed 2026-06-14 |
| Auth | Fresh CAS login | Owner validation: success plus sanitized state evidence | Previously passed |
| Auth | Saved credential restore and relaunch | Owner validation | Previously passed |
| Auth | Captcha, MFA/Safety Verify, account choice | Preview each; owner validation when naturally presented | Partial |
| Auth | Site expiry stays inside app shell | Owner validation of inline “补授权” and resumed feature load | Previously passed |
| Auth | Access-mode switch invalidates sessions | Owner validation for supported direct/WebVPN routes | Not complete |
| Cache | Only schedule/notices/empty rooms persist stale data | Unit tests plus UserDefaults inspection | Unit coverage added |
| Cache | Clear cache does not remove Keychain credentials | Unit/manual validation | Partial |
| Recovery | Retry, empty, malformed, offline, and stale fallback states | Automated/preview/public evidence per feature | Partial |
| Release | Signed archive, privacy disclosures, versioning, TestFlight | Release evidence | Not started |

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
