# YM / يم — Canonical Project Handoff

Read this file first when continuing YM, then read the latest `/Projects/YM/STATE.md` from ChatGPT Library.

## Product identity
- Name: **YM / يم**.
- Separate from RUN.
- Android product centered on a short-form video experience.
- Maximum 3 TikTok accounts for the cloud scheduling side.
- YM must feel like a video app first, never a RUN-like settings dashboard.

## Locked UX philosophy — v0.8
The home screen follows a TikTok-like short-video interaction model:
- full-screen vertical video;
- swipe up/down;
- top feed tabs;
- right action rail;
- bottom navigation;
- center `+` button;
- pressing `+` opens no more than six bubbles.

Current six bubbles:
1. **AUTO** — primary feature: automatic scroll + optional automatic comments.
2. **الدولاب** — local wheel/content pool.
3. **الجدولة** — cloud publishing schedule.
4. **الحسابات** — connect/select TikTok accounts for provider publishing.
5. **السجل** — publication/result history.
6. **الإعدادات** — Worker URL/pairing.

AUTO is visually and functionally the most important control.

## AUTO architecture — v0.8
YM now includes an Android `AccessibilityService` targeted at the installed TikTok app packages.

AUTO behavior:
- configurable watch interval per video;
- gesture-based automatic upward scroll;
- optional automatic comments;
- comment cadence: every N videos;
- comments come from a user-defined multi-line pool;
- the service looks for TikTok comment controls, an editable comment field, and a send/post action through the accessibility tree;
- a small floating **AUTO** accessibility overlay appears over TikTok itself and can toggle the master AUTO state.

The real TikTok UI is used for the automation path; YM does not copy TikTok source code or attempt to reimplement TikTok's backend/feed.

## Cloud publishing architecture
`Android YM -> tiny Cloudflare Worker relay -> Post for Me Quickstart -> TikTok`

This remains separate from AUTO. The Worker only protects provider credentials and exposes a narrow allow-list; it has no database, scheduler or media storage.

Phone-off cloud publishing still uses plan-ahead scheduling: once future jobs are accepted by the provider, they no longer depend on the phone.

## Current Android source
Current source branch: `fateh1989/fateh1989` -> `ym-auto-v0.8`.

v0.8 preserves the v0.7 video UI and the earlier scheduler, then adds:
- prominent AUTO button in YM's right action rail;
- AUTO bubble replacing the old proof bubble;
- AUTO configuration sheet;
- Accessibility permission launcher;
- configurable auto-scroll interval;
- optional auto-comment and comment cadence;
- user-defined comment pool;
- start/stop AUTO controls;
- automatic launch of installed TikTok;
- floating AUTO overlay on TikTok.

The one-post provider proof controls were preserved under the schedule panel.

## Verification
### Existing core
- Worker syntax check: passed.
- Worker unit tests: **8/8 passed**.
- Pure Kotlin planner compile/smoke: passed.

### v0.6
- GitHub Actions run `35046059180`: passed.
- User screenshot verified real-device installation and launch; Arabic UI rendered with no crash/white screen.

### v0.7
- Video-first UI unit tests and `assembleDebug`: passed.
- GitHub Actions run `35047608412`: success.

### v0.8 AUTO
- Android unit tests: passed.
- `assembleDebug`: passed.
- GitHub Actions run `35048318665`: **success**.
- APK: `/Projects/YM/YM-v0.8-debug.apk`.
- APK size: `5,985,868` bytes.
- SHA-256: `d2f0798809a7da9e23699a69c68e67dad4c3ac64bf43b228d5cba94cf8cbd206`.
- APK archive integrity: passed.
- Real TikTok AUTO scroll: pending real-device test.
- Real TikTok AUTO comment: pending real-device test; accessibility selectors may need tuning for the installed TikTok version/language.
- Worker connectivity and real public provider publishing: pending.

## Durable files
- `/Projects/YM/STATE.md` — latest project state.
- `/Projects/YM/YM-v0.8-debug.apk` — current APK.
- `/Projects/YM/YM-v0.7-debug.apk` — previous video-UI build.
- `/Projects/YM/YM-Lite-v0.6.zip` — older source bundle; use GitHub `ym-auto-v0.8` for current source.
- `/Projects/YM/ARCHITECTURE.md` — cloud provider architecture decision.

## Next real-device AUTO test
1. install v0.8;
2. open `+ -> AUTO`;
3. enable `YM AUTO` in Android Accessibility settings;
4. set a watch interval, initially 8 seconds;
5. test auto-scroll with auto-comment OFF;
6. add 2–3 comments to the pool;
7. enable auto-comment every 3 videos;
8. press **تشغيل AUTO وفتح TikTok**;
9. confirm the floating AUTO bubble appears over TikTok;
10. verify one real scroll and one real comment, then tune selectors if TikTok's current UI labels differ.

## Cloud publishing proof gate
Before normal 30-day/3-account provider scheduling:
1. deploy/connect Worker;
2. connect one TikTok account;
3. schedule one public/non-draft cloud post;
4. require provider success + platform URL;
5. verify the resulting TikTok post is public.

## Continuation rule
When the user says `اكمل YM` or `اكمل يم`:
1. read this file;
2. read `/Projects/YM/STATE.md`;
3. inspect branch `ym-auto-v0.8` and `/Projects/YM/YM-v0.8-debug.apk` or newer;
4. do **not** rebuild from scratch;
5. preserve video-first UX, the six-bubble limit, and AUTO as the primary control;
6. distinguish build-passed from real-device TikTok automation verified.
