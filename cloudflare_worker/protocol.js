export const LIFETIMES = Object.freeze({
  GAME_INVITE: 120000,
  FRIEND_REQUEST: 86400000,
  FRIEND_ACCEPTED: 86400000,
  FRIEND_ONLINE: 15000,
});
export class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}
export const requireThat = (condition, status, message) => {
  if (!condition) throw new HttpError(status, message);
};
export const username = (v) =>
  typeof v === "string" ? v.trim().replace(/^@/, "").toLowerCase() : "";
export const validName = (v) => /^[\p{L}\p{N}_]{1,32}$/u.test(v);
export const validId = (v) =>
  typeof v === "string" && /^[a-zA-Z0-9_:.\-]{1,200}$/.test(v);
export const response = (body, status = 200) =>
  Response.json(body, {
    status,
    headers: {
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
async function boundedText(stream, limit) {
  if (!stream) return "";
  const reader = stream.getReader(),
    chunks = [];
  let length = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > limit) {
        await reader.cancel();
        throw new HttpError(413, "Payload too large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}
export async function readBody(request, limit = 8192) {
  requireThat(
    Number(request.headers.get("Content-Length") || 0) <= limit,
    413,
    "Payload too large",
  );
  try {
    const data = JSON.parse(await boundedText(request.body, limit));
    requireThat(
      data && !Array.isArray(data) && typeof data === "object",
      400,
      "Invalid JSON object",
    );
    return data;
  } catch (e) {
    if (e instanceof HttpError) throw e;
    throw new HttpError(400, "Invalid JSON");
  }
}
export async function digest(value) {
  const bytes = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return [...new Uint8Array(bytes)]
    .map((n) => n.toString(16).padStart(2, "0"))
    .join("");
}
export function fresh(e, now = Date.now()) {
  return (
    !!e &&
    !!LIFETIMES[e.kind] &&
    Number.isSafeInteger(e.createdAt) &&
    e.createdAt > 0 &&
    e.createdAt <= now + 5000 &&
    Number.isSafeInteger(e.expiresAt) &&
    e.expiresAt > now &&
    e.expiresAt > e.createdAt &&
    e.expiresAt - e.createdAt <= LIFETIMES[e.kind]
  );
}
export function validateEvent(body, now = Date.now()) {
  const allowed = new Set([
    "id",
    "kind",
    "toUsername",
    "createdAt",
    "expiresAt",
    "roomCode",
    "roomSessionId",
    "requestId",
    "requestTimestamp",
    "presenceSessionId",
  ]);
  requireThat(
    Object.keys(body).every((k) => allowed.has(k)),
    400,
    "Unexpected event fields",
  );
  requireThat(
    validId(body.id) &&
      validName(username(body.toUsername)) &&
      fresh(body, now),
    400,
    "Invalid or expired event",
  );
  const e = {
    id: body.id,
    kind: body.kind,
    toUsername: username(body.toUsername),
    createdAt: body.createdAt,
    expiresAt: body.expiresAt,
  };
  if (body.kind === "GAME_INVITE") {
    requireThat(/^[A-Z0-9]{6}$/.test(body.roomCode || ""), 400, "Invalid room");
    e.roomCode = body.roomCode;
    if (body.roomSessionId !== undefined && body.roomSessionId !== "") {
      requireThat(validId(body.roomSessionId), 400, "Invalid room session");
      e.roomSessionId = body.roomSessionId;
    }
  }
  if (["FRIEND_REQUEST", "FRIEND_ACCEPTED"].includes(body.kind)) {
    requireThat(validId(body.requestId), 400, "Missing request ID");
    e.requestId = body.requestId;
    e.requestTimestamp = Number.isSafeInteger(body.requestTimestamp)
      ? body.requestTimestamp
      : body.createdAt;
  }
  if (body.kind === "FRIEND_ONLINE") {
    requireThat(
      validId(body.presenceSessionId),
      400,
      "Missing presence session",
    );
    e.presenceSessionId = body.presenceSessionId;
  }
  return e;
}
export async function decodeLegacy(raw) {
  requireThat(raw.length <= 100000, 502, "Invalid registry response");
  let s = raw.trim();
  try {
    const parsed = JSON.parse(s);
    if (typeof parsed === "string") s = parsed;
  } catch {}
  if (s.startsWith("{") || s.startsWith("[")) return JSON.parse(s);
  const gzip = s.startsWith("GZ:");
  if (gzip) s = s.slice(3);
  const bytes = Uint8Array.from(
    atob(s.replace(/-/g, "+").replace(/_/g, "/")),
    (c) => c.charCodeAt(0),
  );
  if (!gzip) return JSON.parse(new TextDecoder().decode(bytes));
  const stream = new Blob([bytes])
    .stream()
    .pipeThrough(new DecompressionStream("gzip"));
  const decoded = await boundedText(stream, 65536);
  return JSON.parse(decoded);
}
export async function legacyRead(env, key) {
  requireThat(
    env.LEGACY_API_URL && env.LEGACY_APP_KEY,
    503,
    "Account migration is not configured",
  );
  const r = await fetch(
    `${env.LEGACY_API_URL}/GetValue/${encodeURIComponent(env.LEGACY_APP_KEY)}/${encodeURIComponent(key)}`,
    { signal: AbortSignal.timeout(8000) },
  );
  requireThat(r.ok, 503, "Account directory temporarily unavailable");
  const raw = await r.text();
  if (["", "null", '""'].includes(raw.trim())) return null;
  try {
    return await decodeLegacy(raw);
  } catch {
    throw new HttpError(503, "Account directory response is unavailable");
  }
}

/** Push identity migration reads the existing public Firebase profile, never MQTT room data. */
export async function profileIdentity(env, uid, transport = fetch) {
  requireThat(/^[a-zA-Z0-9_-]+$/.test(env.FIREBASE_PROJECT_ID || ""), 503,
    "Firebase notification directory is not configured");
  const url = `https://firestore.googleapis.com/v1/projects/${env.FIREBASE_PROJECT_ID}/databases/(default)/documents/users/${encodeURIComponent(uid)}`;
  const result = await transport(url, { signal: AbortSignal.timeout(8000) });
  if (result.status === 404) return null;
  requireThat(result.ok, 503, "Firebase account directory temporarily unavailable");
  const document = await result.json();
  const name = username(document.fields?.username?.stringValue);
  requireThat(validName(name), 503, "Firebase account profile is unavailable");
  return { uid, username: name };
}
