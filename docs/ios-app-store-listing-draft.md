# iOS App Store Listing Draft

This is a release-owner review draft for the first iOS release. It is not an App Store Connect submission record. Keep the final App Store Connect entry aligned with `docs/ios-first-release-acceptance.md`, `docs/ios-app-store-privacy-review.md`, and the shipped build.

## Product Identity

- App name: `岱宗盒子`
- Candidate subtitle: `西安交通大学校园工具箱`
- Category candidate: Education or Utilities; release owner should choose based on the final App Store Connect category taxonomy.
- Bundle identifier: confirm from the signed archive before submission.
- First-release platform note: iPhone-only until iPad layouts have separate acceptance evidence.
- Official relationship disclosure: this is an independent, unofficial project and is not affiliated with Xi'an Jiaotong University.

## Candidate Description

岱宗盒子是一款面向西安交通大学学生的校园工具应用。iOS 首发版本直接连接学校系统，帮助用户在一个原生界面中查看课表、考试教材、成绩、校园卡、校园通知、空教室、图书馆座位、加餐券和全校课程搜索等常用信息。

应用没有开发者自建后台，首发版本不包含广告、追踪、第三方分析 SDK 或远程崩溃上报 SDK。登录凭据由用户在设备上输入，并按系统能力保存在 iOS Keychain 中。退出登录会清除已保存凭据和会话状态。

本应用为非官方独立开发项目，与西安交通大学官方无关。使用前请确认你信任当前安装来源，并只在自己的设备上输入统一身份认证账号。

## Candidate Keywords

Use Chinese-first keywords and keep the final list within App Store Connect's current length limit:

`西安交通大学,交大,课表,成绩,校园卡,空教室,图书馆,加餐券,教务,校园`

## Candidate Promotional Text

首发 iOS 版本聚焦高频校园查询：课表、成绩、校园卡、通知、空教室、图书馆座位、加餐券和全校课程搜索。

## Screenshot Plan

Screenshots must use preview data or fully redacted test data. Do not use a real student's name, school account, phone number, grades, campus-card turnover, tickets, cookies, verification codes, or personally identifiable class schedule.

Recommended first-release screenshots:

- Home/dashboard after preview auto-login.
- Schedule week view with preview courses.
- Exam schedule with preview data.
- Textbook information with preview data.
- Grades list with preview data only.
- Campus card screen with preview balance/turnover or redacted values.
- Library seats, coupons, and empty-room screens with preview-safe data.
- School-course search with preview-safe data.

Before upload, verify each screenshot against:

- no personal data;
- no debug/validation labels unless the release owner intentionally explains them in review notes;
- no browser-auth promotion while official browser auth remains blocked;
- visual consistency with the signed Release or TestFlight build.

Generate the privacy-safe candidate set with:

```bash
iosApp/scripts/capture-app-store-screenshots.sh
```

The script creates a disposable `iPhone 16 Pro Max` simulator, requests a Simplified-Chinese app launch language/locale, installs only Debug preview dependencies, captures ten candidate screens, and validates every PNG against Apple's accepted 6.9-inch portrait dimensions. Output stays under ignored `build/app-store-screenshots/` by default. The 2026-06-15 local candidate set passed a manual review for loading states, clipping, real-account data, and Chinese system-control presentation at `1320x2868`; repeat the review and capture against the final signed/TestFlight UI if presentation changes.

## Privacy And Support URLs

Local source pages are prepared and browser-verified:

- Privacy policy source: `website/privacy.md`
- Support source: `website/support.md`
- Expected GitHub Pages routes with the current VitePress base and default non-clean URLs: `/xjtu-toolbox-android/privacy.html` and `/xjtu-toolbox-android/support.html`

The release owner must record the final public absolute URLs after deployment and use those exact URLs in App Store Connect.

## Review Notes Draft

Use only non-sensitive review notes. Do not include a real account, password, verification code, ticket, cookie, token, HAR, raw school-system response, or personal screenshot.

Suggested starting point:

> 岱宗盒子 is an independent, unofficial campus utility for Xi'an Jiaotong University students. The first iOS release directly connects to school systems selected by the user and does not use a developer-operated backend, advertising, analytics, tracking, or remote crash-reporting SDK. Some authenticated workflows require a valid XJTU account; the reviewer can inspect public and preview-safe surfaces without credentials. Production official browser login is intentionally hidden because the school CAS service has not accepted an app callback URL for this app yet.

If App Review requires authenticated inspection, the release owner must decide whether and how to provide a temporary review account through App Store Connect's private credential fields. Do not put credentials in repository files, public issue trackers, screenshots, or release notes.

## Release Owner Checklist

- Confirm final app name, subtitle, category, age rating, support URL, and privacy policy URL in App Store Connect.
- Confirm App Store privacy answers from `docs/ios-app-store-privacy-review.md`, especially whether XJTU school-system traffic is treated as real-time user-request servicing or third-party recipient collection.
- Produce final screenshots from preview-safe or redacted data.
- Confirm signed/TestFlight build behavior before uploading screenshots or review notes.
- Replace AppIcon only if the owner supplies a final brand asset; the current archive validator already proves the generated AppIcon is packaged.
