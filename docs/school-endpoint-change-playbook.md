# School Endpoint Change Playbook

Use this playbook when an XJTU school-system endpoint, HTML page, JSON shape, login handoff, or paging/action contract changes. The goal is to turn endpoint churn into a fixture-backed fix without leaking account data or hammering school systems.

This playbook applies to shared KMP repositories/parsers, iOS real-validation paths, and Android parity investigations. It does not replace `docs/ios-first-release-acceptance.md` for release readiness or `docs/ios-release-owner-evidence-record.md` for private owner evidence.

## Stop Conditions

Stop live retrying and switch to fixture work when any of these appear:

- CAS, MFA, Safety Verify, account choice, or per-site login returns an unexpected page/state;
- a parser reports a missing object, missing rows, missing total, malformed HTML, non-JSON payload, or service error page;
- paging/action endpoints return inconsistent totals, repeated pages, unexpected redirects, or user-visible action failure;
- a feature works in direct mode but not WebVPN, or the reverse;
- public endpoints such as notices or empty-room CDN change structure;
- Release/TestFlight behavior differs from Debug validation.

Do not work around the issue by adding blind retries, longer auto-loops, captcha automation, unbounded pagination, or broader stale-cache persistence.

## Sanitized Evidence

Record only what is needed to reproduce the shape change:

- app version/build, commit, feature, access mode, device/simulator, OS, and timestamp;
- endpoint host and path with query values redacted;
- HTTP status, final host/path, content type, redirect count, and whether the body shape was HTML, JSON, empty, or binary;
- sanitized parser error message and user-visible copy;
- minimal redacted body fragment only after removing names, accounts, grades, schedules, card records, cookies, tickets, tokens, captcha/MFA values, and personal identifiers.

Never commit raw captures, HAR files, preference dumps, cookies, tickets, tokens, account names, verification codes, or screenshots with real personal data.

## Triage State

Use local docs status labels from `docs/agents/triage-labels.md` until a destination repository exists:

| State | Meaning |
|---|---|
| `needs-info` | A sanitized capture, affected account type, access mode, or owner/device trace is still missing. |
| `ready-for-human` | The next step needs a live school account, physical device, TestFlight, or App Store/GitHub permission. |
| `ready-for-agent` | The issue has sanitized evidence, a clear fixture/test target, and an acceptance check. |
| `wontfix` | The behavior is Android-only or intentionally outside the iOS slice. |

## Fixture Refresh Workflow

1. Identify the smallest failing owner: parser, session authenticator, repository, feature facade, or Swift bridge.
2. Create a minimal fixture from sanitized evidence. Preserve only structural fields, selectors, status codes, paging totals, and action result shapes.
3. Replace personal values with stable placeholders such as `STUDENT_NAME`, `COURSE_A`, `CARD_TXN_1`, `SEAT_ID_1`, or `TOKEN_REDACTED`.
4. Add or update the narrowest public-interface test under `shared/src/commonTest/kotlin` first.
5. Run the failing test, then implement the smallest parser/repository/session change that makes it pass.
6. Run `./gradlew :shared:check` before handing the fix to iOS validation.
7. Update user-facing copy only when the evidence proves the user needs different action.

Prefer fixture tests over implementation-coupled assertions. A fixture should prove the service contract the app depends on, not the exact private helper function used today.

## Copy Rules

Endpoint-change copy must be specific without overclaiming:

- Use "学校系统返回了新的页面或数据格式，请稍后重试或提交脱敏反馈" for unknown shape changes.
- Use auth-specific copy only when CAS/session evidence proves the user needs to reauthorize.
- Use availability copy only when the service returns an explicit maintenance, empty, or closed-window state.
- Do not imply wrong credentials unless the auth flow returned an explicit invalid-credential result.
- Do not display raw server messages that may contain account data or internal details.

## Live Validation Cadence

Keep live validation sparse and owner-driven:

| Scope | Cadence |
|---|---|
| Parser/session fixture tests | Every endpoint-change code patch. |
| Public no-account data | After fixture tests pass, with one manual refresh per affected endpoint. |
| Owner account data | Only after a fixture-backed patch exists or when collecting the initial sanitized shape. |
| `iosApp/scripts/run-real-feature-validation.sh` | Use for library seats, coupons, and school-course search; save only `IOS_REAL_FEATURE_RESULTS_PATH` sanitized lines. |
| Site verification | Use visible user-driven verification by default; Debug auto-verification is opt-in and rate-limited. |
| Release/TestFlight | Run only after local shared/iOS gates pass and the owner has signing/TestFlight access. |

If a school system is down or unstable, record `ready-for-human` or `needs-info` instead of increasing retry volume.

## Fix Acceptance Checklist

Before closing an endpoint-change issue:

- sanitized evidence is stored privately or summarized without secrets;
- the failing shape is covered by a fixture or public-interface test;
- direct/WebVPN impact is considered for authenticated school systems;
- pagination/action bounds remain explicit;
- user-facing copy is specific and does not expose raw server data;
- stale-cache behavior did not expand beyond schedule, public notices, and empty rooms unless a separate privacy review approved it;
- `./gradlew :shared:check` passes;
- iOS real validation or TestFlight evidence is recorded only when the changed surface requires it.

## Local Issue Template

```markdown
# Endpoint Change: <feature> <short symptom>

- State: needs-info | ready-for-human | ready-for-agent | wontfix
- Feature:
- Access mode: direct | WebVPN | public
- App version/build:
- Commit:
- Sanitized evidence reference:
- Affected owner: parser | session authenticator | repository | Swift bridge | UI copy
- Expected behavior:
- Actual behavior:
- Fixture/test target:
- Acceptance:
- Human-only gate:
```

Keep the first code patch boring: fixture, fail-closed parser/session change, copy update when needed, and targeted validation. Broader feature redesign belongs in a separate post-release slice.
