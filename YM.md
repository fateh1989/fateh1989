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

`account -> wheel -> time -> quantity -> durable job -> submit -> provider status -> history -> next`

The phone is a **control panel**, not the execution host. YM is intended to keep running when the phone is fully powered off, so the execution engine must live on an always-on backend/server/container.

## Locked product decisions

1. Maximum **3 TikTok accounts**.
2. Each account has its own wheels, schedule, quantity, history and no-repeat state.
3. Content may come from shared or account-specific media pools, but publication history remains account-aware.
4. Publishing is managed by quantity + time window, not by forcing the user to schedule every post one by one.
5. A separate engagement wheel remains part of the concept: emoji, short words and multilingual micro-responses.
6. Engagement is intended as light presence, not repetitive spam; do not wire live commenting until a suitable supported path is confirmed.
7. Keep YM focused; do not turn it into a general-purpose phone automation framework.

## Current implementation state — v0.5

The working bootstrap is stored in ChatGPT Library at:

`/Projects/YM/`

Files:
- `YM-bootstrap.zip`
- `README.md`
- `STATE.md`
- `DEPLOY.md`

Implemented and locally verified through v0.5:
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
- Direct Post provider implementation using creator-info + video init + status fetch;
- asynchronous publish lifecycle: receiving `publish_id` means submitted, not published;
- worker polls status and only marks a job published after `PUBLISH_COMPLETE`;
- local durable media storage for MP4/MOV/WebM;
- streamed upload with configurable byte limit and SHA-256 metadata;
- unguessable public media tokens under `/public/media/{token}` for provider pull URLs;
- configurable `YM_PUBLIC_BASE_URL`, `YM_MEDIA_DIR`, and `YM_MAX_MEDIA_BYTES`;
- safe replacement/deletion of stored media;
- ffprobe-based local video preflight;
- codec, width, height, FPS and duration persisted per stored video;
- preflight status/error persisted in SQLite;
- stored local media is blocked from worker submission until preflight passes;
- automatic preflight on `/media/upload` and manual `/media/{id}/preflight`;
- `/health` liveness plus `/ready` database/media/config readiness endpoint;
- production Docker image includes ffprobe, runs non-root and uses `/data` for persistent state;
- `docker-compose.yml`, `.env.example`, and `DEPLOY.md` added;
- mock provider remains the safe default so development never posts accidentally;
- REST API for accounts/OAuth/media/wheels/jobs.

Verification for v0.5:
- Python compile check passed;
- **25 tests passed out of 25**;
- FastAPI smoke startup passed in mock mode (`YM Core 0.5.0`);
- `/ready` returned ready with writable database/media paths;
- a real ffmpeg-generated H.264 MP4 (720x1280, 30 FPS, 2 seconds) passed local preflight;
- API upload smoke persisted that video, computed SHA-256, extracted H.264/720x1280/30 FPS/2s metadata, and returned `preflight_status=passed`.

No real TikTok credentials have been used yet and no real public TikTok post has been claimed.

## Current TikTok integration boundary

Live-rechecked on 2026-09-16 against official TikTok developer docs:
- Content Posting formats: MP4, WebM, MOV;
- codecs: H.264, H.265, VP8, VP9;
- frame rate: 23–60 FPS;
- each picture dimension: 360–4096 pixels;
- developer-send duration ceiling: 10 minutes, while creator-specific maximum must also be honored;
- maximum size: 4 GB;
- `PULL_FROM_URL` requires HTTPS and a verified domain or URL prefix;
- public Direct Post requires an approved `video.publish` path; unaudited clients are restricted to private visibility.

Important: current TikTok Content Sharing Guidelines also impose creator-facing UX/consent requirements and describe internal/private account-management upload utilities as an unacceptable intended use for Direct Post API clients. This may conflict with YM's fully unattended public-posting goal even though the HTTP integration is technically implementable. Do not hide this constraint or claim the official provider can deliver unattended public growth until the developer-app/audit path is proven acceptable.

The provider boundary is deliberate: if the supported execution route changes, preserve the scheduler/wheels/history and replace only the provider layer.

## Provider route research checkpoint — 2026-09-16

Routes identified:

