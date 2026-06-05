# Context

## Domain

XJTU Toolbox is a campus utility app for Xi'an Jiaotong University students. It directly calls official school systems for authentication, schedules, grades, campus card data, notices, classrooms, and other study/life workflows without a third-party server.

The current source is an Android app. This repository now also contains the KMP shared core and the SwiftUI iOS migration shell.

## Core Concepts

- Unified Auth: CAS login, RSA password encryption, MFA, Safety Verify, account choice, and SSO cookies.
- Browser Auth: user-driven official unified-login handoff through an app callback URL, used when the school CAS flow can return a ticket/code to the app.
- Login Attempt Governor: the auth-layer guard that prevents high-frequency password retries after invalid credentials or transient school-system failures.
- Challenge Attempt Governor: the auth-layer guard that prevents high-frequency MFA, Safety Verify, and browser-auth callback submissions.
- Access Mode: direct campus-network access or WebVPN access, with automatic mode selection as the default.
- Auth Context: the authenticated principal and optional password credentials carried from AuthManager into SiteSession.
- Site Session: an authenticated session for one school subsystem such as JWXT, JWAPP, YWTB, campus card, or notices.
- Http Site Session: a Site Session that can execute authenticated business HTTP requests while keeping tokens, cookies, and WebVPN rewriting inside the session/backend layer.
- Feature Repository: a feature-facing interface that loads schedule, grade, campus card, notice, or empty-room data through a Site Session.
- Local Cache: app-owned persistence used for offline display and startup speed.

## Ubiquitous Language

- AuthManager: the single public interface for login, MFA, session acquisition, and logout.
- SiteSession: the interface a feature uses to make authenticated calls; UI must not depend on concrete login adapters.
- HttpSiteSession: the narrow capability interface used by real feature repositories that need HTTP. Repositories may execute requests through it, but must not own credentials or concrete login adapters.
- AccessMode: `AUTO`, `NORMAL`, or `WEBVPN`.
- Access Mode Store: the persisted user choice for automatic, direct, or WebVPN access; changing it invalidates existing SiteSession instances.
- Safety Verify: CAS server-side second verification page that can appear during any sensitive flow.
- BrowserAuthChallenge: a pending official-login handoff containing the login URL, callback scheme, state token, and optional target site.
- BrowserAuthCallback: the validated callback artifact from official login, carrying the returned state plus a CAS `ticket` or OAuth-style `code`.
- Browser Auth Start: the user-triggered request to prepare/open the official login handoff; transient failures are throttled separately from callback submissions.
- WebVPN Host Codec: the adapter responsible for school WebVPN host encoding/decoding.
- Core Feature: first iOS migration scope: auth, schedule, grades, campus card, notices, empty rooms, settings, and cache.
- KMP iOS Bridge: the Swift adapter layer that maps generated `XJTUToolboxShared.framework` exports into SwiftUI-facing auth and feature interfaces.
- KMP Platform Adapter: an iOS implementation of a shared seam such as CredentialVault, AccessModeStore, LoginAttemptStore, CookieStore, or HttpClient.
- Cookie-Aware HttpClient: the shared wrapper that attaches stored cookies to outgoing requests and persists `Set-Cookie` responses.
- Request Pacing HttpClient: the shared wrapper that serializes real school-system requests through a minimum interval so login, JWAPP, ncard, and WebVPN flows cannot accidentally burst from iOS UI refreshes.
- CasAuthEngine: the shared CAS adapter that owns login form parsing, RSA encryption seam usage, MFA detect, secure-phone SMS verification, and Safety Verify form replay behind `AuthManager`.
- NcardSessionAuthenticator: the campus-card site authenticator that uses an existing CAS/TGC-capable session backend to exchange an ncard SSO ticket for a bounded JWT-backed `HttpSiteSession`.
- JWAPP Academic Repositories: shared schedule, exam, and precise grade repositories that use `HttpSiteSession` plus fixture-tested JSON parsers rather than Android UI or concrete login adapters.
- Shared Preview Services: KMP-owned preview AuthManager and CoreFeatureService assembly used to validate iOS framework wiring before live school adapters exist.

## Boundaries

- In scope: KMP shared auth/session contracts, SwiftUI iOS shell, core feature models, parser guards, regression fixtures, Android compatibility during migration.
- Out of scope for first iOS release: Android widgets, APK self-update install flow, Android FileProvider behavior, Android Media3 implementation, and full v3.5.1 feature parity.
