# iOS Release Owner Evidence Record

Use this record when closing the remaining first-release gates that require owner credentials, Apple Developer access, GitHub deployment rights, App Store Connect access, or a physical device/TestFlight install.

This file is a template and handoff checklist, not the evidence store itself. Keep final evidence in the release owner's private storage and commit only sanitized summaries back to `docs/ios-first-release-acceptance.md` or `PROJECT_MEMORY.md`.

## Evidence Rules

Never record:

- school account names, passwords, captcha values, MFA codes, safety-verification codes, cookies, CAS tickets, OAuth tokens, or raw school-system responses;
- screenshots showing a real student's name, account, phone number, grades, class schedule, campus-card turnover, library-seat booking, or other personal data;
- Apple certificates, provisioning profiles, App Store Connect account details, private API keys, or complete signing/team account exports;
- full app preference dumps, HAR files, simulator/device containers, or system logs that may contain credentials or session material.

Acceptable evidence:

- command name, commit hash, build version, sanitized pass/fail status, device model, OS version, Xcode version, workflow URL, public URL, and App Store Connect build number;
- sanitized `debugRealFeatureValidationResults` lines written by `IOS_REAL_FEATURE_RESULTS_PATH`;
- screenshots only when they use preview-safe data or are fully redacted before storage;
- device logs only after checking that credentials, cookies, tickets, verification codes, tokens, and `[DEBUG-AUTH-2FA]` output are absent.

## Baseline Metadata

Fill before executing owner-only gates.

| Field | Value |
|---|---|
| Release owner | |
| Date/time | |
| Git branch | |
| Git commit | |
| iOS marketing version/build | |
| Xcode version | |
| macOS version | |
| Test simulator/device | |
| Physical device and iOS version | |
| App Store Connect app/build id | |
| Private evidence storage location | |

## Execution Order

1. Confirm the local automated gate has passed for the target commit.
2. Run or record the remote `iOS Validation` workflow for the same commit.
3. Deploy and verify public privacy/support URLs.
4. Complete owner-operated live feature validation with sanitized output.
5. Build and validate a signed Release archive, then export/upload if approved.
6. Install the TestFlight build on a physical device and execute the acceptance matrix.
7. Complete App Store Connect listing, privacy, screenshots, review notes, and final pre-submit checks.

## Automated And Remote Gates

| Gate | Required evidence | Result |
|---|---|---|
| Local automated release gate | Command, commit, result, `.xcresult` path when retained, archive path when retained | |
| Remote `iOS Validation` workflow | GitHub workflow URL, commit, pass/fail result | |
| Remote `Website Pages` workflow | GitHub workflow URL, commit, pass/fail result | |
| Public privacy URL | Final absolute URL and HTTP 200/browser verification | |
| Public support URL | Final absolute URL and HTTP 200/browser verification | |

## Owner-Operated Live Feature Gate

Run the controlled Debug diagnostic from `docs/ios-release-checklist.md`:

```bash
IOS_REAL_FEATURE_RESULTS_PATH=/absolute/private/path/real-feature-results.txt \
iosApp/scripts/run-real-feature-validation.sh
```

The release owner enters credentials manually if the app shows the login screen. The script must only persist sanitized diagnostic result lines.

| Feature/scenario | Required evidence | Result |
|---|---|---|
| Library seats success | Sanitized success line or redacted owner note | |
| Library seats legitimate empty state | Sanitized empty-state line or redacted owner note | |
| Library seats action/paging failure recovery | Sanitized result or redacted owner note | |
| Coupons success | Sanitized success line or redacted owner note | |
| Coupons empty/error/paging recovery | Sanitized result or redacted owner note | |
| School-course search success | Sanitized success line or redacted owner note | |
| School-course search empty/error/paging recovery | Sanitized result or redacted owner note | |
| Direct/WebVPN access-mode validation where supported | Sanitized state transition and user-visible result | |
| Captcha/MFA/account choice when naturally presented | Challenge type and sanitized completion state only | |
| Per-site reauthorization and resumed feature load | Site name, resumed feature, sanitized completion state | |
| Logout, relaunch, saved restore, offline/stale-cache behavior | Sanitized acceptance-matrix note | |

## Signed Archive And TestFlight Gate

Run the signed gate only from a machine where the release owner has approved Apple Developer access:

```bash
IOS_DEVELOPMENT_TEAM=<team-id> iosApp/scripts/run-signed-release-gate.sh
```

Add `IOS_EXPORT_OPTIONS_PLIST=/absolute/path/ExportOptions.plist` only when the release owner has approved the export configuration.

| Gate | Required evidence | Result |
|---|---|---|
| Signed archive validation | Command, commit, archive version/build, `REQUIRE_SIGNED=1` pass | |
| Optional IPA export | Export method, output path or storage id, no embedded secrets in evidence | |
| App Store Connect upload | Build number, processing status, upload timestamp | |
| TestFlight install | Device model, iOS version, build number | |
| Release ignores preview/debug launch arguments | TestFlight/device result | |
| No Debug auth logging | Device log scan result, specifically no `[DEBUG-AUTH-2FA]` | |
| No preserved-container Debug/preview stale-cache read | Upgrade/install scenario result | |
| Crash/log policy check | Result from `docs/ios-crash-log-policy.md` | |

## App Store Connect Gate

| Gate | Required evidence | Result |
|---|---|---|
| App identity | Final app name, bundle id, category, age rating | |
| Privacy questionnaire | Release-owner decision on XJTU traffic classification and final answers | |
| Privacy policy/support URLs | Exact App Store Connect URLs | |
| Screenshots | Screenshot set source, dimensions, privacy review result | |
| Review notes | Final non-sensitive notes, no credentials in repository/public channels | |
| AppIcon review | Current generated icon accepted, or owner-provided replacement validated | |
| Final submit/pre-submit status | App Store Connect status and unresolved warnings | |

## Failure Record

Use this for any failed owner gate.

| Field | Value |
|---|---|
| Gate/scenario | |
| Commit/build | |
| Expected behavior | |
| Actual behavior | |
| Sanitized evidence reference | |
| Credential/session data checked absent | |
| Reproduction notes | |
| Owner action needed | |
| Code/documentation follow-up | |

Only create code changes for evidence-backed failures. If the failure depends on school-system availability, Apple account state, App Store Connect review policy, or GitHub permissions, record it as an external release-owner gate instead of changing production behavior speculatively.
