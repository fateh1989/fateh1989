# YM / يم — Canonical Project Handoff

## Current continuation point
- Product: **YM / يم**, separate from RUN.
- Repository: `fateh1989/fateh1989`.
- Current source branch: `ym-v0.20-real-tiktok-shell`.
- Current built head: `33fb34f2224e26fe5d88bf6f8e17119b5cf6288b`.
- Android package: `com.ym.lite.stable`.
- Version: **0.20.0**, versionCode **20**.
- GitHub Actions run: **35151075813** / run number **49** — success.
- Artifact: `YM-v0.20-real-tiktok-shell-apk`.
- APK SHA-256: `3629bb50795171aefc20462e342c6d3f61b7a3ff5e3bff6d5af30690053751f9`.
- APK size: `9,690,623` bytes.

## Product direction
The user wants **real TikTok**, not a local imitation. v0.20 therefore makes YM a launcher/control shell around the installed official TikTok app:
- tapping YM opens the installed official TikTok package;
- TikTok account/feed/video data remain TikTok's own;
- YM does not copy or re-render TikTok content;
- the old local YM feed remains available only as a secondary library/test screen;
- YM's visible control inside TikTok is a small accessibility overlay bubble.

## v0.20 launcher shell
`YmEntryActivity` is now the launcher activity.
- If YM Accessibility is enabled, opening YM immediately launches real TikTok.
- If it is not enabled, YM shows a minimal setup screen with: open real TikTok, enable YM Automation, configure TikTok AUTO, and open local YM library.

## TikTok overlay
`YmTikTokAccessibilityService` keeps the strict v0.19 scope guard and now acts as the main YM control surface inside TikTok:
- short tap on YM bubble: toggle AUTO on/off;
- long press: open TikTok AUTO settings;
- bubble is removed outside TikTok;
- all swipe/comment/click/back actions re-check the active TikTok window at action time.

Allowed packages only:
- `com.zhiliaoapp.musically`
- `com.ss.android.ugc.trill`

Unit tests explicitly reject ChatGPT, Android Settings, YouTube and null package names.

## Build verification
GitHub Actions run `35151075813` passed:
- relay JavaScript syntax;
- Android unit tests;
- strict TikTok scope test;
- debug APK build;
- artifact upload.

## Device verification boundary
CI does **not** prove device runtime. Still required on the user's phone:
1. install/update v0.20;
2. enable YM Automation in Accessibility;
3. tap YM app icon and confirm it opens the installed real TikTok app;
4. confirm YM bubble appears only over TikTok;
5. tap bubble and verify automatic TikTok swipes;
6. open ChatGPT/Settings while AUTO remains enabled and confirm zero YM actions outside TikTok;
7. only after swipe proof, test automatic comments against the installed TikTok version/language.

## Continuation rule
When the user says `اكمل YM` or `اكمل يم`:
1. continue from `ym-v0.20-real-tiktok-shell` or newer;
2. do not rebuild from scratch;
3. real TikTok remains the primary visible experience;
4. local YM feed is secondary only;
5. preserve strict current-window TikTok scope for all Accessibility actions;
6. distinguish CI-built from real-device-tested status.
