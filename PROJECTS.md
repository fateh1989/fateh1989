# Project Registry

This is the canonical cross-session index for projects, infrastructure, and research owned or tracked by this account. Future ChatGPT conversations and coding agents should resolve names here before assuming a repository, rebuilding anything, or asking the user to repeat prior context.

## Active / tracked work

| Name | Canonical location | Primary branch | Status / purpose | Continuation rule |
|---|---|---|---|---|
| RUN | `fateh1989/1122` | `main` | Active Android automation / WhatsApp scheduling app. Existing codebase includes scheduling, multi-recipient work, attachments, retries/history, templates, local AI work, and a debug-only Test Lab merged into main. | Continue from the existing repository state. Do **not** search for a separate repo named RUN. Inspect recent commits, branches/PRs, CI, and existing files before editing. Build/test before claiming success. Real-device behavior still requires device QA. |
| YM / يم | `YM.md` in this repository; current state/APKs in ChatGPT Library `/Projects/YM`; current Android source branch `ym-auto-v0.8` | `ym-auto-v0.8` | Active small Android video-first TikTok companion/scheduler. v0.8 makes **AUTO** the primary feature: Accessibility-based automatic TikTok scrolling, optional automatic comments from a user-defined pool, and a floating AUTO overlay on TikTok. Existing cloud scheduling remains via `Android -> tiny Cloudflare Worker -> Post for Me -> TikTok`. v0.8 unit tests and APK build passed in GitHub Actions run `35048318665`; real TikTok AUTO behavior still requires device QA. | Read `YM.md`, then `/Projects/YM/STATE.md`, then inspect `/Projects/YM/YM-v0.8-debug.apk` and branch `ym-auto-v0.8`. Preserve the video-first UX, six-bubble limit and AUTO as the primary control. Next: real-device test auto-scroll first, then one auto-comment, tune selectors if needed; cloud provider proof remains separate. |
| Termux Bridge | Android device: `~/AI-Bridge`, main file: `~/AI-Bridge/bridge.py` | N/A | Local execution bridge between ChatGPT and Android/Termux. Last known server implementation is FastMCP on `127.0.0.1:8765`, exposed when needed through Cloudflare. | Do not reinstall or rebuild from scratch. Verify the current device/process/tunnel state before claiming it is online. Read `BRIDGE.md` first. |
| Heavy-parts reverse lookup research | `RESEARCH.md` in this repository | N/A | Research on heavy-equipment parts databases and reverse lookup: Part Number -> machines/models/serial ranges/assembly/diagram/location/supersessions. | Reuse the saved site list and search live before relying on current availability or pricing. |
| Privacy-app suite | No repository yet | N/A | Future concept: several complementary Android privacy tools, not one monolithic app. | Concept only for now. Do not start implementation unless explicitly requested. |

## Historical / paused work

| Name | Canonical location | Status | Rule |
|---|---|---|---|
| Binaa | `fateh1989/Binaa` | Earlier AI-builder experiment; user moved away from it after the approach failed to deliver useful results. | Treat as historical. Do not restart or continue unless the user explicitly asks for Binaa. |
| FORGE / AI app-builder direction | No canonical repository | Paused / not a priority after prior attempts consumed time without enough value. | Do not revive by default. Only return to it on explicit request. |
| Todd | `fateh1989/Todd` | Repository exists but is not an active mapped project in the current workflow. | Do not infer that it is RUN, the Bridge, or another discussed project. |

## Cross-session rules

1. Start with `CONTEXT.md`, then use this registry to locate the correct project or infrastructure.
2. Read `WORKING_RULES.md` before coding or troubleshooting.
3. For YM, read `YM.md` before making changes, then read the latest `/Projects/YM/STATE.md` from ChatGPT Library.
4. For the Android bridge, read `BRIDGE.md` before giving connection/restart instructions.
5. For heavy-equipment parts research, read `RESEARCH.md` and then verify the web live.
6. Never equate a blocked implementation path with an impossible project; preserve the goal and change the architecture/path when necessary.
7. Whenever a new project is created, moved, renamed, paused, or abandoned, update this registry immediately.
