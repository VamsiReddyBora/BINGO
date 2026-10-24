import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { webcrypto, generateKeyPairSync, sign as nodeSign } from "node:crypto";
import {
  generateKeyPair,
  exportJWK,
  SignJWT,
  createLocalJWKSet,
  exportPKCS8,
} from "jose";
import worker from "../worker.js";
import { NotificationInbox } from "../inbox.js";
import { fresh, validateEvent, decodeLegacy } from "../protocol.js";
import { verifyIdentity } from "../auth.js";

const audience = "test.apps.googleusercontent.com",
  rsa = await generateKeyPair("RS256", { extractable: true });
const googleKeys = createLocalJWKSet({
  keys: [{ ...(await exportJWK(rsa.publicKey)), kid: "test", alg: "RS256" }],
});
const env = {
  GOOGLE_CLIENT_ID: audience,
  LEGACY_API_URL: "https://legacy.invalid/api",
  LEGACY_APP_KEY: "test",
  SERVICE_ACCOUNT_JSON: JSON.stringify({
    project_id: "test",
    client_email: "test@example.invalid",
    private_key: await exportPKCS8(rsa.privateKey),
  }),
};
let legacy, pushes, pushStatus, pushError;
beforeEach(() => {
  legacy = new Map();
  pushes = [];
  pushStatus = 200;
  pushError = "";
  globalThis.fetch = async (url, options = {}) => {
    const host = new URL(url).hostname;
    if (host === "legacy.invalid")
      return Response.json(
        legacy.get(
          decodeURIComponent(new URL(url).pathname.split("/").at(-1)),
        ) ?? null,
      );
    if (host === "oauth2.googleapis.com")
      return Response.json({ access_token: "synthetic", expires_in: 3600 });
    if (host === "fcm.googleapis.com") {
      pushes.push(JSON.parse(options.body));
      return Response.json(
        pushStatus === 200
          ? { name: "test" }
          : { error: { details: [{ errorCode: pushError }] } },
        { status: pushStatus },
      );
    }
    throw Error("Unexpected external request");
  };
});
class Storage {
  constructor() {
    this.map = new Map();
    this.alarm = null;
  }
  async get(k) {
    return structuredClone(this.map.get(k));
  }
  async put(k, v) {
    if (typeof k === "object") {
      for (const [a, b] of Object.entries(k))
        this.map.set(a, structuredClone(b));
    } else this.map.set(k, structuredClone(v));
  }
  async delete(k) {
    return this.map.delete(k);
  }
  async list({ prefix = "", limit = 100000, startAfter } = {}) {
    return new Map(
      [...this.map]
        .filter(
          ([k]) => k.startsWith(prefix) && (!startAfter || k > startAfter),
        )
        .sort(([a], [b]) => a.localeCompare(b))
        .slice(0, limit)
        .map(([k, v]) => [k, structuredClone(v)]),
    );
  }
  async getAlarm() {
    return this.alarm;
  }
  async setAlarm(n) {
    this.alarm = n;
  }
}
const identity = (
  name = "bob",
  uid = "google_123456",
  sequence = 1,
  generation = "gen-one",
  installationId = "install-one",
) => ({
  uid,
  username: name,
  displayName: name,
  sequence,
  generation,
  installationId,
  token: "synthetic-token",
});
const targetIdentity = () =>
  identity("stuart", "google_222222", 1, "target-gen", "target-install");

test("Online alerts allow two minutes for background delivery and use high priority", async () => {
  const { instance: i } = setup(), s = await enroll(i), t = await enroll(i, targetIdentity());
  await call(i, t, "/v2/register", { token: t.token, revision: 1, onlineLifetimeMillis: 120000 });
  await call(i, t, "/v2/friends", { uids: [s.uid], revision: 1 });
  const e = input("FRIEND_ONLINE", "return-with-delivery-budget");
  e.expiresAt = e.createdAt + 120000;
  assert.equal((await call(i, s, "/v2/events", e)).status, 202);
  await i.alarm();
  assert.equal(pushes.length, 1);
  assert.equal(pushes[0].message.android.priority, "HIGH");
  assert.ok(parseInt(pushes[0].message.android.ttl) > 100);
});

test("Older APKs retain their 15-second online payload in both FCM and inbox", async () => {
  const { instance: i } = setup(), s = await enroll(i), t = await enroll(i, targetIdentity());
  await call(i, t, "/v2/friends", { uids: [s.uid], revision: 1 });
  const e = input("FRIEND_ONLINE", "legacy-online");
  await call(i, s, "/v2/events", e);
  assert.equal((await inbox(i, t))[0].expiresAt, e.createdAt + 15000);
  await i.alarm();
  assert.equal(JSON.parse(pushes[0].message.data.event).expiresAt, e.createdAt + 15000);
});

