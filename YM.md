# YM / يم — Canonical Project Handoff

This file is the durable handoff for the YM project. Future ChatGPT sessions should read this file before discussing or modifying YM so the project continues from the current state instead of restarting.

## Product identity

- Name: YM / يم.
- Separate project from RUN.
- Purpose: manage the growth/continuity of up to **3 TikTok accounts** through persistent publishing and light engagement workflows.
- This is **not** intended as an account farm. The design target is a small personal control system for three accounts.
- Core product idea: the user loads content and rules once, then YM keeps the accounts active over long periods with minimal manual attention.

## Philosophy

YM borrows RUN's useful mechanics — wheels, time windows, quantity, history and no-repeat — but not RUN's relationship philosophy.

Core loop:

`account -> wheel -> time -> quantity -> durable job -> submit -> TikTok status -> history -> next`

The phone is a **control panel**, not the execution host. YM is intended to keep running when the phone is fully powered off, so the execution engine must live on an always-on backend/server/container.

## Locked product decisions

1. Maximum **3 TikTok accounts**.
2. Each account has its own wheels, schedule, quantity, history and no-repeat state.
3. Content may come from shared or account-specific media pools, but publication history remains account-aware.
4. Publishing is managed by quantity + time window, not by forcing the user to schedule every post one by one.
5. A separate engagement wheel remains part of the concept: emoji, short words and multilingual micro-responses.
6. Engagement is intended as light presence, not repetitive spam; do not wire live commenting until a suitable supported path is confirmed.
7. Keep YM focused; do not turn it into a general-purpose phone automation framework.

## Current implementation state — v0.4

The working bootstrap is stored in ChatGPT Library at:

`/Projects/YM/`

Files:
- `YM-bootstrap.zip`
- `README.md`
- `STATE.md`

Implemented and locally verified through v0.4:
- hard limit of 3 accounts;
- persistent accounts and per-account timezones;
- persistent wheel definitions/items and media metadata/captions;
- deterministic daily time-slot planning from time window + quantity;
- daily quantity cap, minimum interval and no-repeat selection;
- durable SQLite publication jobs with restart survival;
- stale-job protection so a restarted backend does not dump an old backlog;
- retry/backoff for submission failures;
- background worker independent of the phone;
- TikTok OAuth v2 authorization flow with one-time expiring state;
- encrypted access/refresh token storage using a server-side Fernet key;
- proactive access-token refresh and refresh-token rotation;
- token revoke/disconnect path;
- official Direct Post provider implementation using creator-info + video init + status fetch;
- asynchronous publish lifecycle: receiving `publish_id` means submitted, not published;
- worker polls status and only marks a job published after `PUBLISH_COMPLETE`;
- local durable media storage for MP4/MOV/WebM;
- streamed upload with configurable byte limit and SHA-256 metadata;
- unguessable public media tokens under `/public/media/{token}` for TikTok `PULL_FROM_URL`;
- configurable `YM_PUBLIC_BASE_URL`, `YM_MEDIA_DIR`, and `YM_MAX_MEDIA_BYTES`;
- safe replacement/deletion of stored media;
- mock provider remains the safe default so development never posts accidentally;
- REST API for accounts/OAuth/media/wheels/jobs.

Verification for v0.4:
- Python compile check passed;
- **19 tests passed out of 19**;
- FastAPI smoke startup passed in mock mode (`YM Core 0.4.0`);
- media upload/public-fetch smoke test stored bytes and served the same bytes back through the public route;
- earlier TikTok-configured startup smoke passed with dummy configuration and no external request.

No real TikTok credentials have been used yet and no real TikTok post has been claimed.

## Current TikTok integration boundary

Current official endpoints used by the code:
- authorization: `https://www.tiktok.com/v2/auth/authorize/`
- token exchange/refresh: `POST https://open.tiktokapis.com/v2/oauth/token/`
- creator info: `/v2/post/publish/creator_info/query/`
- Direct Post video init: `/v2/post/publish/video/init/`
- post status: `/v2/post/publish/status/fetch/`
- required Direct Post scope: `video.publish`

For `PULL_FROM_URL`, live use requires an HTTPS media URL whose domain or URL prefix is verified for the TikTok developer app. Current official media guidance lists MP4, WebM and MOV and a maximum file size of 4 GB; YM's default byte limit matches that maximum. Re-check official TikTok documentation live before changing integration details because API requirements and policies can change.

The provider defaults to `SELF_ONLY` until another privacy level is deliberately configured. YM does not yet preflight codec, frame rate, resolution or duration locally.

## Not implemented / not live yet

- deployment to a permanent always-on HTTPS host;
- TikTok URL/domain verification against the deployed media origin;
- first real TikTok developer app/account authorization;
- first controlled real Direct Post and end-to-end final-status verification;
- local media preflight for codec/resolution/frame-rate/duration;
- three-account dashboard/control client;
- real engagement/comment execution;
- Android control app.

## Continuation order

When continuing YM:

1. Read this file first.
2. Read `/Projects/YM/STATE.md` from ChatGPT Library for the latest implementation delta.
3. Inspect the latest `YM-bootstrap.zip` code before changing it.
4. Do not rebuild from scratch.
5. Keep changes small and verifiable.
6. Test after each meaningful change.
7. Distinguish clearly between code written, locally tested, deployed, and real TikTok-verified behavior.
8. Update this handoff and `/Projects/YM/STATE.md` after every durable milestone because development may span multiple days/conversations.

## Next milestones

1. Choose/deploy a real always-on HTTPS host with persistent DB/media storage and secret management.
2. Point `YM_PUBLIC_BASE_URL` at that origin and verify the domain/URL prefix in TikTok for Developers.
3. Configure a TikTok developer app and connect the first real account through OAuth.
4. Perform one controlled real Direct Post and verify final status end to end.
5. Add local media preflight checks.
6. Extend to accounts 2 and 3 only after account 1 is stable.
7. Build the three-account dashboard/control client.
8. Add engagement-wheel execution only after confirming a suitable supported path.

## UX direction

Keep the visible model simple:

`3 accounts -> wheels -> time -> quantity -> history`

The user should not need to manage hundreds of individual scheduled tasks. A wheel can contain a large content pool; the user defines a quantity, time window, spacing and no-repeat rules, and the backend executes the plan.

## Relationship to RUN

RUN and YM share scheduling/wheel ideas but remain separate projects.

- RUN: human communication continuity on Android/WhatsApp.
- YM: persistent social publishing/account continuity on an always-on backend.
