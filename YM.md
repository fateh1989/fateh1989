# YM / يم — Canonical Project Handoff

This is the durable cross-session handoff for YM. Read this file before changing the project, then read the latest `/Projects/YM/STATE.md` from ChatGPT Library.

## Product identity

- Name: **YM / يم**.
- Separate from RUN.
- Goal: a **small Android app** that helps maintain up to **3 TikTok accounts** with wheels, time windows, daily quantity, history and no-repeat behavior.
- It is not an account farm and must not grow into a large automation platform.
- The user explicitly prefers a compact Android product over owning a VPS/backend stack.

## Locked UX philosophy

Visible model:

`3 accounts -> wheel/content pool -> time window -> daily quantity -> cloud schedule -> history`

The user should not schedule every post manually. YM builds a future plan from a pool of videos and timing rules.

## Current architecture — v0.6 Lite

Chosen first implementation:

`Android YM -> tiny Cloudflare Worker relay -> Post for Me Quickstart -> TikTok`

Why:
1. Post for Me currently handles account OAuth, temporary media staging, scheduling, token refresh and remote job execution.
2. Their Quickstart route can use provider credentials for the first proof instead of requiring YM to own a TikTok developer app immediately.
3. The phone can be powered off after YM has handed future scheduled jobs to the provider cloud.
4. The provider project API key is administrative and must not be embedded in an APK, so a tiny relay is required.
5. The relay has no database, scheduler or media storage; it only holds secrets and exposes a narrow allow-list of operations.

Do not replace this with Hetzner/Oracle/VPS infrastructure unless the lightweight route fails.

## Phone-off model

YM uses **plan-ahead scheduling**, not Android wakeups.

Example: 2 posts/day for 30 days -> Android creates 60 future scheduled cloud posts in one planning session. After all jobs are accepted, those jobs no longer depend on the phone.

## Current source

Canonical bundle in ChatGPT Library:

`/Projects/YM/YM-Lite-v0.6.zip`

Also read:
- `/Projects/YM/README.md`
- `/Projects/YM/STATE.md`
- `/Projects/YM/ARCHITECTURE.md`

The old v0.5 FastAPI/Docker backend remains archived as `/Projects/YM/YM-bootstrap.zip` only as a fallback/reference. Do not deploy it by default.

## Android v0.6 implementation

- Kotlin + XML single-screen app; no Compose, Room or Retrofit.
- Android Keystore-encrypted pairing token.
- Worker URL + pairing setup.
- TikTok account connect flow and connected-account refresh.
- hard product cap of 3 accounts.
- Storage Access Framework multi-video picker.
- deterministic wheel planner with normal/overnight windows, daily quantity, jitter and no immediate repeat with multi-item pools.
- planning horizon capped at 30 days.
- direct PUT from Android to provider short-lived signed media upload URLs; large media never traverses the Worker.
- cloud scheduled-post creation using UTC ISO timestamps.
- dedicated **one-post proof** scheduled roughly 10 minutes ahead.
- safe result lookup for the most recent proof.
- monthly plan stays locked until the provider reports a successful proof result.

## Tiny Worker v0.6 implementation

Public:
- `/health`
- `/oauth-done`

Pairing-token authenticated:
- `POST /api/auth-url`
- `GET /api/accounts`
- `POST /api/media/upload-url`
- `POST /api/posts`
- `GET /api/post-results?post_id=...`

Security design:
- provider key exists only as `POST_FOR_ME_API_KEY` Worker Secret;
- Android pairing credential exists only as `YM_CLIENT_TOKEN` Worker Secret;
- provider access/refresh tokens are stripped before account data reaches Android;
- post creation does **not** proxy arbitrary provider JSON anymore;
- Android supplies only account ID, caption, media URL and schedule time;
- Worker constructs the locked TikTok configuration: `public`, `is_draft=false`, comments enabled;
- only HTTPS media URLs are accepted;
- schedule is restricted to approximately the next 31 days;
- publication-result response is whitelisted to success, public URL/platform ID and a short error summary.