test("Different friends' online alerts do not share a collapse key", async () => {
  const { instance: i } = setup(), s = await enroll(i), t = await enroll(i, targetIdentity());
  const other = await enroll(i, identity("kevin", "google_333333", 1, "other-generation", "other-installation"));
  await call(i, t, "/v2/friends", { uids: [s.uid, other.uid], revision: 1 });
  await call(i, s, "/v2/events", input("FRIEND_ONLINE", "bob-return"));
  await call(i, other, "/v2/events", input("FRIEND_ONLINE", "kevin-return"));
  await i.alarm();
  assert.equal(pushes.length, 2);
  for (const push of pushes) assert.equal(push.message.android.collapse_key, undefined);
});

test("New recipients retain the longer online lifetime after an object restart", async () => {
  const { instance: i, storage } = setup(), s = await enroll(i), t = await enroll(i, targetIdentity());
  await call(i, t, "/v2/register", { token: t.token, revision: 1, onlineLifetimeMillis: 120000 });
  await call(i, t, "/v2/friends", { uids: [s.uid], revision: 1 });
  const e = input("FRIEND_ONLINE", "modern-online");
  await call(i, s, "/v2/events", e);
  assert.equal((await inbox(new NotificationInbox({ storage }, env), t))[0].expiresAt, e.expiresAt);
});

test("The online guard survives restart, deduplicates retries and releases after an hour", async () => {
  const { instance: i, storage } = setup(), s = await enroll(i), t = await enroll(i, targetIdentity());
  await call(i, t, "/v2/friends", { uids: [s.uid], revision: 1 });
  const e = input("FRIEND_ONLINE", "durable-first");
  await call(i, s, "/v2/events", e);
  const restarted = new NotificationInbox({ storage }, env);
  assert.equal((await (await call(restarted, s, "/v2/events", e)).json()).status, "queued");
  assert.equal((await (await call(restarted, s, "/v2/events", input("FRIEND_ONLINE", "durable-second"))).json()).status, "suppressed");
  const clock = Date.now;
  try {
    Date.now = () => e.createdAt + 3600000;
    assert.equal((await (await call(restarted, s, "/v2/events", input("FRIEND_ONLINE", "hour-return"))).json()).status, "queued");
  } finally { Date.now = clock; }
});

test("A new online session inside an hour is suppressed without suppressing invitations", async () => {
  const { instance: i } = setup(), s = await enroll(i), t = await enroll(i, targetIdentity());
  await call(i, t, "/v2/friends", { uids: [s.uid], revision: 1 });
  const first = input("FRIEND_ONLINE", "return-first");
  assert.equal((await call(i, s, "/v2/events", first)).status, 202);
  const duplicate = await call(i, s, "/v2/events", input("FRIEND_ONLINE", "return-again"));
  assert.equal((await duplicate.json()).status, "suppressed");
  assert.equal((await call(i, s, "/v2/events", input("GAME_INVITE", "invite-independent"))).status, 202);
  assert.equal((await inbox(i, t)).filter(e => e.kind === "FRIEND_ONLINE").length, 1);
});
function setup() {
  const storage = new Storage(),
    instance = new NotificationInbox({ storage }, env);
  return { storage, instance };
}

test("Configured five-minute online cooldown releases at the exact boundary", async () => {
  const { instance: i } = setup();
  i.env = { ...i.env, FRIEND_ONLINE_INTERVAL_MS: "300000" };
  const s = await enroll(i), t = await enroll(i, targetIdentity());
  await call(i, t, "/v2/friends", { uids: [s.uid], revision: 1 });
  const first = input("FRIEND_ONLINE", "five-minute-first");
  await call(i, s, "/v2/events", first);
  const clock = Date.now;
  try {
    Date.now = () => first.createdAt + 299999;
    assert.equal((await (await call(i, s, "/v2/events", input("FRIEND_ONLINE", "just-before-five"))).json()).status, "suppressed");
    Date.now = () => first.createdAt + 300000;
    assert.equal((await (await call(i, s, "/v2/events", input("FRIEND_ONLINE", "at-five"))).json()).status, "queued");
  } finally { Date.now = clock; }
});

