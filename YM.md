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

## Current architecture — v0.6 Lite pivot

Chosen first implementation:

`Android YM -> tiny Cloudflare Worker relay -> Post for Me Quickstart -> TikTok`

Why this is now preferred:

1. Post for Me currently provides account OAuth, media staging, scheduling, token refresh and remote job execution.
2. Their Quickstart project can use Post for Me's platform credentials, avoiding a TikTok developer-app approval path for the first proof.
3. The phone can be powered off **after YM has created future scheduled posts in the provider cloud**.
4. A pure Android-to-provider design was rejected for security: Post for Me documents its project API key as an administrative credential that must not be exposed in frontend/client code.
5. Therefore the only backend in v1 is a tiny Cloudflare Worker with no database, no scheduler and no media storage. It stores the provider key as a Worker Secret and proxies a small allow-list of API calls.

Cloudflare Workers currently has a Free plan and encrypted Worker Secrets, which fits this tiny relay role. Do not replace this with Hetzner/Oracle/VPS infrastructure unless the lightweight path fails.

## Phone-off model

YM does **plan-ahead scheduling**, not Android wakeups.

Example: 2 posts/day for 30 days -> Android creates 60 future scheduled cloud posts in one planning session. Each scheduled item is handed to Post for Me. After that upload/scheduling session completes, those jobs are no longer dependent on the phone.

This is the key simplification that makes YM small.

## Implemented source — v0.6

Canonical source bundle in ChatGPT Library:

`/Projects/YM/YM-Lite-v0.6.zip`

Also read:
- `/Projects/YM/README.md`
- `/Projects/YM/STATE.md`
- `/Projects/YM/ARCHITECTURE.md`

Android source currently includes:
- Kotlin/XML single-screen app; no Compose, Room or Retrofit;
- Android Keystore-encrypted pairing-token storage;
- Worker URL + pairing setup;
- TikTok account-connect flow via relay;
- account refresh with product cap of 3 accounts;
- Storage Access Framework multi-video picker;
- deterministic wheel planner with normal/overnight windows, daily quantity, jitter and no immediate repeat while cycling the shuffled media pool;
- planning horizon capped at 30 days in v1;
- direct PUT from Android to Post for Me signed temporary-media URLs;
- cloud scheduled-post creation using UTC ISO timestamps.

Cloudflare Worker source currently includes:
- public `/health`;
- `/oauth-done` return page;
- authenticated `/api/auth-url`;
- authenticated `/api/accounts`;
- authenticated `/api/media/upload-url`;
- authenticated `/api/posts` and post lookup;
- project-level 1–3 account validation;
- provider API key only in `POST_FOR_ME_API_KEY` Worker Secret;
- Android pairing credential only in `YM_CLIENT_TOKEN` Worker Secret.

## Verification — v0.6

- Worker unit tests: **6/6 passed** using mocked Post for Me upstream calls.
- Worker JavaScript syntax check passed.
- Planner core compiled with `kotlinc`.
- Planner smoke verified deterministic 60-slot month planning, chronological order, overnight window support and no immediate media repeat.
- Android APK itself has **not** yet been compiled in the current environment because Android SDK/Gradle are not installed there.
- No real Post for Me key, TikTok account or public post has been used yet. Do not claim live posting until tested.

## Provider facts re-checked 2026-09-16

Post for Me currently documents:
- TikTok scheduled video publishing;
- standard TikTok and TikTok Business connections;
- Quickstart projects using their credentials with no developer approval required to start;
- `POST /v1/social-accounts/auth-url` for account connection;
- `GET /v1/social-accounts` for connected accounts;
- `POST /v1/media/create-upload-url` for temporary media staging;
- `POST /v1/social-posts` with `scheduled_at` for future publishing;
- temporary media is retained when attached to a scheduled post and cleaned after the associated publish/delete lifecycle;
- project API keys are administrative credentials and should stay server-side.

Re-check these details live before changing the integration because provider behavior can change.

## Historical v0.5 server

The earlier FastAPI/Docker/TikTok-direct server remains archived in `/Projects/YM/YM-bootstrap.zip` as a fallback and source of tested scheduling/media ideas. It is **not** the preferred v1 architecture anymore.

Do not deploy a VPS merely because that code exists.

## TikTok/provider fallback order

1. **Post for Me Quickstart** — first proof because it keeps YM small.
2. **TikTok API for Business / Organic Accounts API** if Quickstart cannot deliver the required direct/public unattended workflow.
3. Hybrid draft/confirmation if platform policy forces user confirmation.
4. Browser/device automation only as a last technical fallback; no anti-detection/evasion design.

The outbound engagement wheel that comments on other creators' videos remains unimplemented because no suitable supported general API path was established. Do not make this the blocker for the publishing MVP.

## Continuation order

When the user says `اكمل YM` or `اكمل يم`:

1. Read this file.
2. Read `/Projects/YM/STATE.md`.
3. Inspect the latest `/Projects/YM/YM-Lite-v0.6.zip` or newer source bundle.
4. Do not rebuild from scratch.
5. Keep YM small; resist adding infrastructure that is not needed.
6. Test every meaningful change and distinguish source-written, core-tested, APK-built, provider-tested and real TikTok-verified states.
7. Update this handoff and `STATE.md` after every durable milestone.

## Next milestones

1. Compile the first Android APK in a real Android build environment.
2. Create/connect a Post for Me Quickstart project.
3. Deploy the tiny Cloudflare Worker and set `POST_FOR_ME_API_KEY` + `YM_CLIENT_TOKEN` as secrets.
4. Connect **one** TikTok account.
5. Schedule **one** controlled post and verify it publishes successfully after the phone is powered off.
6. Only after that proof, enable the 30-day wheel flow and accounts 2 and 3.
7. Add compact history/status and analytics later; do not bloat v1.
