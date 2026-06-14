# iOS App Store Privacy Review

This is an engineering review for the first iOS release. It is not legal approval. The release owner must make the final App Store Connect answers and publish the public privacy policy/support URLs before TestFlight or App Store submission.

## Apple Disclosure Basis

Apple's App Store privacy details require the account holder to describe the app's privacy practices, including integrated third-party code. Apple asks developers to identify data that the app or third-party partners collect, where "collect" means transmitting data off device in a way that lets the developer or partner access it beyond what is needed to service the request in real time. Apple also requires a privacy policy URL on the product page.

Reference: [Apple Developer: App privacy details](https://developer.apple.com/app-store/app-privacy-details/).

## Local Implementation Evidence

| Area | Current evidence | Disclosure impact |
|---|---|---|
| Tracking, ads, analytics, crash SDKs | No ATT/IDFA, advertising, analytics, Firebase, Crashlytics, Sentry, or remote telemetry usage was found in the iOS target. The Release archive validator rejects known unreviewed crash-reporting SDK markers. | Tracking should remain `No` unless a new SDK is added. |
| Privacy manifest | `iosApp/XJTUToolboxIOS/PrivacyInfo.xcprivacy` declares no tracking, no tracking domains, no collected data types, and UserDefaults required-reason API `CA92.1`. | Required-reason API disclosure is UserDefaults only. |
| System permissions | No camera, photo library, contacts, location, health, motion, microphone, or similar `UsageDescription` keys are currently declared for iOS. | No permission-purpose copy is needed for first release unless features change. |
| Credentials | Username/password are entered by the user and saved only through the iOS Keychain credential store when credential persistence is used. The Keychain item uses this-device-only accessibility. Logout clears the KMP credential vault. | Do not log, screenshot, or include credentials in review notes. If the release owner chooses conservative data disclosure, account/login data belongs in the App Functionality purpose, not tracking. |
| Captcha, MFA, account choice, site verification | Challenge values are transient UI/auth-flow inputs. Settings copy explicitly states captcha values are not cached. | Treat as transient authentication data; keep out of logs and evidence. |
| Cookies, tickets, and tokens | KMP owns cookie/session state through `CookieAwareHttpClient` and session backends. iOS bridges cookies into `HTTPCookieStorage` only for the shared client path. Logout/session invalidation clears shared state. Release log policy forbids these values in logs/evidence. | Session data supports App Functionality only. It must not be disclosed as tracking. |
| UserDefaults | iOS persists access mode, login cooldown records, a CAS visitor ID, authenticated-shell boolean, and the allowed stale feature-cache namespace. | The privacy manifest already declares UserDefaults reason `CA92.1`. |
| Local feature cache | Persistent stale cache is intentionally limited to schedule, public notices, and public empty rooms. Grades, campus card, dashboard, coupons, library seats, grade details, and school-course searches are not persisted in UserDefaults. | If the release owner discloses locally cached education data, keep the scope limited to first-release cached surfaces. |
| Network destinations | First-release network requests go directly to XJTU school systems and public school endpoints. There is no developer-operated backend in the current iOS implementation. | The key App Store Connect decision is whether XJTU endpoints count as a third-party partner/data recipient for this release account. |
| Browser auth | `ASWebAuthenticationSession` code exists, but production official browser auth remains blocked until XJTU CAS accepts an app callback/Universal Link and returns an app-readable artifact. | Do not describe browser-auth as a production release path unless that gate changes. |

## Draft App Store Connect Answers

Use these as the starting point for the release owner's App Store Connect entry:

- Tracking: `No`.
- Data used for tracking: `None`.
- Required-reason APIs: UserDefaults, reason `CA92.1`.
- Data collected: engineering evidence supports `No` only if the release owner confirms there is no developer-operated backend, no integrated third-party SDK/partner collection, and XJTU school-system traffic is treated as real-time servicing of the user's own request rather than developer/partner collection.
- Privacy policy URL: required and still pending. Use `docs/ios-privacy-policy-draft.md` as the source for the published page.
- Support URL: required for the App Store listing and still pending.

## Conservative Disclosure Fallback

If App Review, the account holder, or legal review decides that XJTU school systems should be treated as a third-party recipient/partner for this independently released app, do not use the `No data collected` answer. The conservative fallback is to disclose data sent to school systems for `App Functionality`, linked to the user's school account, and not used for tracking.

Candidate data categories to review in App Store Connect:

- User ID or account identifier: school account/student identifier used for CAS login and school-system access.
- Other user content or other data: schedule, grades, exam/textbook data, library-seat/coupon/course-search requests, and other school-system records displayed in the app.
- Purchase history or financial information: only if campus-card turnover or card balance records fit Apple's category definitions for the selected release account.
- Search history: only if school-wide course search queries are considered user search history under the selected disclosure model.

Do not add categories mechanically. The release owner should select the minimum accurate set based on App Store Connect's current category definitions and the final first-release feature set.

## Remaining Release Gates

- Publish the privacy policy and support URLs.
- Enter the final App Store Connect privacy questionnaire answers.
- Re-run the Release archive validator on the signed archive.
- Inspect a signed device build or TestFlight build for credentials, cookies, tickets, verification codes, debug-auth output, and unexpected telemetry.
