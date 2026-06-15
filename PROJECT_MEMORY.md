# Project Memory
Low-token current-state snapshot for future agents. This is not a changelog: use Git history for chronology, ADRs for decisions, CodeGraph for source structure, and handoff notes for short-lived continuation detail.
## Read Order
- Read `CONTEXT.md`, then this file.
- Before cross-module source work, read `docs/agents/codegraph.md` and query CodeGraph.
- Before changing KMP/iOS boundaries, auth, browser auth, WebVPN, request pacing, or login retries, read the relevant ADR under `docs/adr/`.
- Use `docs/ios-migration.md`, `docs/ios-release-checklist.md`, and `docs/ios-first-release-acceptance.md` for iOS release behavior and validation commands.
## Current System
- Product: XJTU Toolbox / 岱宗盒子, a direct-to-school-system campus utility for XJTU students.
- Baseline: Android v3.5.1 remains in `app`; `shared` is the Kotlin Multiplatform core; `iosApp` is the SwiftUI shell generated from `iosApp/project.yml`.
- ADR 0001 establishes KMP shared core plus SwiftUI shell. ADR 0002 establishes login throttling and official browser-auth handoff.
- CodeGraph is local and ignored by Git. Last known index on 2026-06-14: 269 files, 8,822 nodes, 18,594 edges.
## Architecture Guardrails
- `AuthManager` is the public auth boundary for login, captcha/MFA/account-choice challenges, saved-credential restore, site verification, access-mode changes, session acquisition, and logout.
- `SiteSession`/`HttpSiteSession` is the feature boundary. Repositories issue business requests through it and must not own credentials, cookies, tokens, WebVPN rewriting, or concrete login adapters.
- `CoreFeatureService` owns session routing for dashboard, schedule, grades, campus card, notices, empty rooms, library seats, coupons, and school-wide course search. SwiftUI must not call site sessions or school adapters directly.
- `CookieAwareHttpClient` is the KMP cookie source of truth. Real iOS requests stay paced at 750 ms direct and 1000 ms WebVPN unless fixture-backed evidence supports changing this.
- Access mode is persisted as `AUTO`, `NORMAL`, or `WEBVPN`; changing it invalidates site sessions. Per-site CAS/Safety Verify failures surface as `SiteVerificationRequired`, not global logout, and iOS retries only after user action.
- Fresh explicit login clears authenticated-session context before captcha/MFA/account-choice challenges. Site-specific `beginSiteVerification` is the separate inline reauthorization path that may preserve shell context.
- Password, challenge, and browser-auth attempts are separately throttled. Do not add automatic password/MFA/browser-auth/WebVPN retry loops.
- Official browser auth is blocked for production until XJTU CAS accepts an app callback/Universal Link and returns an app-readable artifact; current `xjtutoolbox://auth` service was rejected in live validation.
- Debug startup is preview-safe unless explicit validation is requested. Release ignores preview/validation dependency launch arguments and uses real first-release dependencies.
- iOS ATS is strict except the scoped `rg.lib.xjtu.edu.cn` direct-HTTP exception for library seats. Do not restore global arbitrary loads.
- Persistent stale cache is limited to schedule, public notices, and public empty rooms, namespaced by build configuration and dependency mode. Credentials stay in Keychain; grades, campus card, dashboard, coupons, library seats, grade details, and course searches are not persisted in UserDefaults.
- Keep grade, textbook, campus-card, notice, coupon, and school-course paging bounded and user-driven; public notice aggregation remains sequential.
- New cross-platform behavior belongs in `shared` first. iOS presentation should stay native, restrained, and task-first.
## Implemented Surface
- Shared auth/session/network core: CAS password login, RSA, graph captcha, MFA, Safety Verify, account choice, attempt governors, saved credentials, WebVPN host codec, cookies, request pacing, session registry, and per-site reauthorization.
- Shared real repositories: JWAPP schedule/exams/textbooks, mobile JWAPP grades and score detail, ncard campus card, public notices, empty-room CDN, first-slice library seats, coupons, and JWXT school-wide course search. Repositories and parsers fail closed on auth/error/service-change shapes with fixture-driven tests.
- iOS shell: login/challenge flows, user-driven inline site verification with a Debug-only rate-limited auto-validation mode, homepage, schedule, grades/detail, campus card, notices, empty rooms, library seats, coupons, school-course search, profile, settings, cache clearing, Keychain, and access-mode controls.
- First-slice library seats intentionally omits map selection, complex neighbor scoring, timed seat-grab automation, and automated sign-out.
- Dependency assemblies exist for preview, login-only, public-data, campus-card/public-data, and real-first-release. Debug defaults to KMP preview and can opt into validation; Release defaults to real-first-release.
## Readiness
Estimates as of 2026-06-15 local; they are engineering judgments, not measured coverage.
| Scope | Completion | Current assessment |
|---|---:|---|
| First-release feature implementation | 90% | Declared workflows have shared service/repository support and iOS surfaces; remaining work is validation and release hardening. |
| Shared architecture and fixture coverage | 94% | Boundaries and fail-closed parsers are established; residual risk is endpoint churn, action workflows, platform-adapter failure paths, and broader iOS UI coverage. |
| Real-account integration confidence | 72% | CAS, saved restore, site verification, schedule, grade list/detail, and ncard have live evidence; library seats, coupons, and school-course search still need recorded end-to-end live validation. |
| iOS product/release readiness | 77% | Local automated gate, Release simulator behavior, unsigned archive validation, privacy review, browser-verified website privacy/support page sources plus a main-only Pages deployment workflow, App Store listing draft, AppIcon, versioning, crash/log policy, KMP bridge tests, and preview UI smoke are in place; remote CI results, signing/export, device/TestFlight, App Store Connect entries, successful public URL deployment, final screenshots, runtime logs, and listing visual upload remain. |
| Broad Android feature parity | 40% | First-release core is present; attendance, class replay, LMS, transcript, textbook center, NeoSchool, venue booking, evaluation, payment code, downloads, widgets, and other Android-only workflows are later slices. |
| Overall first iOS release readiness | 85% | Feature-complete enough for controlled alpha, not for unattended production release. |
## Validation Baseline
- Live iOS account evidence covers CAS login, saved credential reuse, mobile JWAPP token/list/detail, schedule, ncard token/card info/turnover, and inline site reauthorization. Library seats, coupons, school-course search, direct/WebVPN owner validation, and the full acceptance matrix still lack complete recorded live evidence.
- `./gradlew :shared:check` passed locally on 2026-06-15. Shared has substantial common/JVM fixture tests; Android tests remain sparse.
- `iosApp/scripts/run-automated-release-gate.sh` passed locally on 2026-06-15 and is the canonical local/CI entry for iOS shell-script syntax, shared check, XcodeGen, XCTest, unsigned archive, and archive validation.
- Generated-project XCTest passed 35/35 locally on iPhone 16 / iOS 18.6: 28 unit/contract tests plus 7 preview UI smoke tests. Coverage includes cache/store, settings clear-cache preserving Keychain credentials, settings access-mode forwarding, launch/dependency policy including Debug-only real feature validation, automatic site verification, and Debug/Preview-only recovery scenarios, auth-state invariants, opt-in rate-limited automatic site verification, sanitized Debug diagnostic metadata, KMP auth/feature bridge contracts, default Debug login launch, preview captcha/MFA/account-choice completion, deterministic empty-state and first-failure retry recovery, and the main first-release preview surfaces across home, schedule, grades, campus services, empty rooms, and school-course search.
- A Debug-only `-XJTURealFeatureValidation` runner exists for `-XJTURealFirstReleaseCore`; `iosApp/scripts/run-real-feature-validation.sh` explicitly opts into rate-limited `-XJTUAutoSiteVerification`, waits for owner-entered or restored authentication, polls only the sanitized `debugRealFeatureValidationResults` key, can save those sanitized lines via `IOS_REAL_FEATURE_RESULTS_PATH`, and resumes from the interrupted feature after site verification completes. The first 2026-06-15 saved-credential run stopped at `siteVerificationRequired site=schedule`; a post-fix controlled rerun built, installed, and launched successfully but timed out before owner authentication, so library-seat, coupon, and school-course success still lack recorded evidence.
- `.github/workflows/website-pages.yml` validates the VitePress privacy/support pages on relevant PRs and `ios-kmp-migration` pushes and deploys only from `main`. The expected GitHub Pages URLs still return 404. HTTPS push lacks a valid token, and the locally authenticated SSH account has no write permission to `yeliqin666/xjtu-toolbox-android` and no existing fork, so successful `main` deployment and final public URL verification remain release-owner gates.
- `iosApp/scripts/capture-app-store-screenshots.sh` builds the Debug preview app in a disposable 6.9-inch iPhone simulator, requests a Simplified-Chinese launch language/locale, captures ten privacy-safe listing candidates covering the main first-release surfaces, and validates Apple-accepted portrait dimensions. On 2026-06-15 the candidate workflow rendered without loading states, clipping, or real-account data at `1320x2868`; the empty-room system date control now explicitly matches the Chinese UI. Final signed/TestFlight consistency remains a release-owner gate.
- Release simulator smoke proves preview/public/debug launch arguments do not enter the preview shell or emit Debug-auth output. Build/mode cache namespaces prevent known Debug/preview stale-cache reads; preserved-container upgrade proof remains a signed-device/TestFlight gate.
- Unsigned generic-device Release archive `1.0.0 (1)` passes local validation for identity, iPhone-only arm64, compiled AppIcon, privacy/ATS, dSYM, absence of the Debug-auth marker, and absence of known unreviewed crash-SDK markers.
- `iosApp/scripts/run-signed-release-gate.sh` now provides a command-time team injection, automatic signed Release archive, strict `REQUIRE_SIGNED=1` validation, and optional IPA export. A 2026-06-15 local attempt found one valid Apple Development identity but correctly stopped because Xcode has no valid account session for that team and no provisioning profile for `com.xjtu.toolbox.ios`.
- `docs/ios-release-owner-evidence-record.md` is the release-owner template for closing private R1 evidence: owner-account feature validation, remote iOS/Pages workflow URLs, public privacy/support URLs, signed archive/export, TestFlight device inspection, App Store Connect privacy/listing decisions, and failure records without secrets or personal data.
## Remaining Milestones
Difficulty: `M` bounded multi-file work, `H` cross-layer or external-system work, `VH` release/architecture work with broad blast radius. Recommended Codex reasoning: `medium`, `high`, `xhigh`.
| Priority | Remaining node / done condition | Difficulty | Codex reasoning |
|---|---|---:|---|
| R1 | Live-validate library seats, coupons, and school-course search with owner-entered credentials; cover success, empty, expired-session, site-verification, paging/action errors, and direct/WebVPN where supported. | H | xhigh |
| R1 | Execute the documented first-release acceptance matrix across cold start, saved restore, captcha/MFA/account choice, per-site reauth, access-mode switch, offline/stale cache, retry, pagination, logout, and relaunch. | H | xhigh |
| R1 | Run the hardened iOS validation workflow remotely and record a passing GitHub result for current shared check, 28 unit/contract tests, 7 UI smoke tests, and unsigned-archive gates. | M | high |
| R1 | Signed/device Release archive or TestFlight inspection: prove real-first-release behavior outside simulator, including preview-arg ignoring, no debug auth logging, and no preserved-container Debug/preview stale-cache read on upgrade. | H | high |
| R1 | Finalize app identity, team/provisioning/signing, signed archive/export validation, and TestFlight upload/install pipeline. | VH | xhigh |
| R1 | Finalize App Store listing visuals and App Store Connect visual verification from the local listing draft. AppIcon is generated from the Android launcher asset and passes archive validation; replace only if the owner provides a final brand asset. | M | medium |
| R1 | Deploy the prepared privacy policy/support pages, record final public URLs, and complete App Store Connect privacy questionnaire using local privacy-review evidence; release owner must classify XJTU school-system traffic for App Store privacy. | M | high |
| R2 | Harden endpoint-change operations: sanitized diagnostics, fixture refresh workflow, explicit service-change copy, and controlled live-validation cadence for school-system changes. | H | high |
| R2 | Choose the first post-release slice from real demand; do not start broad parity by default. Candidate slices: attendance, schedule export/custom courses, or richer library-seat workflow. | M | high |
| Later | Port Android-only modules as independent vertical slices: attendance, class replay/downloads, LMS, transcript, textbook center, NeoSchool, venue booking, evaluation, payment code, and widgets. | VH each | xhigh |
## Recommended Next Order
1. Complete R1 live validation and execute the acceptance matrix; fix only evidence-backed failures.
2. Confirm the macOS CI workflow remotely and run signed/device Release archive or TestFlight inspection.
3. Complete app identity/signing/export, release assets, App Store Connect privacy entry/public URLs, and the TestFlight pipeline.
4. Ship a controlled alpha, then choose one post-release vertical slice from observed user demand.
