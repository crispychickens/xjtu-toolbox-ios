# iOS Crash And Log Policy

This policy applies to the first iOS release, its local validation builds, device archives, App Store Connect processing, and TestFlight validation.

## Release Policy

- Release builds do not emit app-owned authentication/network debug logs. `AuthNetworkDebugLog` and its call sites compile only under `#if DEBUG`.
- The Release archive gate rejects an executable containing the `[DEBUG-AUTH-2FA]` marker.
- The first release does not embed a third-party crash-reporting or telemetry SDK. The archive gate rejects known unreviewed crash-SDK markers.
- Keep the matching archive and dSYM for every uploaded build. Use App Store Connect/TestFlight crash reports as the first-release crash evidence source.
- Do not add remote diagnostics, analytics, or crash upload without an explicit privacy review and corresponding App Store privacy-disclosure update.

## Debug Validation Policy

Debug authentication logging is opt-in through `-XJTUAuthNetworkDebug` and remains disabled unless the launch argument is present.

Allowed debug metadata:

- HTTP method, host, path-segment count, status code, error domain/code;
- request/response header names, cookie names, body field names, redirect parameter names;
- coarse body shape such as `login-html`, `safety-html`, `json-keys=...`, or text length.

Never log:

- usernames, passwords, encrypted passwords, verification codes, account-choice values, visitor IDs;
- cookie values, CAS tickets, OAuth codes/tokens, JWTs, query/fragment values, request-body values;
- raw HTML/JSON/text bodies, CAS alert text, HTML titles/snippets, or error descriptions that may contain URLs or response text.

## Crash And Incident Handling

1. Record build version, build number, device/OS version, screen/workflow, and reproducible steps without account data.
2. Preserve the matching `.xcarchive` and dSYM; symbolicate through Xcode Organizer/App Store Connect.
3. Before sharing any device log or crash report, remove usernames, URLs with query/fragment data, cookies, tickets, tokens, verification codes, and school-system response content.
4. Store only the minimum sanitized evidence required to reproduce and fix the issue.
5. Re-run the automated archive gate and inspect TestFlight device logs before promotion.

## Verification

The automated baseline is:

```bash
xcodebuild test \
  -project iosApp/XJTUToolboxIOS.xcodeproj \
  -scheme XJTUToolboxIOS \
  -destination 'platform=iOS Simulator,name=iPhone 16,OS=18.6'

xcodebuild archive \
  -project iosApp/XJTUToolboxIOS.xcodeproj \
  -scheme XJTUToolboxIOS \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath /tmp/XJTUToolboxIOS.xcarchive \
  CODE_SIGNING_ALLOWED=NO

iosApp/scripts/validate-release-archive.sh /tmp/XJTUToolboxIOS.xcarchive
```

Signed-device/TestFlight log inspection remains mandatory because a static archive check cannot prove OS/framework logs or every runtime failure path.