test("Configured thirty-minute online cooldown suppresses five-minute returns and releases at the boundary", async () => {
  const { instance: i } = setup();
  i.env = { ...i.env, FRIEND_ONLINE_INTERVAL_MS: "1800000" };
  const s = await enroll(i), t = await enroll(i, targetIdentity());
  await call(i, t, "/v2/friends", { uids: [s.uid], revision: 1 });
  const first = input("FRIEND_ONLINE", "thirty-minute-first");
  await call(i, s, "/v2/events", first);
  const clock = Date.now;
  try {
    Date.now = () => first.createdAt + 300000;
    assert.equal((await (await call(i, s, "/v2/events", input("FRIEND_ONLINE", "at-five-minutes"))).json()).status, "suppressed");
    Date.now = () => first.createdAt + 1799999;
    assert.equal((await (await call(i, s, "/v2/events", input("FRIEND_ONLINE", "just-before-thirty"))).json()).status, "suppressed");
    Date.now = () => first.createdAt + 1800000;
    assert.equal((await (await call(i, s, "/v2/events", input("FRIEND_ONLINE", "at-thirty"))).json()).status, "queued");
  } finally { Date.now = clock; }
});

test("Renewing notification authentication preserves the registered online delivery lifetime", async () => {
  const { instance: i } = setup(), s = await enroll(i), t = await enroll(i, targetIdentity());
  await call(i, t, "/v2/register", { token: t.token, revision: 1, onlineLifetimeMillis: 120000 });
  await call(i, t, "/v2/friends", { uids: [s.uid], revision: 1 });
  const event = input("FRIEND_ONLINE", "renewed-auth-lifetime");
  await call(i, s, "/v2/events", event);
  const renewed = await enroll(i, { ...targetIdentity(), tokenRevision: 1 });
  assert.equal((await inbox(i, renewed))[0].expiresAt, event.expiresAt);
});
async function enroll(i, u = identity()) {
  const r = await i.fetch(
    new Request("https://internal/internal/enroll", {
      method: "POST",
      body: JSON.stringify(u),
    }),
  );
  assert.equal(r.status, 200, await r.clone().text());
  return { ...u, ...(await r.json()) };
}
function call(i, s, path, body) {
  return i.fetch(
    new Request("https://relay.invalid" + path, {
      method: body === undefined ? "GET" : "POST",
      headers: s ? { Authorization: "Bearer " + s.session } : {},
      body: body === undefined ? undefined : JSON.stringify(body),
    }),
  );
}
async function inbox(i, s) {
  return (await (await call(i, s, "/v2/inbox")).json()).events;
}
const room = (s) =>
  legacy.set("room_ABC123", {
    hostId: s.uid,
    hostUsername: s.username,
    status: "WAITING",
  });
function input(kind = "GAME_INVITE", id = "event-one", toUsername = "stuart") {
  const now = Date.now(),
    ttl = {
      GAME_INVITE: 120000,
      FRIEND_REQUEST: 86400000,
      FRIEND_ACCEPTED: 86400000,
      FRIEND_ONLINE: 120000,
    }[kind];
  return {
    id,
    kind,
    toUsername,
    createdAt: now,
    expiresAt: now + ttl,
    ...(kind === "GAME_INVITE" ? { roomCode: "ABC123" } : {}),
    ...(["FRIEND_REQUEST", "FRIEND_ACCEPTED"].includes(kind)
      ? { requestId: "request-one", requestTimestamp: now }
      : {}),
    ...(kind === "FRIEND_ONLINE" ? { presenceSessionId: "presence-one" } : {}),
  };
}
async function jwt({
  sub = "123456",
  aud = audience,
  iss = "https://accounts.google.com",
  exp = "1h",
} = {}) {
  return new SignJWT({ sub })
    .setProtectedHeader({ alg: "RS256", kid: "test" })
    .setIssuer(iss)
    .setAudience(aud)
    .setIssuedAt()
    .setExpirationTime(exp)
    .sign(rsa.privateKey);
}
const proof = (token) => ({
  ...identity(),
  provider: "google",
  idToken: token,
});
for (const kind of [
  "GAME_INVITE",
  "FRIEND_REQUEST",
  "FRIEND_ACCEPTED",
  "FRIEND_ONLINE",
])
  test(kind + " time boundaries", () => {
    const e = input(kind);
    assert.ok(fresh(e, e.createdAt));
    assert.ok(fresh(e, e.expiresAt - 1));
    assert.ok(!fresh(e, e.expiresAt));
    assert.ok(!fresh({ ...e, expiresAt: e.expiresAt + 1 }, e.createdAt));
    assert.ok(
      !fresh(
        { ...e, createdAt: e.createdAt + 5001, expiresAt: e.expiresAt + 5001 },
        e.createdAt,
      ),
    );
  });
