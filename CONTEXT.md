# Canonical ChatGPT / Project Context

This file is the cross-session handoff for future ChatGPT conversations and coding agents. When the user says to continue, update, inspect, build, install, or troubleshoot an existing project, read this file first and then follow the linked project/infrastructure file instead of asking the user to reconstruct old context.

## Current map

- **RUN** lives in `fateh1989/1122`, branch `main`. The project name is RUN; the repository name is `1122`.
- **Termux Bridge** lives on the Android device under `~/AI-Bridge`; main file: `~/AI-Bridge/bridge.py`. It is infrastructure for execution/control, not the primary Android app repository.
- **Heavy-equipment reverse parts lookup** is an active research topic. The target workflow is Part Number -> compatible machines/models -> serial ranges -> assembly -> exploded diagram/location -> superseded/alternative numbers. Saved sources are in `RESEARCH.md`.
- **Binaa** is historical unless explicitly requested.
- **Privacy-app suite** is a future idea only; do not start implementation without an explicit request.
- **FORGE / generic AI app-builder direction** is paused and should not be resumed by default.

## Current RUN state

RUN is an existing Android application, not a fresh project. Continue incrementally from GitHub. The main branch contains the previously developed automation features and the debug-only RUN Test Lab. CI is used for compilation/tests; real Android behavior must still be verified on a device before claiming end-to-end success.

The Love Wheel is now a local/offline presence engine rather than a hidden template bank. Main commit `55feaa6209802e45ebca64f7b09dbb4710c46c57` adds seven user-selected tone ceilings — 🙂 نبضة, ☕ قهوة, 🍫 شوكولا, 🌹 غزل, 😏 مشاكس, 🌶️ فلفل, 🔥 نار — plus a rotary preview that shows actual message text to the user. Higher levels may still use lighter presence, jokes, emojis and warmth to avoid monotonous intensity. Existing dense romantic content and poetry remain available from level 4 upward. Main CI run #185 passed unit tests, lint, APK build and signing verification; real-device UI/runtime verification is still separate.

When continuing RUN:

1. Open `fateh1989/1122`.
2. Read `AGENTS.md` and recent commits.
3. Inspect open branches/PRs and the latest GitHub Actions status.
4. Preserve the existing architecture and user data model.
5. Reuse the proven patterns in `PROGRAMMING_KNOWLEDGE.md` where relevant instead of rediscovering them.
6. Make small changes, run tests/lint/build, inspect logs on failure, and only then report success.
7. Distinguish CI success from device QA success.

## Infrastructure model

The user uses a layered model:

- **Bridge = hand**: Termux / FastMCP / command and file execution.
- **Eyes = visibility/control**: Remote Desktop Commander or equivalent device visibility/control layer.
- GitHub Actions is preferred for Android compilation/builds; Termux/device is mainly for installation, execution, and real-device testing.

Do not claim that the Bridge, Remote Desktop Commander, a tunnel, or the phone is currently connected without checking current state.

## Engineering principle

Protect the goal, not a single implementation path. If one route is blocked by Android/platform/security/tooling constraints, identify whether the limit is fundamental or merely architectural, then pivot the implementation instead of declaring the entire project impossible or repeatedly patching a dead end.

Programming knowledge is also durable context. Reusable lessons from Android, Kotlin, CI, debugging, local AI, the Bridge, persistence, scheduling, accessibility, testing, and data architecture belong in `PROGRAMMING_KNOWLEDGE.md` so future conversations inherit accumulated engineering experience rather than only project names and statuses.

## Files to read next

- `PROJECTS.md` — project/repository/status registry.
- `BRIDGE.md` — Termux Bridge architecture, paths, known issues, and restart/verification rules.
- `WORKING_RULES.md` — coding, testing, communication, cost, and workflow preferences.
- `PROGRAMMING_KNOWLEDGE.md` — reusable engineering knowledge and proven implementation patterns accumulated across projects.
- `RESEARCH.md` — saved heavy-equipment parts/reverse-lookup sources and research target.

Update these files whenever a durable project decision, infrastructure state, workflow rule, or reusable programming lesson changes.
