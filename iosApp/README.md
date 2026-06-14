# XJTU Toolbox iOS Shell

This folder contains the SwiftUI shell for the KMP migration.

The app target is intentionally thin:

- `SharedBridge.swift` defines the Swift-facing interface for the KMP `shared` module.
- `KmpBridge.swift` adapts generated `XJTUToolboxShared.framework` `AuthManager` and `CoreFeatureService` exports into the Swift-facing interfaces.
- `KmpPlatformAdapters.swift` adapts Keychain credentials, UserDefaults access mode, UserDefaults login cooldowns, `HTTPCookieStorage`, and `URLSession` into KMP platform seams.
- `KmpRsaPasswordEncryptor` adapts iOS Security.framework RSA/PKCS1 encryption for CAS password submission, and `KmpUserDefaultsVisitorIdProvider` persists the CAS `fpVisitorId`.
- `KmpSessionBackendFactory` builds direct/WebVPN KMP session backends with iOS HTTP, request pacing, and cookie adapters; `URLSession` automatic cookie injection is disabled so shared owns cookie behavior.
- `AppDependencies.swift` centralizes preview and KMP dependency wiring. When `XJTUToolboxShared.framework` is importable, Debug default app wiring uses a KMP preview auth manager backed by iOS platform storage and a shared `CachedCoreFeatureService` so the SwiftUI shell exercises the generated framework path. Release default app wiring uses the real first-release assembly. Real auth assembly accepts an injected `SessionRegistry` so site sessions can be added without changing SwiftUI stores. Real KMP feature wiring adds a policy-gated Swift stale fallback only for schedule, public notices, and public empty rooms.
- `AppDependencyFactory.makeKmpPreviewWithRealEmptyRooms()` can switch the iOS shell to real public empty-room CDN data while keeping auth-gated features on preview repositories.
- `AppDependencyFactory.makeKmpPreviewWithRealPublicData()` can switch the iOS shell to real public notices plus real public empty-room CDN data while keeping auth-gated features on preview repositories.
- `AppDependencyFactory.makeKmpWithRealLoginValidation()` switches only auth to the real CAS password path while keeping feature data on preview repositories. This path supports manual CAS graph captcha and MFA, and login success will not automatically trigger JWAPP/ncard feature requests.
- `AppDependencyFactory.makeKmpWithRealCampusCardAndPublicData()` is an explicit development assembly for real CAS auth, real ncard campus-card data, and real public data. It is not the default startup path.
- `AppDependencyFactory.makeKmpWithRealFirstReleaseCore()` is the broadest assembly for real CAS auth, JWAPP schedule/grades and school-wide course search, ncard campus card, public notices, empty rooms, library seats, and coupons. It is the Release default and remains available as an explicit Debug validation scheme.
- Real opt-in assemblies pace direct school-system requests by at least 750 ms and WebVPN requests by at least 1000 ms through shared `RequestPacingHttpClient`.
- `makeDefault()` is build-configuration aware: Debug can select real assemblies only when an explicit launch argument is present (`-XJTURealLoginValidation`, `-XJTURealEmptyRooms`, `-XJTURealPublicData`, `-XJTURealCampusCardAndPublicData`, or `-XJTURealFirstReleaseCore`), while Release ignores dependency override arguments and uses the real first-release assembly.
- `AccessModeStore.swift` persists the iOS access-mode picker until it is replaced by the KMP settings adapter. Settings shows the active validation mode, authentication/data source, bundle version, Keychain/cache boundary, and access-mode session invalidation copy so testers can tell whether they are in preview or real integration mode.
- `AuthStore.swift` owns login state, graph-captcha submission, MFA submission, and official browser-auth callback resume.
- `AuthStore.swift` also owns CAS account-type challenge state. If CAS asks the user to choose undergraduate vs postgraduate identity, SwiftUI defaults to undergraduate when available and waits for explicit confirmation.
- Real CAS validation modes hide the official browser-login button because the current `xjtutoolbox://auth` callback service is not accepted by school CAS.
- The profile tab displays the active dependency mode, authenticated username, auth source, feature-data source, access mode, and current auth/session status. When a site needs reauthorization while the app shell stays authenticated, Profile offers the same continue-verification action as the inline banner instead of implying a full logout.
- `FeatureBridge.swift` defines Swift-facing feature models and the `SharedFeatureProviding` adapter seam.
- `FeatureCacheStore.swift` wraps Swift-facing feature providers with UserDefaults-backed TTL cache and stale-data fallback. The real KMP provider only persists the schedule, notice, and empty-room cache keys; grades, campus card, and dashboard data are deliberately excluded because UserDefaults is not a secret store. Persistent feature cache keys include build configuration and dependency mode, preventing Release from reading stale Debug/preview cache. Settings clears feature cache only after confirmation and does not remove Keychain credentials.
- `PrivacyInfo.xcprivacy` is the bundled iOS privacy manifest. It currently declares no tracking, no collected data types, and the UserDefaults required-reason API category used by access-mode, login-cooldown, visitor-id, and allowed stale feature-cache persistence.
- `FeatureStore.swift` owns dashboard, schedule, grade, campus card, coupon, notice, empty-room, library-seat, and school-course-search loading/filter/action state for SwiftUI, and gates forced provider-cache clearing to one clear per 30 seconds to avoid repeated pull-to-refresh request bursts. The homepage renders today's courses first, followed by compact campus-card and notice sections; combined schedule teaching-week/day and textbook filtering, grade filtering/sorting, coupon status filtering, and notice source filtering stay local over loaded records; campus-card transactions, coupons, public notices, and school-course results use total-aware user-driven pagination through the KMP feature seam, and known post-transaction balances remain visible; empty-room filtering supports campus, teaching building, minimum capacity, date, and section range; library seats support area availability, current booking, recommendations, and manual book/swap without persisting snapshots in UserDefaults. School-course search is user-triggered and is not persisted.
- The `日程` tab keeps courses, exams, and textbooks one tap away through a text-only segmented selector. Its compact summary and controls change with the selected view, so course week/day filters and textbook filters appear only where they apply.
- Textbook rows preserve available edition and price through the KMP/Swift bridge. They prioritize course and textbook names, then show non-duplicative edition/price purchase checks before author, publisher, and ISBN details.
- Exam records preserve course code, separate date/time, location, and seat number through the KMP/Swift bridge. The iOS exam view sorts them chronologically and prioritizes course/seat, then date/time, then location/course code.
- The `学辅` tab keeps grades, campus card, coupons, library seats, school-wide course search, empty rooms, and notices one tap away through a horizontally scrollable text-only selector. It renders and loads only the selected function instead of stacking and requesting every module on entry.
- Grade records preserve their term code through both real JWAPP paths and the KMP/Swift bridge. The iOS grade view defaults to the latest available term, keeps "全部学期" one selection away, and scopes GPA, credits, highest grade point, and attention counts to the selected term.
- Grade rows with a mobile JWAPP record id open an on-demand score-detail view in one tap. The detail view prioritizes score/GPA/credit and component scores, keeps secondary course metadata below them, and does not persist grade-detail data.
- Homepage and `学辅` notice rows open valid `http`/`https` source links in the system browser while malformed, credential-bearing, or non-Web links stay read-only.
- Public notices are sorted and paged after cross-source deduplication. The shared notice page preserves the aggregate total, so iOS shows loaded/total progress, appends stable-id-deduplicated pages through a text-only "加载更多通知" action only while records remain, and resets to page one on refresh.
- Empty-room capacity from the public CDN is preserved through shared and Swift models. The iOS empty-room view can filter locally by minimum capacity, displays known seat counts, and sorts results by building, capacity, then room name.
- `OfficialBrowserAuthPresenter.swift` opens the school login page through `ASWebAuthenticationSession`; the app only resumes after an explicit callback URL.
- `LoginAttemptStore.swift` is the iOS persistence adapter shape for login cooldown records when wiring the generated KMP auth bridge.
- `Router.swift` owns tab and stack navigation.
- `KeychainCredentialStore.swift` stores credentials in the iOS Keychain.
- Live-account validation should use owner-entered credentials in the app's `SecureField`s and then restore them from the simulator Keychain for repeated agent-assisted runs. Do not put account names, passwords, verification codes, CAS tickets, or OAuth tokens in launch arguments, source files, logs, screenshots, or docs.
- Auth network diagnostics compile only in Debug, require `-XJTUAuthNetworkDebug`, and log structural metadata rather than raw values or response text. Release archive validation enforces the first-release crash/log policy in `docs/ios-crash-log-policy.md`.
- SwiftUI views keep the Android information architecture and visual density while using native iOS controls.
- SwiftUI presentation stays restrained and task-first: primary information comes before secondary metadata, decorative icons are minimized, and equally important actions remain one step away.

