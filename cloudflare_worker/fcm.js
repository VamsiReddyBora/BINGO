import { importPKCS8, SignJWT } from "jose";
import { requireThat } from "./protocol.js";
let cache = null,
  refresh = null;
async function accessToken(credentials, transport) {
  const now = Math.floor(Date.now() / 1000);
  if (cache?.email === credentials.client_email && cache.expiresAt > now + 120)
    return cache.token;
  if (!refresh)
    refresh = (async () => {
      const key = await importPKCS8(credentials.private_key, "RS256");
      const assertion = await new SignJWT({
        scope: "https://www.googleapis.com/auth/firebase.messaging",
      })
        .setProtectedHeader({ alg: "RS256", typ: "JWT" })
        .setIssuer(credentials.client_email)
        .setAudience("https://oauth2.googleapis.com/token")
        .setIssuedAt(now)
        .setExpirationTime(now + 3600)
        .sign(key);
      const r = await transport("https://oauth2.googleapis.com/token", {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
          assertion,
        }),
        signal: AbortSignal.timeout(8000),
      });
      requireThat(r.ok, 503, "Push authentication unavailable");
      const data = await r.json();
      requireThat(
        typeof data.access_token === "string",
        503,
        "Push authentication unavailable",
      );
      cache = {
        email: credentials.client_email,
        token: data.access_token,
        expiresAt: now + Math.min(data.expires_in || 3600, 3600),
      };
      return cache.token;
    })().finally(() => {
      refresh = null;
    });
  return refresh;
}
export async function sendFcm(env, token, event, transport = fetch) {
  const credentials =
    typeof env.SERVICE_ACCOUNT_JSON === "string"
      ? JSON.parse(env.SERVICE_ACCOUNT_JSON)
      : env.SERVICE_ACCOUNT_JSON;
  requireThat(
    credentials?.private_key &&
      credentials?.client_email &&
      credentials?.project_id,
    503,
    "Push service is not configured",
  );
  const access = await accessToken(credentials, transport);
  const ttl = Math.max(0, Math.floor((event.expiresAt - Date.now()) / 1000));
  if (ttl <= 0) return { expired: true };
  const r = await transport(
    `https://fcm.googleapis.com/v1/projects/${credentials.project_id}/messages:send`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${access}`,
      },
      signal: AbortSignal.timeout(8000),
      body: JSON.stringify({
        message: {
          token,
          data: { version: "2", event: JSON.stringify(event) },
          android: {
            priority: event.kind === "FRIEND_ONLINE" ? "NORMAL" : "HIGH",
            ttl: `${ttl}s`,
            collapse_key:
              event.kind === "FRIEND_ONLINE" ? "bingo_presence" : undefined,
          },
        },
      }),
    },
  );
  const result = await r.json().catch(() => ({}));
  if (r.status === 401) cache = null;
  const invalid = (result.error?.details || []).some(
    (d) => d.errorCode === "UNREGISTERED",
  );
  return {
    accepted: r.ok,
    retryable: r.status === 401 || r.status === 429 || r.status >= 500,
    invalid,
    status: r.status,
  };
}