for (const [name, mutate] of Object.entries({
  missingId: (e) => delete e.id,
  blankTarget: (e) => (e.toUsername = ""),
  badRoom: (e) => (e.roomCode = "bad"),
  forgedSender: (e) => (e.senderUid = "forged"),
  typeOverride: (e) => (e.data = { kind: "FRIEND_ONLINE" }),
  expired: (e) => {
    e.createdAt -= 180000;
    e.expiresAt -= 180000;
  },
  future: (e) => {
    e.createdAt += 60000;
    e.expiresAt += 60000;
  },
}))
  test("Reject malformed event: " + name, () => {
    const e = input();
    mutate(e);
    assert.throws(() => validateEvent(e));
  });
test("Google subject determines account identity", async () => {
  assert.equal(
    (await verifyIdentity(proof(await jwt()), env, { googleKeys })).uid,
    "google_123456",
  );
  await assert.rejects(async () =>
    verifyIdentity({ ...proof(await jwt()), uid: "google_999999" }, env, {
      googleKeys,
    }),
  );
});
for (const [name, options] of Object.entries({
  audience: { aud: "attacker" },
  issuer: { iss: "https://attacker.invalid" },
  expired: { exp: -100 },
  subject: { sub: "invalid" },
}))
  test("Reject Google " + name, async () => {
    await assert.rejects(async () =>
      verifyIdentity(proof(await jwt(options)), env, { googleKeys }),
    );
  });
