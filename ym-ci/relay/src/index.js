const TIKTOK_TOKEN_URL = "https://open.tiktokapis.com/v2/oauth/token/";
const TIKTOK_USER_URL = "https://open.tiktokapis.com/v2/user/info/?fields=open_id,display_name,avatar_url";

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function requireBearer(request, env) {
  const value = request.headers.get("authorization") || "";
  if (!env.YM_PAIR_TOKEN || value !== `Bearer ${env.YM_PAIR_TOKEN}`) {
    throw new Response(JSON.stringify({ error: "unauthorized" }), {
      status: 401,
      headers: { "content-type": "application/json" },
    });
  }
}

function bytesToBase64(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

function base64ToBytes(value) {
  const s = atob(value);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

async function tokenKey(env) {
  if (!env.TOKEN_ENC_KEY) throw new Error("TOKEN_ENC_KEY missing");
  const raw = base64ToBytes(env.TOKEN_ENC_KEY);
  if (raw.byteLength !== 32) throw new Error("TOKEN_ENC_KEY must be 32 bytes base64");
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}

async function seal(value, env) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await tokenKey(env);
  const cipher = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, new TextEncoder().encode(value));
  return `${bytesToBase64(iv)}.${bytesToBase64(new Uint8Array(cipher))}`;
}

async function exchangeCode(body, env) {
  const code = String(body.code || "").trim();
  const verifier = String(body.code_verifier || "").trim();
  const redirectUri = String(body.redirect_uri || "").trim();
  if (!code || !verifier || !redirectUri) throw new Error("code, code_verifier and redirect_uri are required");
  if (!env.TIKTOK_CLIENT_KEY || !env.TIKTOK_CLIENT_SECRET) throw new Error("TikTok credentials not configured");

  const form = new URLSearchParams({
    client_key: env.TIKTOK_CLIENT_KEY,
    client_secret: env.TIKTOK_CLIENT_SECRET,
    code,
    grant_type: "authorization_code",
    redirect_uri: redirectUri,
    code_verifier: verifier,
  });

  const tokenResponse = await fetch(TIKTOK_TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: form.toString(),
  });
  const token = await tokenResponse.json();
  if (!tokenResponse.ok || token.error || !token.access_token) {
    throw new Error(`TikTok token exchange failed: ${token.error_description || token.message || token.error || tokenResponse.status}`);
  }

  const userResponse = await fetch(TIKTOK_USER_URL, {
    headers: { authorization: `Bearer ${token.access_token}` },
  });
  const userRoot = await userResponse.json();
  const user = userRoot?.data?.user;
  if (!userResponse.ok || !user?.open_id) {
    throw new Error(`TikTok user info failed: ${userRoot?.error?.message || userResponse.status}`);
  }

  if (!env.DB) throw new Error("D1 binding DB missing");
  const accessCipher = await seal(token.access_token, env);
  const refreshCipher = await seal(token.refresh_token || "", env);
  const now = Date.now();
  const accessExpiresAt = now + Number(token.expires_in || 0) * 1000;
  const refreshExpiresAt = now + Number(token.refresh_expires_in || 0) * 1000;
  const scopes = String(token.scope || body.granted_scopes || "");

  await env.DB.prepare(`
    INSERT INTO tiktok_accounts
      (open_id, display_name, avatar_url, access_token_enc, refresh_token_enc, scopes, access_expires_at, refresh_expires_at, updated_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(open_id) DO UPDATE SET
      display_name=excluded.display_name,
      avatar_url=excluded.avatar_url,
      access_token_enc=excluded.access_token_enc,
      refresh_token_enc=excluded.refresh_token_enc,
      scopes=excluded.scopes,
      access_expires_at=excluded.access_expires_at,
      refresh_expires_at=excluded.refresh_expires_at,
      updated_at=excluded.updated_at
  `).bind(
    user.open_id,
    user.display_name || user.open_id,
    user.avatar_url || "",
    accessCipher,
    refreshCipher,
    scopes,
    accessExpiresAt,
    refreshExpiresAt,
    now,
  ).run();

  return {
    id: user.open_id,
    open_id: user.open_id,
    display_name: user.display_name || user.open_id,
    username: user.display_name || user.open_id,
    avatar_url: user.avatar_url || "",
    scopes,
  };
}

async function listAccounts(env) {
  if (!env.DB) throw new Error("D1 binding DB missing");
  const result = await env.DB.prepare(`
    SELECT open_id, display_name, avatar_url, scopes, updated_at
    FROM tiktok_accounts
    ORDER BY updated_at DESC
    LIMIT 10
  `).all();
  return (result.results || []).map((row) => ({
    id: row.open_id,
    open_id: row.open_id,
    display_name: row.display_name,
    username: row.display_name,
    avatar_url: row.avatar_url,
    scopes: row.scopes,
  }));
}

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (url.pathname === "/health") return json({ ok: true, service: "ym-relay" });

      requireBearer(request, env);

      if (request.method === "POST" && url.pathname === "/api/tiktok/oauth/exchange") {
        const body = await request.json();
        const account = await exchangeCode(body, env);
        return json({ account });
      }

      if (request.method === "GET" && url.pathname === "/api/accounts") {
        if (url.searchParams.get("platform") !== "tiktok") return json({ data: [] });
        return json({ data: await listAccounts(env) });
      }

      return json({ error: "not_found" }, 404);
    } catch (error) {
      if (error instanceof Response) return error;
      return json({ error: String(error?.message || error) }, 400);
    }
  },
};
