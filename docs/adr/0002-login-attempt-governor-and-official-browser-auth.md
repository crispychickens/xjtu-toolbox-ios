# ADR 0002: Login Attempt Governor And Official Browser Auth

## Status

Accepted

## Context

XJTU login verification changes frequently. Repeated password, MFA, or WebVPN requests can also trigger school-system risk controls, especially when a client retries failed credentials without user intervention.

The iOS migration should avoid high-frequency login attempts and should consider using the official unified authentication page where possible.

## Decision

Keep login as a deep module behind `AuthManager`, and add two explicit safeguards:

- Password login is governed by `LoginAttemptGovernor`. Transient failures use exponential backoff, the attempt store can be persisted by platform adapters, exact invalid credentials are blocked from being retried, and pending MFA/browser-auth states are returned without starting a new school request.
- MFA, Safety Verify, and browser-auth callback submissions use challenge-scoped throttling. A rejected verification response or invalid callback records a short cooldown for that challenge before another school-system request is allowed.
- Official browser auth is modeled as `beginBrowserAuth(site)` plus `resumeBrowserAuth(callbackUrl)`. iOS opens the school login URL with `ASWebAuthenticationSession`, waits for the user-driven callback, then passes the callback URL back into shared auth. The shared auth module validates callback scheme/state and extracts a CAS `ticket` or OAuth-style `code` before calling the auth engine. The app does not poll the login page or scrape Safari cookies.

Official browser auth is only a complete replacement for in-app CAS if the school auth system supports an app callback, Universal Link, OAuth-style redirect, or CAS `service` ticket URL that the app can validate. iOS cannot import HttpOnly cookies from Safari or `ASWebAuthenticationSession` into the app's own Ktor/URLSession cookie jar without an explicit redirect artifact such as a ticket or code.

## Consequences

- Positive: Password failures and temporary network/service failures no longer create tight retry loops.
- Positive: Wrong MFA/Safety Verify submissions and stale browser callbacks cannot hammer verification endpoints.
- Positive: Platform adapters can persist cooldown records across app restarts.
- Positive: The iOS app has a platform-native seam for official unified login without duplicating CAS page parsing in SwiftUI.
- Positive: Callback validation has locality in shared auth, so future CAS/OAuth callback changes can be tested without changing SwiftUI.
- Positive: Browser login can evolve independently inside the auth module as school redirect support is confirmed.
- Negative: If school CAS does not allow an app callback/service ticket, browser auth must remain a handoff option and the app still needs the in-app CAS adapter.
- Negative: Live verification still requires a real school account and an approved callback URL/scheme.
