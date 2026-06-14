# Project Memory

Low-token current-state snapshot for future agents. This is not a changelog. Use Git history for chronology, ADRs for decisions, and CodeGraph for source-level structure.

## Read Order

- Read `CONTEXT.md`, then this file.
- Before cross-module changes, read `docs/agents/codegraph.md` and query CodeGraph.
- Before changing KMP/iOS boundaries, auth, browser auth, WebVPN, or request pacing, read the relevant ADR under `docs/adr/`.
- Use `docs/ios-migration.md` for detailed migration behavior and validation commands.

## Current System

- Product: XJTU Toolbox / 岱宗盒子, a direct-to-school-system campus utility for XJTU students.
- Baseline: the Android v3.5.1 app remains in `app`; `shared` is the Kotlin Multiplatform core; `iosApp` is the SwiftUI shell generated from `iosApp/project.yml`.
- ADR 0001 establishes KMP shared core plus SwiftUI shell. ADR 0002 establishes login throttling and official browser-auth handoff.
- CodeGraph is local and ignored by Git. On 2026-06-14 it was current at 269 files, 8,822 nodes, and 18,594 edges.
- The 2026-06-14 migration checkpoint spans auth hardening, first-release features, tests, iOS UI, and docs.

## Stable Architecture And Guardrails

- `AuthManager` is the public auth module. It owns login, captcha/MFA/account-choice challenges, saved-credential restore, site verification, access-mode changes, session acquisition, and logout.
- `SiteSession`/`HttpSiteSession` is the feature boundary. Repositories may issue business requests through it but must not own credentials, cookies, tokens, WebVPN rewriting, or concrete login adapters.
- `CoreFeatureService` owns session routing for dashboard, schedule, grades, campus card, notices, empty rooms, library seats, coupons, and school-wide course search. SwiftUI must not call site sessions or school adapters directly.
- `CookieAwareHttpClient` is the KMP cookie source of truth. Real iOS requests remain paced at 750 ms direct and 1000 ms WebVPN unless fixture-backed evidence supports changing this.
- Access mode is persisted as `AUTO`, `NORMAL`, or `WEBVPN`; changing it invalidates site sessions.
- Per-site CAS/Safety Verify failures surface as `SiteVerificationRequired`, not global logout. Once inside the app shell, iOS shows inline “补授权” and retries only after user action.
- Starting an explicit fresh login clears any persisted authenticated-session context before entering captcha/MFA/account-choice challenges. Site-specific `beginSiteVerification` remains the separate path that may preserve shell context during inline reauthorization.
- Password, challenge, and browser-auth attempts are separately throttled. Never add automatic password/MFA/browser-auth/WebVPN retry loops.
- Official browser auth is blocked for production use until XJTU CAS accepts an app callback/Universal Link and returns an app-readable artifact. The current `xjtutoolbox://auth` service was rejected in live validation.
- Debug startup remains preview-safe unless an explicit validation scheme or launch argument is used. Release startup ignores preview/validation dependency launch arguments and uses the real first-release dependency assembly; real traffic is still user/login-driven and paced.
- iOS ATS remains strict except for the scoped `rg.lib.xjtu.edu.cn` exception required by the library seat system's direct HTTP endpoint. Do not restore global arbitrary-load permission.
- Persistent stale cache is limited to schedule, public notices, and public empty rooms, with keys namespaced by build configuration and dependency mode. Credentials stay in Keychain; grades, campus card, dashboard, coupons, library seats, grade details, and course searches are not persisted in UserDefaults.
- Keep grade, textbook, campus-card, notice, coupon, and school-course paging bounded and user-driven. Public notice aggregation stays sequential.
- New cross-platform behavior goes into `shared` first. iOS presentation remains restrained, task-first, and native.

## Implemented First-Release Surface

