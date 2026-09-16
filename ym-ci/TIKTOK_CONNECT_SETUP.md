# YM ↔ TikTok Android connection setup

Current YM package: `com.ym.lite.stable`

## TikTok Developer Portal

1. Create/open the TikTok Developer app for YM.
2. Add the Android platform.
3. Copy the MD5 and SHA-256 signing fingerprints shown inside YM > Account > TikTok connection.
4. Add Login Kit and request the scopes YM needs. Start with `user.info.basic`.
5. Register the redirect URI used by the Android OpenSDK build:
   `https://open-platform.tiktokapis.com/callback`
6. Copy the public Client Key into YM's TikTok connection screen.
7. Keep the Client Secret server-side only. Never put it in the APK.

## YM Relay

Relay needs:
- `TIKTOK_CLIENT_KEY`
- `TIKTOK_CLIENT_SECRET`
- `YM_PAIR_TOKEN`
- `TOKEN_ENC_KEY` (32-byte AES key, base64)
- database binding/storage for TikTok account tokens

The Android app gets an authorization code using TikTok OpenSDK + PKCE, then sends the code and verifier to the relay. The relay exchanges it for TikTok access/refresh tokens and stores them encrypted.

## First live proof

1. Relay `/health` returns ok.
2. Save Client Key inside YM.
3. Tap TikTok App login (or Chrome fallback).
4. Approve `user.info.basic` in TikTok.
5. YM receives the callback and sends the code to the relay.
6. YM displays the connected TikTok display name/avatar.

Do not enable AUTO publishing until this login/account proof succeeds.