## Verification — current v0.6

- Worker JavaScript syntax check passed.
- Worker unit tests: **8/8 passed** using mocked provider calls.
- `PlanEngine.kt` compiled with `kotlinc`.
- Planner smoke: **OK (60 slots)**, deterministic, chronological, overnight-window capable and no immediate repeat with a multi-item pool.
- Android APK itself has **not** yet been compiled in the current container because Android SDK/Gradle are unavailable there.
- A Remote Desktop Commander build-environment check was attempted, but the device was offline at that moment; no device build was claimed.
- No real Post for Me key, Cloudflare deployment, TikTok account or public post has been used yet.

## Provider facts re-checked 2026-09-16

Current Post for Me SDK/docs expose:
- `POST /v1/social-accounts/auth-url` for account connection;
- `GET /v1/social-accounts` for connected accounts;
- `POST /v1/media/create-upload-url` returning `upload_url` + `media_url`;
- `POST /v1/social-posts` with `scheduled_at`;
- `GET /v1/social-post-results` with success/failure and platform URL;
- TikTok config fields for privacy, draft/direct behavior, comments/duet/stitch and related flags;
- project API keys are administrative credentials and belong server-side.

Re-check these live before changing integration because provider behavior can change.

## Mandatory one-account proof gate

Before a month-long plan or accounts 2/3 are used:
1. connect one TikTok account;
2. select one video;
3. schedule one public/non-draft cloud post;
4. power the phone off before the scheduled time if desired;
5. return later and query the provider result;
6. require provider success and a platform URL;
7. verify the resulting TikTok post is actually public.

Only then use the 30-day wheel.

## Provider fallback order

1. **Post for Me Quickstart** — first proof because it keeps YM small.
2. **TikTok API for Business / Organic Accounts API** if Quickstart cannot deliver the required public unattended workflow.
3. Hybrid draft/confirmation if platform rules force user confirmation.
4. Browser/device automation only as a last technical fallback; do not design anti-detection/evasion mechanisms.

Outbound automated comments on other creators' videos remain unimplemented; no suitable supported general API path was established. Do not let this block the publishing MVP.

## Continuation order

When the user says `اكمل YM` or `اكمل يم`:
1. Read this file.
2. Read `/Projects/YM/STATE.md`.
3. Inspect the latest `/Projects/YM/YM-Lite-v0.6.zip` or newer bundle.
4. Do not rebuild from scratch.
5. Keep YM small and resist unnecessary infrastructure.
6. Test every meaningful change and distinguish source-written, core-tested, APK-built, provider-tested and real TikTok-verified states.
7. Update this handoff and `STATE.md` after every durable milestone.

## Next milestones

1. Compile the first Android APK in an Android-capable build environment.
2. Create/connect a Post for Me Quickstart project.
3. Deploy the tiny Cloudflare Worker and set `POST_FOR_ME_API_KEY` + `YM_CLIENT_TOKEN` as encrypted secrets.
4. Configure the Quickstart redirect URL to Worker `/oauth-done`.
5. Connect account 1.
6. Run the one-post proof with the phone off.
7. Verify provider result and actual public TikTok URL.
8. Only after that proof, use the 30-day wheel and accounts 2/3.

## Known issue for next YM update — confirmed on device (v0.42)

- First automatic comment can work.
- After YM swipes to the next TikTok video, it opens the comments panel but may fail to place text in the editor, so nothing is sent.
- Treat opening the comments panel as only an intermediate state, not success.
- The next update must re-discover the current comment editor after every video change, wait for it to become editable/focused, insert text, verify the field actually contains text, then discover and press the current send control.
- Do not reuse stale AccessibilityNodeInfo/editor references from the previous video.
- Add a bounded retry/state reset when the editor is not ready, while keeping all actions scoped to the active official TikTok window.