- Shared auth/session/network core: CAS password login, RSA, graph captcha, MFA, Safety Verify, account choice, attempt governors, saved credentials, WebVPN host codec, cookie handling, request pacing, session registry, and per-site reauthorization.
- Shared real repositories: JWAPP schedule/exams/textbooks, mobile JWAPP grades and score detail, ncard campus card, public notices, empty-room CDN, first-slice library seats, coupons, and JWXT school-wide course search.
- Shared repositories and parsers fail closed on auth/error/service-change shapes and have fixture-driven tests.
- iOS shell: login/challenge flows, inline site verification, homepage, schedule, grades/detail, campus card, notices, empty rooms, library seats, coupons, school-course search, profile, settings, cache clearing, Keychain, and access-mode controls.
- First-slice library seats intentionally omit map selection, complex neighbor scoring, timed seat-grab automation, and automated sign-out.
- Preview, login-only, public-data, campus-card/public-data, and real-first-release dependency assemblies exist. Debug runs default to KMP preview and can opt into validation modes; Release defaults to the real-first-release assembly.

## Completion Assessment

Estimates as of 2026-06-15 local; these are engineering judgments, not measured coverage.

| Scope | Completion | Assessment |
|---|---:|---|
| First-release feature implementation | 90% | Every declared first-release workflow has a shared repository/service seam and iOS surface. Remaining work is mainly validation and release hardening. |
| Shared architecture and fixture coverage | 94% | Core boundaries and fail-closed parsers are established; iOS now tests cache/store, launch/dependency policy, auth-state, sanitized Debug diagnostic metadata, preview UI smoke, and the KMP auth/first-release-feature bridge contract against the generated framework. School endpoint churn, action workflows, platform-adapter failure paths, and broader iOS UI coverage remain residual risk. |
| Real-account integration confidence | 70% | CAS, saved restore, site verification, schedule, grades, and ncard were live-validated. Library seats, coupons, and school-course search have fixture/preview validation but no recorded end-to-end live validation. |
| iOS product/release readiness | 75% | Debug and Release simulator builds pass, the current local automated gate script passed on 2026-06-15, and the XCTest suite covers cache, launch/dependency policy, auth-state invariants, sanitized Debug diagnostic metadata, KMP bridge contracts, and a preview UI smoke path. The validated unsigned generic-device archive proves version, device-family, AppIcon packaging, privacy/ATS, arm64, dSYM integrity, and absence of the Debug-auth marker and known unreviewed crash-SDK markers. Local App Store privacy-review evidence and a publishable policy draft now exist, and the iOS CI workflow is manually triggerable through the same automated release-gate script with tool/simulator logging plus XCTest failure artifacts. A passing remote CI result, final app identity/signing/export, TestFlight, App Store Connect privacy entry/public URLs, runtime device-log inspection, and App Store listing visuals remain incomplete. |
| Broad Android feature parity | 40% | First-release core is present; attendance, class replay, LMS, transcript, textbook center, NeoSchool, venue booking, evaluation, payment code, downloads, widgets, and other Android-only workflows remain later releases. |
| Overall first iOS release readiness | 85% | Feature-complete enough for a controlled alpha, with Release dependency wiring, current local automated gate evidence, KMP bridge and preview UI-smoke coverage, privacy manifest, explicit versioning, compiled AppIcon assets, a documented crash/log policy, unsigned device-archive validation, and local App Store privacy-review material now productionized. It is still not ready for unattended production release without live validation, remote CI, signed archive/export, App Store Connect privacy entry/public URLs, runtime device-log inspection, and TestFlight evidence. |

## Latest Validation Baseline

