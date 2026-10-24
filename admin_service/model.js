export class Problem extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}
export function requireThat(ok, status, message) {
  if (!ok) throw new Problem(status, message);
}
export function username(raw) {
  const name = String(raw || "")
    .trim()
    .toLowerCase()
    .replace(/^@/, "");
  requireThat(
    /^[a-z0-9][a-z0-9_.-]{1,63}$/.test(name),
    400,
    "Invalid player username",
  );
  return name;
}
export function decodeLegacy(raw) {
  if (raw == null) return null;
  let value = raw;
  for (let n = 0; n < 3; n++) {
    if (typeof value !== "string") return value;
    const text = value.trim();
    if (!text || text === "null") return null;
    try {
      value = JSON.parse(text);
      continue;
    } catch {}
    try {
      value = new TextDecoder().decode(
        Uint8Array.from(atob(text.replace(/-/g, "+").replace(/_/g, "/")), (c) =>
          c.charCodeAt(0),
        ),
      );
    } catch {
      return text;
    }
  }
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
}
export function encodeLegacy(value) {
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  let str = "";
  for (const b of bytes) str += String.fromCharCode(b);
  return btoa(str).replace(/\+/g, "-").replace(/\//g, "_");
}
export function directory(raw) {
  let value;
  try {
    value = JSON.parse(raw);
  } catch {
    value = raw;
  }
  requireThat(typeof value === "string", 503, "Player directory is unreadable");
  return [
    ...new Set(
      value
        .split(",")
        .map((n) => n.trim().replace(/^@/, "").toLowerCase())
        .filter(Boolean),
    ),
  ]
    .map(username)
    .sort();
}
export function validatePatch(input, current = {}) {
  requireThat(
    input && typeof input === "object" && !Array.isArray(input),
    400,
    "Profile changes required",
  );
  const result = {};
  for (const [key, value] of Object.entries(input)) {
    if (key === "displayName") {
      requireThat(
        typeof value === "string" &&
          value.trim().length > 0 &&
          value.length <= 80 &&
          !/[\u0000-\u001f]/.test(value),
        400,
        "Display name must contain 1–80 printable characters",
      );
      result[key] = value.trim();
    } else if (
      [
        "gamesPlayed",
        "gamesWon",
        "currentStreak",
        "bestStreak",
        "xp",
        "level",
      ].includes(key)
    ) {
      requireThat(
        Number.isSafeInteger(value) &&
          value >= (key === "level" ? 1 : 0) &&
          value <= (key === "level" ? 10000 : 100000000),
        400,
        "Statistics must be valid whole numbers",
      );
      result[key] = value;
    } else throw new Problem(400, `Unsupported profile field: ${key}`);
  }
  requireThat(Object.keys(result).length > 0, 400, "No changes supplied");
  if (Object.hasOwn(result, "level") && !Object.hasOwn(result, "xp")) {
    const xp = current.xp || 0;
    if (Math.max(1, Math.floor(xp / 400)) !== result.level)
      result.xp = result.level === 1 ? 0 : result.level * 400;
  }
  const next = { ...current, ...result };
  requireThat(
    (next.gamesWon || 0) <= (next.gamesPlayed || 0),
    400,
    "Wins cannot exceed games played",
  );
  requireThat(
    (next.currentStreak || 0) <= (next.bestStreak || 0),
    400,
    "Current streak cannot exceed best streak",
  );
  return result;
}
export function validateAvatar(value) {
  if (value === null) return null;
  requireThat(
    typeof value === "string" && value.length <= 88000,
    400,
    "Photo exceeds the 64 KiB limit",
  );
  const match = /^data:image\/(jpeg|png|webp);base64,([A-Za-z0-9+/]+=*)$/.exec(
    value,
  );
  requireThat(match, 400, "Only JPEG, PNG and WebP photos are accepted");
  let bytes;
  try {
    bytes = Uint8Array.from(atob(match[2]), (c) => c.charCodeAt(0));
  } catch {
    throw new Problem(400, "Invalid photo encoding");
  }
  requireThat(
    bytes.length > 12 && bytes.length <= 65536,
    400,
    "Photo exceeds the 64 KiB limit",
  );
  const hex = [...bytes.slice(0, 8)]
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  const valid =
    match[1] === "jpeg"
      ? hex.startsWith("ffd8ff")
      : match[1] === "png"
        ? hex === "89504e470d0a1a0a"
        : new TextDecoder().decode(bytes.slice(0, 4)) === "RIFF" &&
          new TextDecoder().decode(bytes.slice(8, 12)) === "WEBP";
  requireThat(valid, 400, "Photo content does not match its type");
  return value;
}
export function rasterPhoto(value) {
  try {
    if (typeof value !== "string") return null;
    if (value.startsWith("data:")) return validateAvatar(value);
    const mime = value.startsWith("/9j/")
      ? "jpeg"
      : value.startsWith("iVBORw0KGgo")
        ? "png"
        : value.startsWith("UklGR")
          ? "webp"
          : null;
    return mime ? validateAvatar(`data:image/${mime};base64,${value}`) : null;
  } catch {
    return null;
  }
}
export function applyPolicy(profile, policy = {}) {
  requireThat(
    !policy.deleted,
    410,
    "This account was removed by an administrator",
  );
  if (policy.uid && profile.uid)
    requireThat(
      policy.uid === profile.uid,
      409,
      "Player identity changed; refresh before editing",
    );
  const result = { ...profile };
  const applied = Number(profile.adminRevision) || 0;
  for (const [key, change] of Object.entries(policy.fields || {}))
    if (change.revision > applied) result[key] = change.value;
  result.adminRevision = Math.max(applied, policy.revision || 0);
  return result;
}
export function presence(raw, now = Date.now(), available = true) {
  if (!available)
    return {
      status: "UNKNOWN",
      observedAt: 0,
      detail: "Presence source unavailable",
    };
  let p = decodeLegacy(raw);
  if (typeof p === "string") {
    const [status, stamp, appVersion, code] = p.split(":");
    p = {
      status,
      timestamp: Number(stamp),
      appVersion,
      appVersionCode: Number(code),
    };
  }
  if (!p || typeof p !== "object")
    return {
      status: "UNKNOWN",
      observedAt: 0,
      detail: "No presence observation",
    };
  let status = String(p.status || "").toUpperCase();
  if (status === "LOBBY") status = "IN_LOBBY";
  if (status === "IN_GAME") status = "PLAYING";
  const observedAt = Number(p.timestamp) || 0;
  if (
    !["ONLINE", "IN_LOBBY", "PLAYING", "AWAY", "OFFLINE"].includes(status) ||
    observedAt > now + 60000
  )
    status = "UNKNOWN";
  if (status !== "OFFLINE" && (!observedAt || now - observedAt > 15000))
    status = "UNKNOWN";
  return {
    status,
    observedAt,
    detail: String(p.activityDetail || "").slice(0, 100),
    roomCode: String(p.currentRoomCode || "").slice(0, 32),
    appVersion: p.appVersion || null,
    appVersionCode: Number(p.appVersionCode) || null,
  };
}
export function roomObservation(raw, code, now = Date.now()) {
  const room = decodeLegacy(raw);
  if (!room || typeof room !== "object" || Array.isArray(room)) return null;
  const protocol = Number(room.mv ?? room.metadataProtocolVersion) || 0;
  const revision = Number(room.r ?? room.revision) || 0;
  const terminal = room.tc === true || room.isTerminalClosed === true;
  return {
    ...room,
    code,
    metadataProtocolVersion: protocol,
    revision,
    status: terminal ? "CLOSED" : String(room.status || "UNKNOWN"),
    sourceStatus:
      now -
        (Number(room.lastHeartbeat) ||
          Number(room.timestamp) ||
          Number(room.createdAt) ||
          0) >
      30000
        ? "STALE"
        : "FRESH",
  };
}
export function rememberRoom(rooms, code, payload) {
  const next = roomObservation(payload, code);
  if (!next) return;
  const old = roomObservation(rooms[code], code);
  if (
    old &&
    (old.metadataProtocolVersion > next.metadataProtocolVersion ||
      (old.sessionId === next.sessionId && old.revision > next.revision) ||
      (old.sessionId !== next.sessionId &&
        (old.createdAt || 0) > (next.createdAt || 0)))
  )
    return;
  rooms[code] = JSON.stringify(next);
}
export function safeAudit(value) {
  if (typeof value === "string" && value.startsWith("data:image/"))
    return `[photo ${value.length} encoded characters]`;
  if (Array.isArray(value)) return value.map(safeAudit);
  if (value && typeof value === "object")
    return Object.fromEntries(
      Object.entries(value).map(([k, v]) => [k, safeAudit(v)]),
    );
  return value;
}
export async function mapLimit(items, limit, action) {
  const results = new Array(items.length);
  let index = 0;
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, async () => {
      while (index < items.length) {
        const i = index++;
        results[i] = await action(items[i], i);
      }
    }),
  );
  return results;
}
export async function digest(value) {
  return [
    ...new Uint8Array(
      await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)),
    ),
  ]
    .map((x) => x.toString(16).padStart(2, "0"))
    .join("");
}
export function randomToken() {
  return [...crypto.getRandomValues(new Uint8Array(32))]
    .map((x) => x.toString(16).padStart(2, "0"))
    .join("");
}
export async function passwordHash(password, salt) {
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const bytes = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      hash: "SHA-256",
      salt: new TextEncoder().encode(salt),
      iterations: 100000,
    },
    key,
    256,
  );
  return [...new Uint8Array(bytes)]
    .map((x) => x.toString(16).padStart(2, "0"))
    .join("");
}
export function constantEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length)
    return false;
  let n = 0;
  for (let i = 0; i < a.length; i++) n |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return n === 0;
}

/** A cloud write and a confirmed app application are different states. */
export function profileSyncState(profile, revision, stale = false) {
  if (stale) return { status: "UNKNOWN", label: "Sync unavailable" };
  if (!revision) return { status: "NONE", label: "No admin edits" };
  if ((Number(profile.adminRevision) || 0) < revision)
    return { status: "PENDING_CLOUD", label: "Saving to cloud" };
  const appliedRevision = Number(profile.adminAppliedRevision) || 0;
  const appliedAt = Number(profile.adminAppliedAt) || 0;
  const build = Number(profile.adminAppliedBuild) || 0;
  if (appliedRevision >= revision && appliedAt > 0 && build >= 72)
    return {
      status: "APPLIED",
      label: "Applied by app",
      revision: appliedRevision,
      appliedAt,
      appBuild: build,
    };
  return {
    status: "WAITING_APP",
    label: "Waiting for app",
    appBuild: Number(profile.appVersionCode) || null,
  };
}
