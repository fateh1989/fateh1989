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

Do **not** choose or build the live provider yet. Research first, then decide.

Routes identified:

1. **TikTok for Developers — Content Posting Direct Post**: works with cloud/web apps and can post directly, but public visibility requires audit/approval and TikTok requires creator-facing privacy/settings UI and explicit consent. Strong fit for normal creator-facing schedulers; uncertain fit for YM's fully unattended private three-account manager.
2. **TikTok for Developers — Upload/Draft**: uploads media as a draft, then the user must finish the post in TikTok. Safe and official but fails the phone-off/unattended requirement.
3. **TikTok API for Business — Organic/Accounts API**: has an official endpoint to publish a public video to an owned TikTok account, plus publish status, account analytics, hashtag/location helpers, and management of comments on videos owned by the account. Since March 20, 2026 developers requesting TikTok Accounts scopes must complete an Accounts API Access Application. This route requires a TikTok For Business developer setup and may imply Business Account / Business Center requirements. For EU/UK/US Business Center account-management integration, current TikTok help states a Verified Business Account is required. This is currently the most structurally promising official route for YM, but eligibility/approval must be proven before implementation.
4. **TikTok Business Center / Web Business Suite native scheduler**: official and low-risk. Current TikTok materials expose account management, analytics and a scheduler; TikTok's scheduler documentation states 15 minutes to 10 days in advance. Good fallback or manual control surface, but by itself does not satisfy a month-long autonomous YM loop.
5. **TikTok Marketing Partner / third-party scheduler** (Later/Hootsuite/Metricool/Sprout-class tools): proven auto-publishing exists through approved partners. Fastest product route but adds subscription/vendor dependence and does not give YM ownership of the execution layer.
6. **Unified posting API provider** (for example Post for Me): can absorb OAuth, platform changes and scheduling; some providers support both TikTok API and TikTok Business API. Useful as a temporary or fallback provider behind YM's provider interface, but it is paid/vendor-dependent and must still be checked for whether TikTok posts are truly direct/public versus drafts for the intended account type.
7. **Browser automation against TikTok Studio/Web Business Suite** (Playwright): open-source projects show this is technically workable and can reuse login state, upload and schedule. It keeps the user's physical phone off, but it is unofficial/brittle: page selectors and login state break, CAPTCHA/re-auth may interrupt it, and browser automation may conflict with TikTok terms/platform controls. Do not use anti-detection/evasion techniques; treat this only as a fallback if a supported route cannot satisfy the product.
8. **Android app automation on a cloud Android VM/emulator or spare always-on phone**: functionally closest to RUN's execution style and can keep the user's main phone off. However it is operationally heavy, brittle under UI changes/login challenges, and not a preferred route for account safety. A physical spare device is more realistic than an emulator if ever explored, but it violates the goal of an execution engine that is naturally cloud/server based.
9. **Share Kit / Android intents**: official mobile sharing, but requires a mobile app/user flow and therefore does not satisfy YM's fully unattended phone-off goal.
10. **Hybrid YM planner + human confirmation**: YM performs wheels/timing/content selection/history/analytics, but sends drafts or prepares scheduled posts for a person to approve. This preserves most of YM's intelligence with the lowest platform risk, but gives up full autonomy.

Important account-type tradeoff: TikTok Business Accounts currently see only the Commercial Music Library when adding sound, whereas Personal Accounts can see the broader sound library. Do not convert the three target accounts to Business Account until the user confirms that losing general music access is acceptable for their content strategy.

Engagement boundary: the Accounts API can create/reply/like/hide/delete comments associated with organic videos owned by the authorized account. It does **not** establish a general supported path for leaving arbitrary comments/emoji on other creators' videos. Keep the original outbound engagement wheel unimplemented until a supported route is found. TikTok publicly prohibits spam/fake engagement and bulk account manipulation.

Current research preference (not a final decision): first prove whether the **TikTok API for Business Accounts/Organic API** can be approved for the intended three owned accounts without unacceptable Business Account tradeoffs. If not, compare a vetted partner/unified provider against a hybrid draft/confirmation model before considering browser/device automation.

## Not implemented / not live yet

- deployment to a permanent always-on HTTPS host;
- persistence verification on a real host across restart/redeploy;
- TikTok URL/domain verification against the deployed media origin;
- first real TikTok developer app/account authorization;
- first controlled real post and end-to-end final-status verification;
- creator-specific duration enforcement before each live Direct Post;
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

1. Finish provider-route research and choose the live execution model before any provider deployment.
2. Choose an always-on HTTPS host with persistent storage and secret management only after provider requirements are known.
3. Deploy v0.5 in `mock` mode first and verify database/media persistence across restart/redeploy.
4. Point `YM_PUBLIC_BASE_URL` at the final HTTPS origin if the chosen provider needs pullable media URLs.
5. Connect account 1 only after the chosen route is approved/supported.
6. Perform one controlled real post and verify final status end to end.
7. Extend to accounts 2 and 3 only after account 1 is stable.
8. Build the three-account dashboard/control client.
9. Add engagement-wheel execution only after confirming a supported path.

## Hosting research note — 2026-09-16

- Render free web services spin down after 15 minutes idle and have ephemeral local files, so they do not satisfy YM's current SQLite + local-media always-on requirement without architectural changes or paid persistent storage.
- Railway currently has a free plan with limited monthly credit and small volume storage, but real always-on monthly usage must be measured rather than assumed free.
- Oracle Cloud documentation still lists Always Free compute resources and is a candidate for a zero-cost VM experiment, subject to account/region capacity and current signup requirements.
- Cloudflare Containers require the Workers Paid plan, so they are not a zero-cost fit for this stage.

## UX direction

Keep the visible model simple:

`3 accounts -> wheels -> time -> quantity -> history`

The user should not need to manage hundreds of individual scheduled tasks. A wheel can contain a large content pool; the user defines a quantity, time window, spacing and no-repeat rules, and the backend executes the plan.

## Relationship to RUN

RUN and YM share scheduling/wheel ideas but remain separate projects.

- RUN: human communication continuity on Android/WhatsApp.
- YM: persistent social publishing/account continuity on an always-on backend.
