# ADR 0001: KMP Shared Core With SwiftUI iOS Shell

## Status

Accepted

## Context

The Android app is a large Kotlin + Jetpack Compose codebase that directly talks to XJTU school systems. Its highest-change area is authentication: CAS, RSA password encryption, MFA, Safety Verify, WebVPN, cookies, site tokens, and per-site re-authentication.

The iOS migration should avoid rewriting these high-risk flows twice. The Android UI also uses MIUIX/HyperOS-specific Compose widgets that are not a good direct fit for iOS.

## Decision

Use Kotlin Multiplatform for the shared core and SwiftUI for the iOS app shell.

The `shared` module owns auth contracts, auth state machine hosting, WebVPN URL seams, core feature models, parser guards, and repository interfaces. iOS owns native SwiftUI navigation, screens, Keychain/cookie adapters, and platform presentation.

The auth module is a deep module. Callers use only `AuthManager.login`, `AuthManager.ensureSession`, `AuthManager.submitMfa`, `AuthManager.logout`, `AuthManager.currentAccessMode`, and `AuthManager.authState`. CAS form details, MFA/Safety Verify flow, WebVPN conversion, cookie isolation, and token refresh stay behind that interface.

## Consequences

- Positive: Login changes have locality in one module and can be tested through one public interface.
- Positive: Android and iOS can share parsing, session semantics, feature contracts, and regression fixtures.
- Positive: iOS can feel native while preserving the Android information architecture.
- Negative: The first migration phase must build platform adapters before every existing feature can be fully wired.
- Negative: Some Android-only capabilities need platform-specific replacements, such as WidgetKit for widgets and AVPlayer for video.