1. **TikTok for Developers — Content Posting Direct Post**: works with cloud/web apps and can post directly, but public visibility requires audit/approval and TikTok requires creator-facing privacy/settings UI and explicit consent. Strong fit for normal creator-facing schedulers; uncertain fit for YM's fully unattended private three-account manager.
2. **TikTok for Developers — Upload/Draft**: uploads media as a draft, then the user must finish the post in TikTok. Safe and official but fails the phone-off/unattended requirement.
3. **TikTok API for Business — Organic/Accounts API**: has an official endpoint to publish a public video to an owned TikTok account, plus publish status, account analytics, hashtag/location helpers, and management of comments on videos owned by the account. Since March 20, 2026 developers requesting TikTok Accounts scopes must complete an Accounts API Access Application. This route requires a TikTok For Business developer setup and may imply Business Account / Business Center requirements.
4. **TikTok Business Center / Web Business Suite native scheduler**: official and low-risk but insufficient by itself for month-long autonomous scheduling.
5. **TikTok Marketing Partner / third-party scheduler**: proven auto-publishing exists through approved partners. Fastest product route but adds subscription/vendor dependence.
6. **Unified posting API provider**: can absorb OAuth, platform changes, media processing and scheduling, and is attractive for a small Android v1.
7. **Browser automation**: technically workable but unofficial/brittle; not preferred.
8. **Android cloud VM/emulator or spare device**: workable but operationally heavy; not preferred.
9. **Share Kit / intents**: requires user interaction and does not satisfy the phone-off goal.
10. **Hybrid planner + human confirmation**: low platform risk but gives up full autonomy.

Important account-type tradeoff: TikTok Business Accounts currently see only the Commercial Music Library when adding sound, whereas Personal Accounts can see the broader sound library. Do not convert the three target accounts to Business Account until the user confirms that losing general music access is acceptable for their content strategy.

Engagement boundary: the Accounts API can create/reply/like/hide/delete comments associated with organic videos owned by the authorized account. It does **not** establish a general supported path for leaving arbitrary comments/emoji on other creators' videos. Keep the original outbound engagement wheel unimplemented until a supported route is found.

## Small Android v1 decision — 2026-09-16

The user explicitly wants YM to remain a **small Android application**, not a large infrastructure/platform project.

Chosen v1 direction:

`small Android app -> Post for Me -> TikTok`

Why this is the preferred first implementation:
- Post for Me currently exposes TikTok scheduled posting, media processing, multi-account support, account connection/OAuth, feeds and analytics through one API;
- it supports TikTok and TikTok Business integration routes;
- current public pricing starts at $10/month for up to 1,000 successful posts with unlimited social accounts;
- it can use its own social developer credentials or customer-provided credentials;
- it removes the need for v1 to own a VPS, Docker deployment, ffmpeg service, OAuth token vault and constant TikTok API maintenance;
- the phone can be fully off after the schedule is handed to the remote provider.

The Android v1 should stay visually and technically small:
- maximum three account cards;
- one wheel/content pool per account (expandable later);
- choose/upload videos;
- daily quantity;
- time window;
- compact next-post/history/status view;
- connect/disconnect account;
- no giant dashboard, no browser engine, no cloud Android emulator, no local accessibility automation.

Do **not** purchase/deploy Hetzner/Oracle infrastructure for v1. Preserve the existing YM v0.5 backend as a fallback/provider-independent engine and a source of tested scheduler logic, but do not make it mandatory for the first Android release.

Before building the full Android UI, validate Post for Me with one TikTok account: connect it, schedule one post, verify final public visibility, and verify the scheduled post completes while the phone is off. Their own TikTok integration documentation still notes TikTok production review/audit considerations, so this real test is mandatory before locking the provider permanently.

If that one-account proof fails, provider #2 is TikTok API for Business / Organic Accounts API while keeping exactly the same small Android UI.

## Not implemented / not live yet

- real one-account Post for Me proof;
- Android control app;
- real engagement/comment execution.

## Continuation order

When continuing YM:

1. Read this file first.
2. Read `/Projects/YM/STATE.md` from ChatGPT Library for the latest implementation delta.
3. Inspect the latest `YM-bootstrap.zip` only when backend/fallback work is needed.
4. Do not rebuild from scratch.
5. Keep changes small and verifiable.
6. Test after each meaningful change.
7. Distinguish clearly between code written, locally tested, provider-tested, and real TikTok-verified behavior.
8. Update this handoff and `/Projects/YM/STATE.md` after every durable milestone because development may span multiple days/conversations.

## Next milestones

1. Validate Post for Me with one TikTok account before building more infrastructure.
2. If it schedules and publishes publicly while the phone is off, lock it as the v1 execution provider.
3. Build the small Android app around `3 accounts -> wheels -> time -> quantity -> history`.
4. Keep existing YM backend code as fallback, not mandatory infrastructure.
5. If Post for Me cannot satisfy the public unattended workflow, switch only the provider to TikTok Business/Organic API.
6. Add the engagement wheel only after a supported route is confirmed.

## UX direction

Keep the visible model simple:

`3 accounts -> wheels -> time -> quantity -> history`

The user should not need to manage hundreds of individual scheduled tasks. A wheel can contain a large content pool; the user defines a quantity, time window, spacing and no-repeat rules, and the remote provider executes the plan.

## Relationship to RUN

RUN and YM share scheduling/wheel ideas but remain separate projects.

- RUN: human communication continuity on Android/WhatsApp.
- YM: small Android control app for persistent social publishing/account continuity.
