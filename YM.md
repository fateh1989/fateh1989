# YM / يم — Canonical Project Handoff

## Current continuation point
- Product: **YM / يم**, separate from RUN.
- Repository: `fateh1989/fateh1989`.
- Current source branch: `ym-v0.19-safe-tiktok-auto`.
- Current built head: `22a83dcd684497b6eb1cb9beee88b00b6a9c07bf`.
- Android package: `com.ym.lite.stable`.
- Version: **0.19.0**, versionCode **19**.
- GitHub Actions run: **35149961240** / run number **48** — success.
- Artifact: `YM-v0.19-safe-tiktok-auto-apk`.
- APK SHA-256: `7cf3123b3dba33fd580415124b63316ff604e1a5f5d687feeff846ccc85e5765`.
- APK size: `9,688,303` bytes.

## Locked product direction
YM must feel like a modern TikTok-style video app first, not a settings dashboard. Preserve:
- full-screen vertical video feed;
- swipe up/down;
- current-TikTok-inspired top tabs/right rail/bottom navigation;
- AUTO as a primary control;
- library/wheel, publishing scheduler, accounts, history and settings as secondary controls;
- up to 3 TikTok accounts on the cloud publishing side.

Do not rebuild YM from scratch.

## v0.18 baseline retained
v0.19 continues the v0.18 player/feed work:
- Media3 ExoPlayer full-screen playback;
- manual vertical navigation;
- playback pause/resume;
- like/save local state;
- comment panel;
- share action;
- persisted local video library/wheel;
- publishing/cloud pieces remain separate from feed automation.

## v0.19 safe TikTok AUTO
v0.19 restores a real TikTok Accessibility AUTO layer but strictly scopes every external action to the active TikTok window.

### Strict scope
Central allow-list in `TikTokScope`:
- `com.zhiliaoapp.musically`
- `com.ss.android.ugc.trill`

The unit test explicitly rejects:
- `com.openai.chatgpt`
- `com.android.settings`
- `com.google.android.youtube`
- null package names.

### Runtime guard
`YmTikTokAccessibilityService`:
- obtains `rootInActiveWindow` at the point of action;
- swipes only when the current root belongs to an allowed TikTok package;
- re-checks TikTok before comment-button click, editor text insertion, send click and Back;
- removes its AUTO accessibility overlay outside TikTok using a 250 ms scope watcher;
- if TikTok is not active, external AUTO waits and does not dispatch a global gesture;
- returning to TikTok can resume while master AUTO remains enabled.

### Android registration restored
v0.19 registers the service in `AndroidManifest.xml` with `BIND_ACCESSIBILITY_SERVICE` and `@xml/ym_accessibility_service`. The service configuration itself also limits accessibility events to the two allowed TikTok packages.

### TikTok AUTO control screen
`TikTokAutoActivity` now provides:
- AUTO on/off;
- swipe interval 3–120 seconds;
- optional automatic comments;
- comment frequency;
- local comment pool;
- direct Accessibility settings button;
- real TikTok launcher;
- pending-start flow: after the user enables YM Accessibility and returns, TikTok is opened and AUTO is armed.

The existing YM AUTO hub links to this TikTok AUTO screen while keeping cloud publishing AUTO separate.

## Build verification
GitHub Actions run `35149961240`:
- relay JavaScript syntax: passed;
- Android unit tests: passed;
- strict TikTok scope test: passed;
- `assembleDebug`: passed;
- APK upload: passed.

CI proves build/test behavior only. It does **not** prove the installed TikTok UI selectors or Accessibility behavior on the user's device.

## Real-device verification still required
1. Install YM v0.19.
2. Open `AUTO -> TikTok AUTO`.
3. Open Accessibility settings and enable **YM Automation**.
4. Start TikTok AUTO with automatic comments OFF.
5. Verify automatic vertical swipes in real TikTok.
6. Leave TikTok while AUTO remains enabled and open ChatGPT or Android Settings.
7. Verify **zero YM swipes/actions and no YM AUTO overlay outside TikTok**.
8. Return to TikTok and verify AUTO resumes only there.
9. Then enable one automatic comment and verify TikTok's current comment/editor/send selectors.
10. Keep cloud TikTok account linking/publishing verification separate from Accessibility AUTO.

## Known verification boundary
Do not claim real TikTok AUTO success, comment success, provider OAuth success or public publishing success until each is actually tested on the device/provider.

## Continuation rule
When the user says `اكمل YM` or `اكمل يم`:
1. start from branch `ym-v0.19-safe-tiktok-auto` or a newer YM branch;
2. do not return to v0.10/v0.18 unless diagnosing a regression;
3. preserve the video-first UI;
4. preserve strict current-window TikTok scope for every Accessibility action;
5. never allow global gestures/actions based on stale remembered package state;
6. distinguish CI-built from device-tested and provider-verified status.