- Live iOS account evidence covers CAS login, saved credential reuse, mobile JWAPP grade token/list, schedule, ncard token/card info/turnover, and inline site reauthorization. Grade detail, library seats, coupons, school-course search, access-mode switching, and the full acceptance matrix still lack complete recorded live evidence.
- Shared has substantial fixture-driven common/JVM tests and current `./gradlew :shared:check` passed locally on 2026-06-15. Android tests remain sparse; endpoint churn, action workflows, and real adapter failure/cancellation behavior remain residual gaps.
- Full generated-project `xcodebuild test` passed 22/22 locally on 2026-06-15 against iPhone 16 / iOS 18.6: 20 unit/contract tests plus 2 preview UI smoke tests. Coverage includes cache/store, launch/dependency policy, auth-state invariants, sanitized Debug diagnostic metadata, KMP auth/feature bridge contracts, default Debug login launch, and preview schedule rendering. Broader UI workflows remain uncovered.
- Release simulator smoke proves preview/public/debug launch arguments do not enter the preview shell or emit Debug-auth output. Build/mode cache namespaces prevent known Debug/preview stale-cache reads; preserved-container upgrade proof remains a signed-device/TestFlight gate.
- A current unsigned generic-device Release archive for `1.0.0 (1)` passed local validation on 2026-06-15, proving identity, iPhone-only arm64, compiled AppIcon asset, privacy/ATS, dSYM, absence of the Debug-auth marker, and absence of known unreviewed crash-SDK markers. Local App Store privacy-review evidence and a privacy-policy draft are documented. `iosApp/scripts/run-automated-release-gate.sh` passed locally and is the canonical local/CI entry for shared check, XcodeGen, XCTest, unsigned archive, and archive validation. A passing remote macOS CI run, final signing/export, App Store Connect privacy entry/public URLs, runtime device-log inspection, App Store listing visuals, and TestFlight evidence remain pending.

## Remaining Milestones

Difficulty: `M` bounded multi-file work, `H` cross-layer or external-system work, `VH` release/architecture work with broad blast radius. Recommended Codex reasoning levels are `medium`, `high`, and `xhigh`.

| Priority | Remaining node / done condition | Difficulty | Codex reasoning |
|---|---|---:|---|
| R1 | Live-validate library seats, coupons, and school-course search with owner-entered credentials; cover success, empty, expired-session, site-verification, paging/action errors, and direct/WebVPN where supported. | H | xhigh |
| R1 | Execute the documented first-release acceptance matrix across cold start, saved restore, captcha/MFA/account choice, per-site reauth, access-mode switch, offline/stale cache, retry, pagination, logout, and relaunch. | H | xhigh |
| R1 | Run the hardened iOS validation workflow remotely and record a passing GitHub result for the current shared, 20-unit/contract, 2-UI-smoke, and unsigned-archive gates; add further KMP platform-adapter/error-path tests only when evidence exposes a gap. | M | high |
| R1 | Signed/device Release archive or TestFlight inspection: prove the same real-first-release behavior outside simulator, including preview-arg ignoring, no debug auth logging, and no preserved-container Debug/preview stale-cache read on upgrade. | H | high |
| R1 | Finalize app identity, team/provisioning/signing, signed archive/export validation, and the TestFlight upload/install pipeline. | VH | xhigh |
| R1 | Finalize App Store listing visuals and App Store Connect visual verification. The in-app AppIcon is generated from the Android launcher asset and now passes archive validation; replace it only if the owner provides a final brand asset. | M | medium |
| R1 | Publish privacy policy/support URLs and complete App Store Connect privacy questionnaire using the local privacy-review evidence; release owner must confirm whether XJTU school-system traffic is real-time request servicing or third-party partner collection. | M | high |
| R2 | Harden endpoint-change operations: sanitized diagnostics, fixture refresh workflow, explicit service-change copy, and a controlled live-validation cadence for school-system changes. | H | high |
| R2 | Decide first post-release slice from real demand; do not start broad parity by default. Candidate slices: attendance, schedule export/custom courses, or richer library-seat workflow. | M | high |
| Later | Port Android-only modules as independent vertical slices: attendance; class replay/downloads; LMS; transcript; textbook center; NeoSchool; venue booking; evaluation; payment code; widgets. | VH each | xhigh |

## Recommended Execution Order

1. Complete R1 live validation and execute the acceptance matrix; fix only evidence-backed failures.
2. Confirm the macOS CI workflow remotely and run signed/device Release archive or TestFlight inspection.
3. Complete app identity/signing/export, release assets, App Store Connect privacy entry/public URLs, and the TestFlight pipeline; ship a controlled alpha.
4. Choose one post-release vertical slice from observed user demand.
