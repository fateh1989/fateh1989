# YM Relay

OAuth/token bridge for YM's authorized TikTok connection. TikTok client secrets stay server-side.

Required environment/bindings:
- `TIKTOK_CLIENT_KEY`
- `TIKTOK_CLIENT_SECRET`
- `YM_PAIR_TOKEN`
- `TOKEN_ENC_KEY` (32-byte AES key, base64)
- D1 binding `DB`

Health endpoint: `GET /health`
