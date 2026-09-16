# YM / يم — Canonical Project Handoff

This file is the durable handoff for the YM project. Future ChatGPT sessions should read this file before discussing or modifying YM so the project continues from the current state instead of restarting.

## Product identity

- Name: YM / يم.
- Separate project from RUN.
- Purpose: manage the growth/continuity of up to **3 TikTok accounts** through persistent publishing and light engagement workflows.
- This is **not** intended as an account farm. The design target is a small personal control system for three accounts.
- Core product idea: the user loads content and rules once, then YM keeps the accounts active over long periods with minimal manual attention.

## Philosophy

YM borrows the useful mechanics from RUN, not RUN's relationship philosophy.

The basic loop is:

`account -> wheel -> time -> quantity -> durable jobs -> publish -> history -> next`

The user should think in terms of:
- which account;
- which wheel/content pool;
- time window;
- daily quantity;
- minimum spacing;
- no-repeat/history.

The phone is a **control panel**, not the execution host. The long-term goal is that YM continues running even when the user's phone is fully powered off. Therefore the execution engine must live on an always-on backend/server/container.

## Locked product decisions

1. Maximum: **3 TikTok accounts**.
2. Each account has its own wheels, schedule, quantity, history and no-repeat state.
3. Content may come from shared or account-specific media pools, but publication history remains account-aware.
4. Publishing is managed by quantity + time window, not by forcing the user to schedule every post one by one.
5. A separate engagement wheel is part of the concept: light reactions such as emoji, short words and multilingual micro-responses.
6. Engagement should be treated as a small presence signal, not repetitive spam. The exact TikTok-supported execution path still needs confirmation before wiring it live.
7. The project should remain focused and should not become a general-purpose phone automation framework.

## Current implementation state — v0.2

A backend bootstrap exists in ChatGPT Library at:

`/Projects/YM/`

Files there include:
- `YM-bootstrap.zip`
- `README.md`
- `STATE.md`

Implemented and locally verified in v0.2:
- hard limit of 3 accounts;
- persistent accounts and per-account timezones;
- persistent wheel definitions and wheel items;
- persistent media references;
- deterministic daily time-slot planning from time window + quantity;
- daily quantity cap and minimum interval;
- quantity reduction when the configured window cannot physically fit the requested count;
- deterministic no-repeat content selection with daily variation;
- durable SQLite publication jobs;
- restart survival without duplicating planned jobs;
- stale-job protection so a restarted server does not dump an old backlog at once;
- retry/backoff state for provider failures;
- background worker running with the backend independently of the phone;
- REST API for accounts, media, wheels and jobs;
- mock TikTok provider abstraction;
- unit tests.

Last reported verification: **11 tests passed out of 11** and the server identified itself as `YM Core 0.2.0`.

## Not implemented yet

- real TikTok OAuth;
- encrypted credential/token storage;
- real TikTok Content Posting / Direct Post provider calls;
- publish-status polling and webhook handling;
- actual media-byte storage/upload pipeline;
- deployment to a permanent always-on host;
- three-account dashboard/control client;
- real engagement/comment execution;
- Android control app.

Do not claim any of those are live until they are actually wired and tested.

## Current integration boundary

The scheduler/wheel/job/history engine is intentionally independent from TikTok-specific HTTP code. TikTok sits behind a provider interface. This means the publishing provider can be changed or replaced without rebuilding the whole engine.

For real posting, use a supported/approved TikTok integration path and test it before claiming live publishing.

## Continuation order

When continuing YM:

1. Read this file first.
2. Read `/Projects/YM/STATE.md` from ChatGPT Library for the latest implementation delta.
3. Inspect the current bootstrap/code before changing it.
4. Do not rebuild from scratch.
5. Keep changes small and verifiable.
6. Test after each meaningful change.
7. Distinguish clearly between code written, locally tested, deployed, and real TikTok-verified behavior.

Recommended next milestones:

1. TikTok OAuth + encrypted token storage.
2. Official Direct Post provider implementation.
3. Publish status handling / webhook processing.
4. Real media storage + verified URL/upload pipeline.
5. Deploy backend to an always-on host.
6. Build the 3-account control dashboard.
7. Only then add live engagement execution after confirming the supported execution path.

## UX direction

Keep the visible model simple:

`3 accounts -> wheels -> time -> quantity -> history`

The user should not need to manage hundreds of individual scheduled tasks. A wheel should be able to contain a large content pool, and the user should be able to say, for example: publish 3 items per day between 18:00 and 23:00 with a minimum interval and no-repeat history.

## Relationship to RUN

RUN and YM share a conceptual scheduling/wheel heritage, but they are separate projects and should not be merged.

- RUN: human communication continuity on Android/WhatsApp.
- YM: persistent social publishing/account continuity with an always-on backend.

Future work may reuse generic algorithms or ideas, but repository/project state must remain separate.
