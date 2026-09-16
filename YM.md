# YM / يم — Canonical Project Handoff

Read this file first when continuing YM, then read the latest `/Projects/YM/STATE.md` from ChatGPT Library.

## Product identity
- Name: **YM / يم**.
- Separate from RUN.
- Android product centered on a TikTok-like short-form video experience.
- Maximum 3 TikTok accounts for the cloud scheduling side.
- YM must feel like a video app first, never a settings dashboard.

## Locked UX
The home screen keeps the approved current-TikTok-inspired layout:
- full-screen vertical video;
- swipe up/down;
- top feed tabs;
- right action rail;
- bottom navigation;
- center `+` button;
- no more than six bubbles above `+`.

Current bubbles: **AUTO، الدولاب، الجدولة، الحسابات، السجل، الإعدادات**. AUTO is the primary control.

## Current source — v0.10 scope guard
Current repository: `fateh1989/fateh1989`.
Current branch: `ym-v0.10-scope-guard`.
Current commit: `dd1b262f8e1970a57b2795178ef5711908a58a21`.
Package: `com.ym.lite.stable`.
Version: `0.10.0` / versionCode `10`.

v0.10 continues the functional v0.9 feed and fixes the real-device AUTO escape defect reported by the user: after TikTok had been active, the Accessibility service could retain the last TikTok package state and later dispatch a global swipe while another app such as ChatGPT was on screen.

### v0.10 TikTok scope guard
- AUTO no longer trusts a remembered/last package name.
- Every swipe checks `rootInActiveWindow` at the exact action point and requires a TikTok package.
- Every comment click, editor action, send action and automatic Back action is re-checked against the active TikTok window.
- A 250 ms scope guard removes/hides the floating overlay when TikTok is not the active window.
- Allowed automation packages are centralized in `TikTokScope`: `com.zhiliaoapp.musically` and `com.ss.android.ugc.trill` only.
- Unit coverage explicitly rejects `com.openai.chatgpt`, Android Settings and null package names.
- Leaving TikTok pauses external AUTO actions; returning to TikTok can resume while master AUTO remains enabled.

## Functional feed retained from v0.9
- Persisted local video wheel using Storage Access Framework URI grants.
- Empty feed opens the video picker.
- Selected local videos play full-screen.
- Manual swipe changes video.
- YM feed AUTO advances videos at a configurable 3–120 second interval.
- Wheel wraps in both directions.
- Caption, daily quantity, time window, horizon and wheel position persist locally.
- Long-press AUTO opens detailed settings.
- Cloud scheduling remains behind the feature bubbles.

## AUTO architecture
There are two distinct AUTO paths:
1. **YM feed AUTO** — advances through the local wheel while YM itself is open.
2. **TikTok AUTO** — AccessibilityService controls only the currently active real TikTok window, with upward swipes and optional comments from the user's pool.

The floating AUTO accessibility overlay is available over TikTok only.

## Cloud publishing architecture
`Android YM -> tiny Cloudflare Worker relay -> Post for Me Quickstart -> TikTok`

This is separate from feed/TikTok AUTO. The Worker protects provider credentials and exposes a narrow allow-list. Plan-ahead scheduling is used for future publishing when the phone is off.

## Verification
### Existing core
- Worker syntax check: passed.
- Worker tests: **8/8 passed**.
- Planner compile/smoke: passed.

### Previous device proof
- v0.6 installed/launched successfully.
- v0.8 rendered the approved TikTok-like visual direction.
- Real-device testing exposed the external AUTO escape defect: gestures could continue after leaving TikTok.

### v0.10
- GitHub Actions run: `35099490504` — **success**.
- Unit tests: **passed**.
- Scope test rejects ChatGPT/non-TikTok packages: **passed**.
- `assembleDebug`: **passed**.
- Artifact upload: **passed**.
- APK: `/Projects/YM/YM-v0.10-scope-guard.apk`.
- APK size: `5,990,976` bytes.
- APK SHA-256: `30137481be8bd35521fe1a648215460f9d51f7665c93bfe55a249d4896fdcc14`.
- ZIP/APK compressed-data integrity: passed.
- APK Signing Block present.
- Real-device v0.10 scope-guard proof: **pending**.
- Real TikTok auto-scroll/comment proof after the guard: **pending**.
- Worker connectivity and real public provider publishing: pending.

## Durable files
- `/Projects/YM/STATE.md` — latest state.
- `/Projects/YM/YM-v0.10-scope-guard.apk` — current APK.
- `/Projects/YM/YM-v0.9-functional.apk` — previous functional-feed APK.
- `/Projects/YM/YM-v0.8-parallel-debug.apk` — previous visual prototype.
- `/Projects/YM/ARCHITECTURE.md` — provider architecture decision.

## Next real-device proof
1. Install/update to v0.10 (`com.ym.lite.stable`).
2. Enable YM Accessibility service.
3. Start TikTok AUTO with comments OFF.
4. Confirm one or more automatic swipes inside TikTok.
5. While AUTO is still enabled, switch to ChatGPT or Android Settings and confirm there are **zero** YM swipes and no AUTO overlay outside TikTok.
6. Return to TikTok and confirm AUTO resumes only there.
7. Then enable one automatic comment and tune selectors if required for the installed TikTok language/version.
8. Separately verify the YM local feed/wheel persistence and cloud provider path.

## Continuation rule
When the user says `اكمل YM` or `اكمل يم`:
1. read this file;
2. read `/Projects/YM/STATE.md`;
3. inspect branch `ym-v0.10-scope-guard` and `/Projects/YM/YM-v0.10-scope-guard.apk` or newer;
4. do **not** rebuild from scratch;
5. preserve the approved video-first UI and six-bubble limit;
6. keep AUTO as the primary control;
7. never allow TikTok AUTO gestures/actions based on stale package state;
8. distinguish CI-built from real-device-tested, TikTok-AUTO-tested and provider-verified states.