The XcodeGen project in `project.yml` runs `:shared:embedAndSignAppleFrameworkForXcode` before compiling the app and searches the standard KMP `shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` output path. The KMP build script is marked as intentionally always-running so Xcode does not skip framework embedding when shared code changes.

`project.yml` is also the source of truth for the iOS marketing version, build number, and first-release iPhone-only device family. `scripts/validate-release-archive.sh` inspects a generic-device archive for identity/version consistency, arm64 and dSYM integrity, privacy/ATS policy, and optional signing evidence. Use `docs/ios-release-checklist.md` for the complete promotion gate.

Local build commands:

```bash
cd iosApp
xcodegen generate
cd ..
xcodebuild -project iosApp/XJTUToolboxIOS.xcodeproj \
  -scheme XJTUToolboxIOS \
  -configuration Debug \
  -destination 'platform=iOS Simulator,name=iPhone 16,OS=18.6' \
  build

xcodebuild -project iosApp/XJTUToolboxIOS.xcodeproj \
  -scheme XJTUToolboxIOS \
  -configuration Release \
  -sdk iphonesimulator \
  CODE_SIGNING_ALLOWED=NO \
  build
```

Local validation schemes:

1. `XJTUToolboxIOS` launches the default preview-safe app in Debug and archives with Release configuration.
2. `XJTUToolboxIOS-PreviewAutoLogin` enters the SwiftUI shell with preview auth and preview data.
3. `XJTUToolboxIOS-PreviewAccountChoice` opens the account-type challenge with preview auth so undergraduate-default selection can be checked without contacting CAS.
4. `XJTUToolboxIOS-RealLoginValidation` uses real CAS auth with preview feature data. Use this first for live-account validation; post-login schedule, grade, card, and dashboard values are not expected to match the real account in this scheme.
5. `XJTUToolboxIOS-RealPublicData` enters the `学辅` tab with preview auth, preview auth-gated features, and real public notices plus real public empty-room CDN data. This is the first no-account integration path and can validate empty-room campus/building/date/section filters without a live account.
6. `XJTUToolboxIOS-RealFirstReleaseCore` uses saved/entered real CAS credentials with the real first-release repositories. Feature screens auto-attempt each load at most once per app lifetime; failures do not auto-retry on redraw or tab switching.

