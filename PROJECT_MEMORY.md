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
- CodeGraph is local and ignored by Git. On 2026-06-14 it was current at 262 files, 8,732 nodes, and 18,523 edges.
- The 2026-06-14 migration checkpoint spans auth hardening, first-release features, tests, iOS UI, and docs.

## Stable Architecture And Guardrails

- `AuthManager` is the public auth module. It owns login, captcha/MFA/account-choice challenges, saved-credential restore, site verification, access-mode changes, session acquisition, and logout.
- `SiteSession`/`HttpSiteSession` is the feature boundary. Repositories may issue business requests through it but must not own credentials, cookies, tokens, WebVPN rewriting, or concrete login adapters.
- `CoreFeatureService` owns session routing for dashboard, schedule, grades, campus card, notices, empty rooms, library seats, coupons, and school-wide course search. SwiftUI must not call site sessions or school adapters directly.
- `CookieAwareHttpClient` is the KMP cookie source of truth. Real iOS requests remain paced at 750 ms direct and 1000 ms WebVPN unless fixture-backed evidence supports changing this.
- Access mode is persisted as `AUTO`, `NORMAL`, or `WEBVPN`; changing it invalidates site sessions.
- Per-site CAS/Safety Verify failures surface as `SiteVerificationRequired`, not global logout. Once inside the app shell, iOS shows inline “补授权” and retries only after user action.
- Password, challenge, and browser-auth attempts are separately throttled. Never add automatic password/MFA/browser-auth/WebVPN retry loops.
- Official browser auth is blocked for production use until XJTU CAS accepts an app callback/Universal Link and returns an app-readable artifact. The current `xjtutoolbox://auth` service was rejected in live validation.
- Real school-system wiring stays behind explicit validation launch arguments. Default startup remains network-free KMP preview until release productionization is deliberately completed.
- iOS ATS remains strict except for the scoped `rg.lib.xjtu.edu.cn` exception required by the library seat system's direct HTTP endpoint. Do not restore global arbitrary-load permission.
- Persistent stale cache is limited to schedule, public notices, and public empty rooms. Credentials stay in Keychain; grades, campus card, dashboard, coupons, library seats, grade details, and course searches are not persisted in UserDefaults.
- Keep grade, textbook, campus-card, notice, coupon, and school-course paging bounded and user-driven. Public notice aggregation stays sequential.
- New cross-platform behavior goes into `shared` first. iOS presentation remains restrained, task-first, and native.

## Implemented First-Release Surface

- Shared auth/session/network core: CAS password login, RSA, graph captcha, MFA, Safety Verify, account choice, attempt governors, saved credentials, WebVPN host codec, cookie handling, request pacing, session registry, and per-site reauthorization.
- Shared real repositories: JWAPP schedule/exams/textbooks, mobile JWAPP grades and score detail, ncard campus card, public notices, empty-room CDN, first-slice library seats, coupons, and JWXT school-wide course search.
- Shared repositories and parsers fail closed on auth/error/service-change shapes and have fixture-driven tests.
- iOS shell: login/challenge flows, inline site verification, homepage, schedule, grades/detail, campus card, notices, empty rooms, library seats, coupons, school-course search, profile, settings, cache clearing, Keychain, and access-mode controls.
- First-slice library seats intentionally omit map selection, complex neighbor scoring, timed seat-grab automation, and automated sign-out.
- Preview, login-only, public-data, campus-card/public-data, and real-first-release dependency assemblies exist. The real-first-release assembly is not the default production assembly.

## Completion Assessment

Estimates as of 2026-06-14; these are engineering judgments, not measured coverage.

