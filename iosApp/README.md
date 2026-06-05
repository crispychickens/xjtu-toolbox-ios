# XJTU Toolbox iOS Shell

This folder contains the SwiftUI shell for the KMP migration.

The app target is intentionally thin:

- `SharedBridge.swift` defines the Swift-facing interface for the KMP `shared` module.
- `KmpBridge.swift` adapts generated `XJTUToolboxShared.framework` `AuthManager` and `CoreFeatureService` exports into the Swift-facing interfaces.
- `KmpPlatformAdapters.swift` adapts Keychain credentials, UserDefaults access mode, UserDefaults login cooldowns, `HTTPCookieStorage`, and `URLSession` into KMP platform seams.
- `KmpRsaPasswordEncryptor` adapts iOS Security.framework RSA/PKCS1 encryption for CAS password submission, and `KmpUserDefaultsVisitorIdProvider` persists the CAS `fpVisitorId`.
- `KmpSessionBackendFactory` builds direct/WebVPN KMP session backends with iOS HTTP, request pacing, and cookie adapters; `URLSession` automatic cookie injection is disabled so shared owns cookie behavior.
- `AppDependencies.swift` centralizes preview and KMP dependency wiring. When `XJTUToolboxShared.framework` is importable, the default app wiring uses a KMP preview auth manager backed by iOS platform storage and a shared `CachedCoreFeatureService` so the SwiftUI shell exercises the generated framework path. Real auth assembly accepts an injected `SessionRegistry` so site sessions can be added without changing SwiftUI stores. Real KMP feature wiring adds a policy-gated Swift stale fallback only for schedule, public notices, and public empty rooms.
- `AppDependencyFactory.makeKmpPreviewWithRealEmptyRooms()` can switch the iOS shell to real public empty-room CDN data while keeping auth-gated features on preview repositories.
- `AppDependencyFactory.makeKmpPreviewWithRealPublicData()` can switch the iOS shell to real public notices plus real public empty-room CDN data while keeping auth-gated features on preview repositories.
- `AppDependencyFactory.makeKmpWithRealLoginValidation()` switches only auth to the real CAS password path while keeping feature data on preview repositories. This path supports manual CAS graph captcha and MFA, and login success will not automatically trigger JWAPP/ncard feature requests.
- `AppDependencyFactory.makeKmpWithRealCampusCardAndPublicData()` is an explicit development assembly for real CAS auth, real ncard campus-card data, and real public data. It is not the default startup path.
- `AppDependencyFactory.makeKmpWithRealFirstReleaseCore()` is the broadest explicit development assembly for real CAS auth, JWAPP schedule/grades, ncard campus card, public notices, and empty rooms. It is not the default startup path.
- Real opt-in assemblies pace direct school-system requests by at least 750 ms and WebVPN requests by at least 1000 ms through shared `RequestPacingHttpClient`.
- `makeDefault()` can select real assemblies only when an explicit launch argument is present: `-XJTURealLoginValidation`, `-XJTURealEmptyRooms`, `-XJTURealPublicData`, `-XJTURealCampusCardAndPublicData`, or `-XJTURealFirstReleaseCore`.
- `AccessModeStore.swift` persists the iOS access-mode picker until it is replaced by the KMP settings adapter.
- `AuthStore.swift` owns login state, graph-captcha submission, MFA submission, and official browser-auth callback resume.
- `AuthStore.swift` also owns CAS account-type challenge state. If CAS asks the user to choose undergraduate vs postgraduate identity, SwiftUI defaults to undergraduate when available and waits for explicit confirmation.
- Real CAS validation modes hide the official browser-login button because the current `xjtutoolbox://auth` callback service is not accepted by school CAS.
- The profile tab displays the active dependency mode, authenticated username, auth source, and feature-data source. `XJTUToolboxIOS-RealLoginValidation` intentionally reports real CAS auth with preview feature data.
- `FeatureBridge.swift` defines Swift-facing feature models and the `SharedFeatureProviding` adapter seam.
- `FeatureCacheStore.swift` wraps Swift-facing feature providers with UserDefaults-backed TTL cache and stale-data fallback. The real KMP provider only persists the schedule, notice, and empty-room cache keys; grades, campus card, and dashboard data are deliberately excluded because UserDefaults is not a secret store. Settings clears feature cache only after confirmation and does not remove Keychain credentials.
- `FeatureStore.swift` owns dashboard, schedule, grade, campus card, notice, and empty-room loading/filter state for SwiftUI, and gates forced provider-cache clearing to one clear per 30 seconds to avoid repeated pull-to-refresh request bursts. Schedule day and textbook filtering, grade filtering/sorting, and notice source filtering stay local over loaded records; campus-card transactions are paged through the KMP feature seam with a user-driven "加载更多流水" action; empty-room filtering supports campus, teaching building, date, and section range.
- `OfficialBrowserAuthPresenter.swift` opens the school login page through `ASWebAuthenticationSession`; the app only resumes after an explicit callback URL.
- `LoginAttemptStore.swift` is the iOS persistence adapter shape for login cooldown records when wiring the generated KMP auth bridge.
- `Router.swift` owns tab and stack navigation.
- `KeychainCredentialStore.swift` stores credentials in the iOS Keychain.
- Live-account validation should use owner-entered credentials in the app's `SecureField`s and then restore them from the simulator Keychain for repeated agent-assisted runs. Do not put account names, passwords, verification codes, CAS tickets, or OAuth tokens in launch arguments, source files, logs, screenshots, or docs.
- SwiftUI views keep the Android information architecture and visual density while using native iOS controls.