The generated `XJTUToolboxIOSTests` target includes cache/store, launch-policy, auth-state, and KMP bridge contract coverage. `KmpBridgeContractTests` calls the generated shared framework and every first-release feature method rather than replacing the KMP layer with Swift spies. Run the unit/contract and UI-smoke suites through the main scheme:

```bash
xcodebuild test -project iosApp/XJTUToolboxIOS.xcodeproj \
  -scheme XJTUToolboxIOS \
  -destination 'platform=iOS Simulator,name=iPhone 16,OS=18.6'
```

Use `docs/ios-first-release-acceptance.md` as the evidence checklist before promoting a build beyond controlled alpha validation.

Next integration step:

1. Run `XJTUToolboxIOS-PreviewAccountChoice` when checking the account-type challenge UI and default undergraduate selection without a live account.
2. Run `XJTUToolboxIOS-RealLoginValidation` when validating CAS password login, graph captcha, MFA, Safety Verify, and access-mode behavior with a live account. Passwords and verification codes must be entered by the account owner and must not be written into launch arguments, source files, logs, or docs.
3. Run `XJTUToolboxIOS-RealPublicData` when validating public notices and empty rooms together in the iOS shell.
4. Use `-XJTURealCampusCardAndPublicData` only after login-only validation passes and when validating real CAS plus real ncard campus-card behavior with a test account.
5. Use `XJTUToolboxIOS-RealFirstReleaseCore` when validating the first-release real KMP repositories in Debug with a test account and controlled refresh cadence. Release uses the same real-first-release dependency path by default and still needs clean-install runtime/archive evidence before promotion. The live mobile grade path keeps the school-registered HTTP JWAPP callback, lets iOS stop before insecure redirects, upgrades the code callback to HTTPS in shared code, and reuses the returned mobile token through the grade `BackendSiteSession`. If a feature returns CAS/Safety Verify HTML, stop and fix the site-level auth challenge path before retrying.
6. Do not ask testers to prefer the browser-login button for current validation: live testing on 2026-05-30 showed school CAS rejects the unregistered `xjtutoolbox://auth` service. Browser login becomes viable only after CAS accepts an app callback/Universal Link or another app-readable artifact.
