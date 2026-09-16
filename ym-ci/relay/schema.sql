CREATE TABLE IF NOT EXISTS tiktok_accounts (
    open_id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    avatar_url TEXT NOT NULL DEFAULT '',
    access_token_enc TEXT NOT NULL,
    refresh_token_enc TEXT NOT NULL,
    scopes TEXT NOT NULL DEFAULT '',
    access_expires_at INTEGER NOT NULL DEFAULT 0,
    refresh_expires_at INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tiktok_accounts_updated_at
ON tiktok_accounts(updated_at DESC);