The XcodeGen project in `project.yml` runs `:shared:embedAndSignAppleFrameworkForXcode` before compiling the app and searches the standard KMP `shared/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)` output path. The KMP build script is marked as intentionally always-running so Xcode does not skip framework embedding when shared code changes.

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
```

Local validation schemes:

1. `XJTUToolboxIOS` launches the default preview-safe app.
2. `XJTUToolboxIOS-PreviewAutoLogin` enters the SwiftUI shell with preview auth and preview data.
3. `XJTUToolboxIOS-PreviewAccountChoice` opens the account-type challenge with preview auth so undergraduate-default selection can be checked without contacting CAS.
4. `XJTUToolboxIOS-RealLoginValidation` uses real CAS auth with preview feature data. Use this first for live-account validation; post-login schedule, grade, card, and dashboard values are not expected to match the real account in this scheme.
5. `XJTUToolboxIOS-RealPublicData` enters the `学辅` tab with preview auth, preview auth-gated features, and real public notices plus real public empty-room CDN data. This is the first no-account integration path and can validate empty-room campus/building/date/section filters without a live account.
6. `XJTUToolboxIOS-RealFirstReleaseCore` uses saved/entered real CAS credentials with the real first-release repositories. Feature screens auto-attempt each load at most once per app lifetime; failures do not auto-retry on redraw or tab switching.

Next integration step:

1. Run `XJTUToolboxIOS-PreviewAccountChoice` when checking the account-type challenge UI and default undergraduate selection without a live account.
2. Run `XJTUToolboxIOS-RealLoginValidation` when validating CAS password login, graph captcha, MFA, Safety Verify, and access-mode behavior with a live account. Passwords and verification codes must be entered by the account owner and must not be written into launch arguments, source files, logs, or docs.
3. Run `XJTUToolboxIOS-RealPublicData` when validating public notices and empty rooms together in the iOS shell.
4. Use `-XJTURealCampusCardAndPublicData` only after login-only validation passes and when validating real CAS plus real ncard campus-card behavior with a test account.
5. Use `XJTUToolboxIOS-RealFirstReleaseCore` only when validating the first-release real KMP repositories with a test account and controlled refresh cadence. The live mobile grade path keeps the school-registered HTTP JWAPP callback, lets iOS stop before insecure redirects, upgrades the code callback to HTTPS in shared code, and reuses the returned mobile token through the grade `BackendSiteSession`. If a feature returns CAS/Safety Verify HTML, stop and fix the site-level auth challenge path before retrying.
6. Do not ask testers to prefer the browser-login button for current validation: live testing on 2026-05-30 showed school CAS rejects the unregistered `xjtutoolbox://auth` service. Browser login becomes viable only after CAS accepts an app callback/Universal Link or another app-readable artifact.
