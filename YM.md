# YM / يم — Canonical Project Handoff

Read this file first when continuing YM, then read the latest `/Projects/YM/STATE.md` from ChatGPT Library.

## Product identity
- Name: **YM / يم**.
- Separate from RUN.
- Small Android product for up to **3 TikTok accounts**.
- YM is now locked as a **video-first app**, not an automation dashboard.

## Locked UX philosophy — v0.7
The home screen should feel like a modern short-form video app:
- full-screen vertical video;
- swipe up/down between wheel videos;
- top feed tabs;
- right action rail with profile, like, comments, save and share;
- bottom navigation;
- a center `+` button;
- tapping `+` opens **no more than six circular bubbles** above it.

Current six bubbles:
1. **الدولاب** — choose/manage wheel videos and default caption.
2. **الجدولة** — daily quantity, time window and horizon.
3. **الحسابات** — connect/refresh/select TikTok accounts.
4. **اختبار** — one-post proof gate.
5. **السجل** — last result/history.
6. **الإعدادات** — Worker URL and pairing credential.

Automation settings must stay hidden until the user opens one of these bubbles. Do not return YM to the old RUN-like control-panel layout.

## Architecture
`Android YM -> tiny Cloudflare Worker relay -> Post for Me Quickstart -> TikTok`

The Worker exists only to keep provider credentials server-side. It has no database, scheduler or media storage.

Phone-off behavior uses **plan-ahead scheduling**: YM can create future provider jobs in advance, so accepted jobs no longer depend on the phone.

## Current Android implementation
Source branch: `fateh1989/fateh1989` -> `ym-ui-v0.7`.

v0.7 preserves the v0.6 scheduling engine and adds:
- full-screen `VideoView` feed;
- swipe up/down between locally selected wheel videos;
- tap video to pause/resume;
- right-side profile/like/comment/save/share controls;
- bottom home/discover/`+`/history/profile navigation;
- six circular feature bubbles above `+`;
- dark overlay sheets for automation controls;
- local like/save state;
- Android share intent for the current video.

The scheduling core still includes:
- maximum 3 accounts;
- Storage Access Framework multi-video picker;
- deterministic 1–30 day planner;
- daily quantity and normal/overnight windows;
- no immediate media repeat where possible;
- direct media upload to provider signed upload URL;
- cloud scheduled-post creation;
- one-post proof gate before monthly planning.

## Worker relay
Public:
- `/health`
- `/oauth-done`

Pairing-token authenticated:
- `POST /api/auth-url`
- `GET /api/accounts`
- `POST /api/media/upload-url`
- `POST /api/posts`
- `GET /api/post-results?post_id=...`

Security rules:
- `POST_FOR_ME_API_KEY` exists only as Worker Secret;
- `YM_CLIENT_TOKEN` exists only as Worker Secret;
- provider access/refresh tokens are stripped before Android receives account data;
- Android does not proxy arbitrary provider payloads;
- only HTTPS media URLs are accepted;
- Worker constructs the locked TikTok posting configuration.

## Verification state
### Existing core
- Worker syntax check: passed.
- Worker unit tests: **8/8 passed**.
- Pure Kotlin planner compile/smoke: passed.
- Planner smoke covered 60 slots, chronology, overnight windows and no immediate repeat with a multi-item pool.

### v0.6
- GitHub Actions run `35046059180`: passed.
- APK build: passed.
- User screenshot verified real-device installation and launch: Arabic UI rendered with no crash or white screen.

### v0.7 video UI
- Android unit tests: passed.
- `assembleDebug`: passed.
- GitHub Actions run `35047608412`: **success**.
- APK: `/Projects/YM/YM-v0.7-debug.apk`.
- APK size: `5,968,719` bytes.
- SHA-256: `4c97e33cfc5ad946d86387a7b364202e7b3cff382cf679931b2b303d9f481175`.
- APK archive integrity: passed.
- APK Signing Block present.
- v0.7 real-device visual/gesture QA: **pending**.
- Worker connectivity, TikTok OAuth, media upload and real public TikTok publishing: **pending**.

## Durable files
- `/Projects/YM/STATE.md` — latest project state.
- `/Projects/YM/YM-v0.7-debug.apk` — current APK.
- `/Projects/YM/YM-Lite-v0.6.zip` — previous full source bundle; current UI source is on GitHub branch `ym-ui-v0.7` until a newer Library bundle is stored.
- `/Projects/YM/ARCHITECTURE.md` — provider architecture decision.
- `/Projects/YM/YM-bootstrap.zip` — older v0.5 FastAPI fallback only; do not deploy by default.

## Mandatory one-account proof gate
Before normal 30-day/3-account use:
1. install and visually QA v0.7;
2. deploy/connect Worker;
3. connect one TikTok account;
4. select one video;
5. schedule one public/non-draft cloud post;
6. optionally power the phone off before its scheduled time;
7. require provider success and platform URL;
8. verify the actual TikTok post is public.

Only then use accounts 2/3 and the month wheel.

## Provider fallback order
1. Post for Me Quickstart.
2. TikTok API for Business / Organic Accounts API if needed.
3. Hybrid draft/confirmation if platform rules force confirmation.
4. Browser/device automation only as a last technical fallback; do not design anti-detection/evasion mechanisms.

## Continuation rule
When the user says `اكمل YM` or `اكمل يم`:
1. read this file;
2. read `/Projects/YM/STATE.md`;
3. inspect branch `ym-ui-v0.7` and `/Projects/YM/YM-v0.7-debug.apk` or any newer version;
4. do **not** rebuild from scratch;
5. preserve the video-first UX and the six-bubble `+` interaction;
6. keep YM small;
7. distinguish source-written, core-tested, APK-built, device-tested, provider-tested and real TikTok-verified states.

## Next milestones
1. Install v0.7 and visually QA the video feed, right rail, bottom bar and six bubbles on the real device.
2. Tune spacing/size/gestures from the resulting screenshot if needed.
3. Connect Post for Me Quickstart and deploy the tiny Worker.
4. Run the one-account/one-post proof.
5. Only after proof, enable normal 30-day/3-account use.
