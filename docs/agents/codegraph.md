# CodeGraph Navigation

This repository has a local CodeGraph index at `.codegraph/`.

## Current Index Snapshot

- Last checked: 2026-06-06
- Files indexed: 250
- Symbols/nodes: 8,231
- Edges: 17,236
- Languages: Kotlin, Swift, XML, Gradle Kotlin scripts, YAML, JavaScript/TypeScript/Vue, Python
- Main indexed areas: Android app, KMP `shared` core, SwiftUI `iosApp`, demo app, website support files

`.codegraph/` is a local generated index and is ignored by git. Rebuild or sync it locally instead of committing the database.

## Default Low-Token Workflow

Use CodeGraph before broad source reads when a task crosses modules or needs impact analysis.

```bash
codegraph status
codegraph context "<task>" --max-nodes 30 --max-code 0
codegraph query <symbol>
codegraph callers <symbol>
codegraph callees <symbol>
codegraph impact <symbol>
```

If `codegraph status` says the index is stale after file changes:

```bash
codegraph sync
```

If the module layout changed substantially:

```bash
codegraph index
```

## High-Value Entry Points

- Shared auth public contract: `AuthManager` in `shared/src/commonMain/kotlin/com/xjtu/toolbox/shared/auth/AuthContracts.kt`
- Shared auth host: `DefaultAuthManager` in `shared/src/commonMain/kotlin/com/xjtu/toolbox/shared/auth/DefaultAuthManager.kt`
- CAS adapter: `CasAuthEngine` in `shared/src/commonMain/kotlin/com/xjtu/toolbox/shared/auth/CasAuthEngine.kt`
- Session registry: `XjtuSessionRegistryFactory` in `shared/src/commonMain/kotlin/com/xjtu/toolbox/shared/session/XjtuSessionRegistryFactory.kt`
- Authenticated HTTP session seam: `BackendSiteSession` and `SessionBackend` under `shared/src/commonMain/kotlin/com/xjtu/toolbox/shared/session/`
- Core feature facade: `DefaultCoreFeatureService` in `shared/src/commonMain/kotlin/com/xjtu/toolbox/shared/features/DefaultCoreFeatureService.kt`
- Feature cache wrapper: `CachedCoreFeatureService` in `shared/src/commonMain/kotlin/com/xjtu/toolbox/shared/features/CachedCoreFeatureService.kt`
- iOS dependency switchboard: `AppDependencyFactory` in `iosApp/XJTUToolboxIOS/AppDependencies.swift`
- iOS KMP bridge: `KmpBridge.swift`, `KmpPlatformAdapters.swift`, and `SharedBridge.swift` under `iosApp/XJTUToolboxIOS/`
- Android legacy shell: `MainActivity` in `app/src/main/java/com/xjtu/toolbox/MainActivity.kt`
- Android legacy auth/site adapters: `app/src/main/java/com/xjtu/toolbox/auth/`

## Module Map

- `app`: imported Android application and Android-only feature screens, widgets, local persistence, and legacy login adapters.
- `shared`: Kotlin Multiplatform core for auth contracts, session backends, WebVPN rewriting, parser guards, feature repositories, preview services, and shared tests.
- `iosApp`: SwiftUI shell, app routing/state, Keychain/UserDefaults/URLSession platform adapters, and KMP bridge glue.
- `docs/adr`: accepted architecture decisions. Read relevant ADRs before changing auth, KMP/iOS boundaries, browser auth, or request throttling.
- `demo` and `website`: secondary demo/site surfaces; do not start there for auth/session/feature work unless the task names them.

## Query Recipes

- Auth/session change: `codegraph context "AuthManager login ensureSession CAS session registry" --max-nodes 30 --max-code 0`
- iOS dependency wiring: `codegraph query AppDependencyFactory`, then `codegraph callees AppDependencyFactory`
- Shared feature change: `codegraph context "DefaultCoreFeatureService feature repository parser tests" --max-nodes 30 --max-code 0`
- WebVPN change: `codegraph context "WebVPN URL codec request rewriter Android WebVpnUtil" --max-nodes 30 --max-code 0`
- Parser change: `codegraph query <ParserName>`, then read the matching parser test before editing.
- Android compatibility check: `codegraph impact <shared symbol>` and inspect affected Android imports before changing shared APIs.

## Working Rule

For cross-module tasks, record the relevant CodeGraph query in notes or final output. Only read source files selected by CodeGraph unless the query result is clearly incomplete.
