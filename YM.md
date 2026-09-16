# YM / يم — Canonical Project Handoff

Read this file first when continuing YM, then read the latest `/Projects/YM/STATE.md` from ChatGPT Library.

## Product identity
- Name: **YM / يم**.
- Separate from RUN.
- Android product centered on a TikTok-like short-form video experience.
- Maximum 3 TikTok accounts for the cloud scheduling side.
- YM must feel like a video app first, never a settings dashboard.

## Locked UX
The home screen keeps the current approved layout:
- full-screen vertical video;
- swipe up/down;
- top feed tabs;
- right action rail;
- bottom navigation;
- center `+` button;
- no more than six bubbles above `+`.

Current bubbles: **AUTO، الدولاب، الجدولة، الحسابات، السجل، الإعدادات**. AUTO is the primary control.

## Current source — v0.9
Current branch: `fateh1989/fateh1989` -> `ym-v0.9-functional`.
Current commit: `68bae4631ecf82fac9a8b90157c28f505646e621`.

v0.9 keeps the v0.8 visual design and makes the feed functional:
- the wheel/video selection persists across restarts using Storage Access Framework URI grants;
- tapping an empty feed opens the video picker directly;
- selected local videos play in the full-screen feed;
- manual swipe up/down changes video;
- a single tap on the side AUTO button starts/stops automatic feed advance inside YM;
- AUTO interval is configurable from 3–120 seconds;
- the wheel wraps last -> first and first -> last;
- caption, daily quantity, time window, horizon and wheel position persist locally;
- long-press AUTO opens detailed settings;
- existing TikTok Accessibility automation remains available for the real TikTok app: auto-scroll plus optional comments from a user-defined pool;
- cloud scheduling/Worker/Post for Me functionality remains behind the feature bubbles.

## Stable test signing
v0.9 starts the stable test package line with applicationId `com.ym.lite.stable` and a fixed **debug/test-only** signing key. Future YM test builds should update v0.9 instead of failing because a new GitHub runner generated a different debug certificate. This is not a production release credential.

## AUTO architecture
There are now two related AUTO paths:
1. **YM feed AUTO** — advances through the local wheel while the YM video screen is open.
2. **TikTok AUTO** — Android AccessibilityService controls the installed TikTok app, performing upward swipe gestures and optional comments every N videos using the user's comment pool.

The floating AUTO accessibility overlay remains available over TikTok and can toggle TikTok AUTO.

## Cloud publishing architecture
`Android YM -> tiny Cloudflare Worker relay -> Post for Me Quickstart -> TikTok`

This is separate from feed/TikTok AUTO. The Worker only protects provider credentials and exposes a narrow allow-list. Plan-ahead scheduling is still used for phone-off future publishing.

## Verification
### Existing core
- Worker syntax check: passed.
- Worker tests: **8/8 passed**.
- Planner compile/smoke: passed.

### Previous device proof
- v0.6 installed/launched on the real Android device.
- v0.8 parallel package installed and rendered the approved TikTok-like UI. User confirmed the visual direction was correct, but the feed was static.

### v0.9
- Added FeedWheel tests for forward/back wrapping, invalid-index clamping and AUTO interval bounds.
- Android unit tests: **passed**.
- `assembleDebug`: **passed**.
- Artifact upload: **passed**.
- GitHub Actions run: `35049609453` — **success**.
- Current APK: `/Projects/YM/YM-v0.9-functional.apk`.
- APK size: `5,990,336` bytes.
- SHA-256: `857c400de8129e6fdac29c7742637eb34b66c5e5ac92106a1a688118b2a57af5`.
- APK archive integrity: passed.
- APK Signing Block present.
- Real-device v0.9 feed/AUTO test: pending because the connected device is currently offline.
- Real TikTok auto-scroll/comment proof: pending.
- Worker connectivity and real public provider publishing: pending.

## Durable files
- `/Projects/YM/STATE.md` — latest state.
- `/Projects/YM/YM-v0.9-functional.apk` — current APK.
- `/Projects/YM/YM-v0.8-parallel-debug.apk` — previous installed visual prototype.
- `/Projects/YM/ARCHITECTURE.md` — provider architecture decision.

## Next real-device proof
1. install v0.9 (`com.ym.lite.stable`);
2. tap the empty feed and choose at least two videos;
3. verify playback and manual swipe;
4. tap the side AUTO bubble and confirm automatic advance after the chosen interval;
5. restart YM and confirm the wheel persists;
6. enable YM Accessibility service;
7. start TikTok AUTO and verify one automatic swipe on real TikTok;
8. then enable comments and tune selectors for the installed TikTok language/version if needed;
9. afterward complete the Worker/Post for Me one-public-post proof.

## Continuation rule
When the user says `اكمل YM` or `اكمل يم`:
1. read this file;
2. read `/Projects/YM/STATE.md`;
3. inspect branch `ym-v0.9-functional` and `/Projects/YM/YM-v0.9-functional.apk` or newer;
4. do **not** rebuild from scratch;
5. preserve the approved video-first UI and six-bubble limit;
6. keep AUTO as the primary control;
7. distinguish CI-built from real-device-tested, TikTok-AUTO-tested and provider-verified states.