test("Empty OAuth configuration fails closed", async () => {
  await assert.rejects(
    async () =>
      verifyIdentity(
        proof(await jwt()),
        { ...env, GOOGLE_CLIENT_ID: "" },
        { googleKeys },
      ),
    (e) => e.status === 503,
  );
});
test("Guest proof covers UID, username, installation and generation", async () => {
  const k = generateKeyPairSync("ec", { namedCurve: "prime256v1" }),
    pub = k.publicKey.export({ type: "spki", format: "der" }),
    uid =
      "guest_" +
      Buffer.from(await crypto.subtle.digest("SHA-256", pub)).toString("hex");
  const p = {
    ...identity("guestplayer", uid),
    provider: "guest",
    publicKey: pub.toString("base64url"),
    timestamp: Date.now(),
    nonce: "unique",
  };
  const m = `bingo-notifications-v2\n${p.uid}\n${p.username}\n${p.installationId}\n${p.generation}\n${p.sequence}\n${p.timestamp}\n${p.nonce}`;
  p.signature = nodeSign("sha256", Buffer.from(m), k.privateKey).toString(
    "base64url",
  );
  assert.equal((await verifyIdentity(p, env)).uid, uid);
  await assert.rejects(async () =>
    verifyIdentity({ ...p, uid: "guest_old" }, env),
  );
  await assert.rejects(async () => verifyIdentity({ ...p, sequence: 2 }, env));
  await assert.rejects(async () =>
    verifyIdentity({ ...p, timestamp: Date.now() - 120000 }, env),
  );
});
test("Unauthenticated legacy relay is closed", async () => {
  assert.equal(
    (
      await worker.fetch(
        new Request("https://relay.invalid/send", {
          method: "POST",
          body: "{}",
        }),
        env,
      )
    ).status,
    401,
  );
  assert.equal(pushes.length, 0);
});
test("Internal enrollment is not publicly exposed", async () => {
  assert.equal(
    (
      await worker.fetch(
        new Request("https://relay.invalid/internal/enroll", {
          method: "POST",
          body: "{}",
        }),
        env,
      )
    ).status,
    404,
  );
});
test("Health exposes protocol but no credentials", async () => {
  const r = await worker.fetch(
      new Request("https://relay.invalid/health"),
      env,
    ),
    s = await r.text();
  assert.equal(r.status, 200);
  assert.match(s, /"protocol":2/);
  assert.doesNotMatch(s, /private_key|session|token/);
});
test("Inbox rejects missing and unknown sessions", async () => {
  const { instance: i } = setup();
  assert.equal((await call(i, null, "/v2/inbox")).status, 401);
  assert.equal(
    (await call(i, { session: "a".repeat(72) }, "/v2/inbox")).status,
    401,
  );
});
test("Unknown public token lookup does not exist", async () => {
  assert.equal(
    (await worker.fetch(new Request("https://relay.invalid/token/bob"), env))
      .status,
    404,
  );
});
test("New generation revokes old account session; old enrollment cannot overtake it", async () => {
  const { instance: i } = setup(),
    a = await enroll(i),
    b = await enroll(i, identity("stuart", "google_222222", 2, "gen-two"));
  assert.equal((await call(i, a, "/v2/inbox")).status, 401);
  assert.equal((await call(i, b, "/v2/inbox")).status, 200);
  const r = await i.fetch(
    new Request("https://internal/internal/enroll", {
      method: "POST",
      body: JSON.stringify(identity()),
    }),
  );
  assert.equal(r.status, 409);
});
test("Old revocation never deletes a new registration", async () => {
  const { instance: i } = setup(),
    a = await enroll(i),
    b = await enroll(i, identity("stuart", "google_222222", 2, "gen-two"));
  await call(i, null, "/v2/revoke", {
    registrationId: a.registrationId,
    revokeSecret: a.revokeSecret,
  });
  assert.equal((await call(i, b, "/v2/inbox")).status, 200);
});
test("Incorrect revoke capability has no effect", async () => {
  const { instance: i } = setup(),
    s = await enroll(i);
  await call(i, null, "/v2/revoke", {
    registrationId: s.registrationId,
    revokeSecret: "wrong",
  });
  assert.equal((await call(i, s, "/v2/inbox")).status, 200);
});
test("Revoked generation cannot re-enroll", async () => {
  const { instance: i } = setup(),
    s = await enroll(i);
  await call(i, null, "/v2/revoke", {
    registrationId: s.registrationId,
    revokeSecret: s.revokeSecret,
  });
  const r = await i.fetch(
    new Request("https://internal/internal/enroll", {
      method: "POST",
      body: JSON.stringify(identity()),
    }),
  );
  assert.equal(r.status, 409);
});
test("Legacy username owner must match verified account", async () => {
  const { instance: i } = setup();
  legacy.set("reg_bob", { uid: "google_999999" });
  const r = await i.fetch(
    new Request("https://internal/internal/enroll", {
      method: "POST",
      body: JSON.stringify(identity()),
    }),
  );
  assert.equal(r.status, 403);
});
test("Authenticated lobby members can notify without an HTTP host record", async () => {
  const { instance: i } = setup(), s = await enroll(i);
  assert.equal((await call(i, s, "/v2/events", input())).status, 202);
  legacy.set("room_ABC123", { hostId: "other", hostUsername: "other", status: "WAITING" });
  assert.equal((await call(i, s, "/v2/events", input("GAME_INVITE", "other"))).status, 202);
});
test("MQTT-only room invitation push does not require an HTTP room mirror", async () => {
  const { instance: i } = setup();
  const s = await enroll(i), t = await enroll(i, targetIdentity());
  // Neither a legacy room nor an HTTP room metadata record exists.
  const response = await call(i, s, "/v2/events", input("GAME_INVITE", "mqtt-only"));
  assert.equal(response.status, 202, await response.clone().text());
  await i.alarm();
  assert.equal(pushes.length, 1);
  const sent = JSON.parse(pushes[0].message.data.event);
  assert.equal(sent.id, "mqtt-only");
  assert.equal(sent.roomCode, "ABC123");
  assert.equal((await inbox(i, t))[0].id, "mqtt-only");
});
test("Push and MQTT invitation retain the same room session", async () => {
  const { instance: i } = setup();
  const s = await enroll(i), t = await enroll(i, targetIdentity());
  room(s);
  const body = { ...input(), roomSessionId: "friendly-room-session" };
  const response = await call(i, s, "/v2/events", body);
  assert.equal(response.status, 202, await response.clone().text());
  assert.equal((await inbox(i, t))[0].roomSessionId, body.roomSessionId);
  await i.alarm();
  assert.equal(JSON.parse(pushes[0].message.data.event).roomSessionId, body.roomSessionId);
  const conflict = await call(i, s, "/v2/events", { ...body, roomSessionId: "different-session" });
  assert.equal(conflict.status, 409);
});
test("Firebase-backed notification enrollment survives a legacy directory outage", async () => {
  const storage = new Storage();
  const i = new NotificationInbox({ storage }, { ...env, FIREBASE_PROJECT_ID: "test-project" });
  const previous = fetch; let legacyReads = 0;
  globalThis.fetch = async (url, options) => {
    const host = new URL(url).hostname;
    if (host === "legacy.invalid") { legacyReads++; return new Response("", { status: 503 }); }
    if (host === "firestore.googleapis.com") {
      const name = url.endsWith("google_123456") ? "bob" : "stuart";
      return Response.json({ fields: { username: { stringValue: name } } });
    }
    return previous(url, options);
  };
  const s = await enroll(i); await enroll(i, targetIdentity());
  assert.equal((await call(i, s, "/v2/events", input())).status, 202);
  assert.equal(legacyReads, 0);
  await i.alarm(); assert.equal(pushes.length, 1);
});
test("Firebase directory failure retries enrollment without accepting a mismatched profile", async () => {
  const storage = new Storage();
  const i = new NotificationInbox({ storage }, { ...env, FIREBASE_PROJECT_ID: "test-project" });
  globalThis.fetch = async () => new Response("", { status: 503 });
  const enrollRequest = () => new Request("https://internal/internal/enroll", { method: "POST", body: JSON.stringify(identity()) });
  assert.equal((await i.fetch(enrollRequest())).status, 503);
  globalThis.fetch = async () => Response.json({ fields: { username: { stringValue: "someoneelse" } } });
  assert.equal((await i.fetch(enrollRequest())).status, 403);
});
test("Concurrent invitation writes preserve all distinct events", async () => {
  const { instance: i } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  room(s);
  const rs = await Promise.all(
    Array.from({ length: 8 }, (_, n) =>
      call(i, s, "/v2/events", input("GAME_INVITE", "concurrent-" + n)),
    ),
  );
  assert.ok(rs.every((r) => r.status === 202));
  assert.equal((await inbox(i, t)).length, 8);
});
test("Duplicate after ack cannot resurrect invite and ack cannot remove a new invite", async () => {
  const { instance: i } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  room(s);
  const e = input();
  await call(i, s, "/v2/events", e);
  await call(i, t, "/v2/ack", { id: e.id });
  await call(i, s, "/v2/events", e);
  assert.equal((await inbox(i, t)).length, 0);
  await call(i, s, "/v2/events", input("GAME_INVITE", "new"));
  await call(i, t, "/v2/ack", { id: e.id });
  assert.deepEqual(
    (await inbox(i, t)).map((e) => e.id),
    ["new"],
  );
});
test("Unrelated account cannot acknowledge target inbox", async () => {
  const { instance: i } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  room(s);
  await call(i, s, "/v2/events", input());
  await call(i, s, "/v2/ack", { id: "event-one" });
  assert.equal((await inbox(i, t)).length, 1);
});
test("Request and acceptance are delivered; acceptance closes old request", async () => {
  const { instance: i } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  assert.equal(
    (await call(i, s, "/v2/events", input("FRIEND_REQUEST"))).status,
    202,
  );
  assert.equal((await inbox(i, t))[0].kind, "FRIEND_REQUEST");
  assert.equal(
    (
      await call(
        i,
        t,
        "/v2/events",
        input("FRIEND_ACCEPTED", "accepted", "bob"),
      )
    ).status,
    202,
  );
  assert.equal((await inbox(i, t)).length, 0);
  assert.equal((await inbox(i, s))[0].kind, "FRIEND_ACCEPTED");
});
test("Cannot forge request acceptance without matching request", async () => {
  const { instance: i } = setup(),
    s = await enroll(i);
  assert.equal(
    (await call(i, s, "/v2/events", input("FRIEND_ACCEPTED"))).status,
    425,
  );
});
test("Online requires current recipient consent; stale friends update cannot undo removal", async () => {
  const { instance: i } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  assert.equal(
    (await call(i, s, "/v2/events", input("FRIEND_ONLINE"))).status,
    403,
  );
  await call(i, t, "/v2/friends", { uids: [s.uid], revision: 1 });
  assert.equal(
    (await call(i, s, "/v2/events", input("FRIEND_ONLINE"))).status,
    202,
  );
  await call(i, t, "/v2/friends", { uids: [], revision: 3 });
  await call(i, t, "/v2/friends", { uids: [s.uid], revision: 2 });
  assert.equal(
    (await call(i, s, "/v2/events", input("FRIEND_ONLINE", "new-online")))
      .status,
    403,
  );
});
test("Distinct sends are rate limited", async () => {
  const { instance: i } = setup(),
    s = await enroll(i);
  room(s);
  const statuses = [];
  for (let n = 0; n < 11; n++)
    statuses.push(
      (await call(i, s, "/v2/events", input("GAME_INVITE", "rate-" + n)))
        .status,
    );
  assert.equal(statuses.filter((v) => v === 202).length, 10);
  assert.equal(statuses.at(-1), 429);
});
test("Inbox survives object restart without extending expiry", async () => {
  const { instance: i, storage } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  room(s);
  const e = input();
  await call(i, s, "/v2/events", e);
  const result = (await inbox(new NotificationInbox({ storage }, env), t))[0];
  assert.equal(result.expiresAt, e.expiresAt);
  assert.equal(result.recipientUid, t.uid);
});
test("FCM uses generation binding, explicit remaining TTL and data-only payload", async () => {
  const { instance: i, storage } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  room(s);
  await call(i, s, "/v2/events", input());
  await i.alarm();
  assert.equal(pushes.length, 1);
  const m = pushes[0].message,
    e = JSON.parse(m.data.event);
  assert.equal(e.recipientUid, t.uid);
  assert.equal(e.installationId, t.installationId);
  assert.equal(e.generation, t.generation);
  assert.ok(parseInt(m.android.ttl) <= 120);
  assert.equal(m.android.priority, "HIGH");
  assert.equal(m.notification, undefined);
  assert.equal((await storage.list({ prefix: "outbox:" })).size, 0);
});
test("Transient FCM failures retry durably without refreshing expiry", async () => {
  const { instance: i, storage } = setup(),
    s = await enroll(i);
  await enroll(i, targetIdentity());
  room(s);
  const e = input();
  await call(i, s, "/v2/events", e);
  pushStatus = 503;
  await i.alarm();
  const jobs = await storage.list({ prefix: "outbox:" });
  assert.equal(jobs.size, 1);
  const [key, job] = [...jobs][0];
  job.nextAttempt = 0;
  await storage.put(key, job);
  pushStatus = 200;
  await i.alarm();
  assert.equal((await storage.list({ prefix: "outbox:" })).size, 0);
  assert.equal(
    JSON.parse(pushes.at(-1).message.data.event).expiresAt,
    e.expiresAt,
  );
});
test("Invalid registration cleanup affects only invalid target", async () => {
  const { instance: i, storage } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  room(s);
  await call(i, s, "/v2/events", input());
  pushStatus = 404;
  pushError = "UNREGISTERED";
  await i.alarm();
  assert.equal(
    (await storage.get("registration:" + t.registrationId)).token,
    "",
  );
  assert.equal(
    (await storage.get("registration:" + s.registrationId)).token,
    s.token,
  );
});
test("Acknowledged jobs are discarded without FCM", async () => {
  const { instance: i } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  room(s);
  await call(i, s, "/v2/events", input());
  await call(i, t, "/v2/ack", { id: "event-one" });
  await i.alarm();
  assert.equal(pushes.length, 0);
});
test("Legacy directory outage does not block invitation push or erase existing inbox", async () => {
  const { instance: i } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  room(s);
  await call(i, s, "/v2/events", input());
  const old = fetch;
  globalThis.fetch = async (url, o) =>
    new URL(url).hostname === "legacy.invalid"
      ? new Response("", { status: 503 })
      : old(url, o);
  assert.equal(
    (await call(i, s, "/v2/events", input("GAME_INVITE", "failed-read")))
      .status,
    202,
  );
  assert.equal((await inbox(i, t)).length, 2);
});
test("Legacy migration decoding supports raw and base64 JSON", async () => {
  const e = { uid: "google_123456" };
  assert.deepEqual(await decodeLegacy(JSON.stringify(e)), e);
  assert.deepEqual(
    await decodeLegacy(
      JSON.stringify(Buffer.from(JSON.stringify(e)).toString("base64url")),
    ),
    e,
  );
});
test("Maximum supported friend snapshot fits only after authentication", async () => {
  const { instance: i, storage } = setup(),
    s = await enroll(i);
  const body = {
    uids: Array.from(
      { length: 1000 },
      (_, n) => "guest_" + n.toString(16).padStart(64, "0"),
    ),
    revision: 2,
  };
  assert.ok(Buffer.byteLength(JSON.stringify(body)) > 8192);
  assert.equal((await call(i, null, "/v2/friends", body)).status, 401);
  assert.equal((await call(i, s, "/v2/friends", body)).status, 200);
  assert.equal((await storage.get("account:" + s.uid)).friends.length, 1000);
});
test("Token updates are monotonic even when older work arrives late", async () => {
  const { instance: i, storage } = setup(),
    s = await enroll(i);
  await call(i, s, "/v2/register", { token: "new-token", revision: 4 });
  await call(i, s, "/v2/register", { token: "old-token", revision: 2 });
  assert.equal(
    (await storage.get("registration:" + s.registrationId)).token,
    "new-token",
  );
});
test("In-flight FCM cannot block inbox requests or erase a rotated token", async () => {
  const { instance: i, storage } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  room(s);
  await call(i, s, "/v2/events", input());
  const original = fetch;
  let release, started;
  const waiting = new Promise((r) => (started = r));
  globalThis.fetch = async (url, o) =>
    new URL(url).hostname === "fcm.googleapis.com"
      ? new Promise((r) => {
          release = r;
          started();
        })
      : original(url, o);
  const alarm = i.alarm();
  await waiting;
  const response = await Promise.race([
    call(i, t, "/v2/register", { token: "rotated", revision: 2 }),
    new Promise((_, reject) =>
      setTimeout(() => reject(Error("FCM blocked inbox")), 1000),
    ),
  ]);
  assert.equal(response.status, 200);
  release(
    Response.json(
      { error: { details: [{ errorCode: "UNREGISTERED" }] } },
      { status: 404 },
    ),
  );
  await alarm;
  assert.equal(
    (await storage.get("registration:" + t.registrationId)).token,
    "rotated",
  );
  assert.equal((await storage.list({ prefix: "outbox:" })).size, 1);
  globalThis.fetch = original;
  await i.alarm();
  assert.equal(pushes.at(-1).message.token, "rotated");
});
test("Re-registering an unchanged token does not replay already accepted pushes", async () => {
  const { instance: i } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  room(s);
  await call(i, s, "/v2/events", input());
  await i.alarm();
  await call(i, t, "/v2/register", { token: t.token, revision: 0 });
  await i.alarm();
  assert.equal(pushes.length, 1);
});
test("A target enrolling after an invite was sent receives its still-live inbox", async () => {
  const { instance: i } = setup(),
    s = await enroll(i);
  room(s);
  await call(i, s, "/v2/events", input());
  await enroll(i, targetIdentity());
  await i.alarm();
  assert.equal(pushes.length, 1);
});
test("Username ownership cannot be stolen by another identity", async () => {
  const { instance: i } = setup();
  await enroll(i);
  const r = await i.fetch(
    new Request("https://internal/internal/enroll", {
      method: "POST",
      body: JSON.stringify(
        identity("bob", "google_222222", 1, "evil", "evil-install"),
      ),
    }),
  );
  assert.equal(r.status, 409);
});
test("Verified rename preserves old inbox and reserves old alias", async () => {
  const { instance: i } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  room(s);
  await call(i, s, "/v2/events", input());
  legacy.set("reg_newstuart", { uid: t.uid });
  const renamed = await enroll(i, {
    ...t,
    username: "newstuart",
    generation: "rename-gen",
    sequence: 2,
  });
  const events = await inbox(i, renamed);
  assert.equal(events.length, 1);
  assert.equal(events[0].recipientUsername, "newstuart");
  await call(i, renamed, "/v2/ack", { id: "event-one" });
  assert.equal((await inbox(i, renamed)).length, 0);
});
test("Online event queued before friend removal is suppressed before FCM", async () => {
  const { instance: i } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  await call(i, t, "/v2/friends", { uids: [s.uid], revision: 1 });
  await call(i, s, "/v2/events", input("FRIEND_ONLINE"));
  await call(i, t, "/v2/friends", { uids: [], revision: 2 });
  await i.alarm();
  assert.equal(pushes.length, 0);
  assert.equal((await inbox(i, t)).length, 0);
});
test("Anonymous authentication attempts are throttled before proof verification", async () => {
  const { instance: i } = setup();
  for (let n = 0; n < 30; n++)
    assert.equal(
      (
        await i.fetch(
          new Request("https://internal/internal/auth-rate", {
            method: "POST",
            body: JSON.stringify({ key: "same-ip" }),
          }),
        )
      ).status,
      200,
    );
  assert.equal(
    (
      await i.fetch(
        new Request("https://internal/internal/auth-rate", {
          method: "POST",
          body: JSON.stringify({ key: "same-ip" }),
        }),
      )
    ).status,
    429,
  );
});
test("Chunked oversized JSON is rejected before parsing or sending", async () => {
  const { instance: i } = setup(),
    s = await enroll(i);
  const r = await i.fetch(
    new Request("https://relay.invalid/v2/events", {
      method: "POST",
      headers: { Authorization: "Bearer " + s.session },
      body: JSON.stringify({ data: "X".repeat(9000) }),
    }),
  );
  assert.equal(r.status, 413);
});
test("Migrated legacy request acceptance requires mutual authenticated friendship", async () => {
  const { instance: i } = setup(),
    s = await enroll(i),
    t = await enroll(i, targetIdentity());
  await call(i, s, "/v2/friends", { uids: [t.uid], revision: 1 });
  assert.equal(
    (
      await call(
        i,
        t,
        "/v2/events",
        input("FRIEND_ACCEPTED", "legacy-accepted", "bob"),
      )
    ).status,
    425,
  );
  await call(i, t, "/v2/friends", { uids: [s.uid], revision: 1 });
  assert.equal(
    (
      await call(
        i,
        t,
        "/v2/events",
        input("FRIEND_ACCEPTED", "legacy-accepted", "bob"),
      )
    ).status,
    202,
  );
});