| Scope | Completion | Assessment |
|---|---:|---|
| First-release feature implementation | 90% | Every declared first-release workflow has a shared repository/service seam and iOS surface. Remaining work is mainly validation and release hardening. |
| Shared architecture and fixture coverage | 88% | Core boundaries and fail-closed parsers are established; school endpoint churn and action workflows remain residual risk. |
| Real-account integration confidence | 70% | CAS, saved restore, site verification, schedule, grades, and ncard were live-validated. Library seats, coupons, and school-course search have fixture/preview validation but no recorded end-to-end live validation. |
| iOS product/release readiness | 40% | Simulator builds pass, but default wiring is preview, iOS has no meaningful automated test target, CI is Android-only, and signing/archive/TestFlight/privacy/assets work is not established. |
| Broad Android feature parity | 40% | First-release core is present; attendance, class replay, LMS, transcript, textbook center, NeoSchool, venue booking, evaluation, payment code, downloads, widgets, and other Android-only workflows remain later releases. |
| Overall first iOS release readiness | 72% | Feature-complete enough for a controlled alpha, not ready for unattended production release. |

## Latest Validation Baseline

- 2026-06-05: live iOS validation passed CAS login, saved credential reuse, mobile JWAPP grade token/grade-list flow, schedule, ncard token exchange/card info/turnover, and inline site reauthorization behavior. Grade detail has fixture/build/presentation validation but no recorded live end-to-end check.
- 2026-06-14: library seats, coupons, and school-course search first slices were present; school-course search received targeted shared tests and simulator presentation validation, but not a live-account query.
- 2026-06-14: `./gradlew check` passed in 24m04s; iOS simulator Debug build passed; `git diff --check` passed; simulator visual QA passed; CodeGraph was synchronized.
- Test shape: shared has substantial fixture-driven common/JVM tests; Android tests are sparse and iOS has no meaningful XCTest/UI-test suite.

## Remaining Milestones

Difficulty: `M` bounded multi-file work, `H` cross-layer or external-system work, `VH` release/architecture work with broad blast radius. Recommended Codex reasoning levels are `medium`, `high`, and `xhigh`.

| Priority | Remaining node / done condition | Difficulty | Codex reasoning |
|---|---|---:|---|
| R0 | Create a clean migration checkpoint: review dirty scope, split intentional changes, run final regression, then commit without absorbing unrelated edits. | M | high |
| R1 | Live-validate library seats, coupons, and school-course search with owner-entered credentials; cover success, empty, expired-session, site-verification, paging/action errors, and direct/WebVPN where supported. | H | xhigh |
| R1 | Execute and document a first-release acceptance matrix across cold start, saved restore, captcha/MFA/account choice, per-site reauth, access-mode switch, offline/stale cache, retry, pagination, logout, and relaunch. | H | xhigh |
| R1 | Add iOS automated coverage: XCTest targets for Swift bridge/store/cache/auth-state behavior plus a small preview UI smoke suite; run it in macOS CI with shared checks. | H | high |
| R1 | Productionize the iOS assembly: make release configuration use real dependencies without debug launch arguments, keep preview schemes isolated, and verify no debug auth logging or preview data leaks into Release. | VH | xhigh |
| R1 | Establish iOS release engineering: app identity/signing, icons/assets, privacy manifest and disclosures, archive/export validation, versioning, crash/log policy, TestFlight pipeline, and release checklist. | VH | xhigh |
| R2 | Harden endpoint-change operations: sanitized diagnostics, fixture refresh workflow, explicit service-change copy, and a controlled live-validation cadence for school-system changes. | H | high |
| R2 | Decide first post-release slice from real demand; do not start broad parity by default. Candidate slices: attendance, schedule export/custom courses, or richer library-seat workflow. | M | high |
| Later | Port Android-only modules as independent vertical slices: attendance; class replay/downloads; LMS; transcript; textbook center; NeoSchool; venue booking; evaluation; payment code; widgets. | VH each | xhigh |

## Recommended Execution Order

1. R0 checkpoint the current migration state.
2. Complete R1 live validation and acceptance matrix; fix only evidence-backed failures.
3. Add iOS tests/CI, then productionize release wiring.
4. Complete signing, privacy, archive, and TestFlight work; ship a controlled alpha.
5. Choose one post-release vertical slice from observed user demand.
