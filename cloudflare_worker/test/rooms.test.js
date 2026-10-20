import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import worker from "../worker.js";
import { NotificationInbox } from "../inbox.js";
import { digest } from "../protocol.js";

globalThis.crypto ||= webcrypto;
class Storage {
  map = new Map();
  async get(key) {
    return structuredClone(this.map.get(key));
  }
  async put(key, value) {
    if (typeof key === "object") {
      for (const [entry, item] of Object.entries(key))
        this.map.set(entry, structuredClone(item));
    } else this.map.set(key, structuredClone(value));
  }
  async list({ prefix = "" } = {}) {
    return new Map(
      [...this.map]
        .filter(([key]) => key.startsWith(prefix))
        .map(([key, value]) => [key, structuredClone(value)]),
    );
  }
  async delete(key) {
    this.map.delete(key);
  }
  async getAlarm() {
    return this.alarm || null;
  }
  async setAlarm(when) {
    this.alarm = when;
  }
}
let env, objects, legacy;
const token = "a".repeat(80);
beforeEach(async () => {
  legacy = new Map();
  objects = new Map();
  globalThis.fetch = async (url) =>
    Response.json(legacy.get(new URL(url).pathname.split("/").at(-1)) ?? null);
  env = {
    LEGACY_API_URL: "https://legacy.invalid",
    LEGACY_APP_KEY: "test",
    NOTIFICATIONS: {
      idFromName: (name) => name,
      get: (name) => {
        if (!objects.has(name))
          objects.set(
            name,
            new NotificationInbox({ storage: new Storage() }, env),
          );
        return objects.get(name);
      },
    },
  };
  const directory = env.NOTIFICATIONS.get("notification-directory-v2");
  await directory.storage.put("session:" + (await digest(token)), {
    uid: "host",
    registrationId: "registration",
    expiresAt: Date.now() + 60000,
  });
  await directory.storage.put("registration:registration", {
    uid: "host",
    revoked: false,
  });
  await directory.storage.put("account:host", { username: "kevin" });
});
const payload = (status = "WAITING", seed = "0", extra = {}) =>
  JSON.stringify({
    roomCode: "ABC123",
    hostId: "host",
    hostUsername: "kevin",
    status,
    currentSeed: "SEED_PLACEHOLDER",
    players: [{ id: "host" }, { id: "guest" }],
    ...extra,
  }).replace('"SEED_PLACEHOLDER"', seed);
async function read() {
  return worker.fetch(new Request("https://worker/v2/rooms/ABC123"), env);
}
async function commit(value, revision, seed, extra = {}) {
  return worker.fetch(
    new Request("https://worker/v2/rooms/ABC123", {
      method: "POST",
      headers: { Authorization: "Bearer " + token },
      body: JSON.stringify({
        expectedRevision: revision,
        expectedSeed: seed,
        payload: value,
        ...extra,
      }),
    }),
    env,
  );
}

test("held stale lobby write cannot replace committed match metadata", async () => {
  assert.equal((await commit(payload(), 0, null)).status, 200);
  const heldBase = await (await read()).json();
  const canonical = payload("PLAYING", "9223372036854775806", {
    canonicalStartPacketJson:
      '{"seed":9223372036854775806,"type":"START_GAME"}',
    turnClock: { turnNumber: 1, playerId: "guest", deadlineAt: 31000 },
    startCommitDeadline: 1000,
  });
  assert.equal((await commit(canonical, 1, "0")).status, 200);
  const stale = await commit(payload(), heldBase.revision, "0");
  assert.equal(stale.status, 409);
  assert.equal((await stale.json()).payload, canonical);
  assert.equal((await (await read()).json()).payload, canonical);
});

test("old generation closure cannot close rematch even with current revision", async () => {
  await commit(payload(), 0, null);
  await commit(payload("PLAYING", "100"), 1, "0");
  await commit(payload("WAITING", "0"), 2, "100");
  const rematch = payload("PLAYING", "200", {
    manualPreparationComplete: true,
  });
  await commit(rematch, 3, "0");
  assert.equal((await commit(payload("CLOSED", "100"), 2, "100")).status, 409);
  assert.equal((await commit(payload("CLOSED", "100"), 4, "100")).status, 409);
  assert.equal((await (await read()).json()).payload, rematch);
});

test("concurrent clients receive one success and one conflict for the same revision", async () => {
  await commit(payload(), 0, null);
  const results = await Promise.all([
    commit(payload("PLAYING", "10"), 1, "0"),
    commit(payload("PLAYING", "20"), 1, "0"),
  ]);
  assert.deepEqual(results.map((r) => r.status).sort(), [200, 409]);
  assert.equal((await (await read()).json()).revision, 2);
});

test("terminal records cannot be reopened by stale or freshly retried writers", async () => {
  await commit(payload(), 0, null);
  await commit(payload("CLOSED", "0"), 1, "0");
  assert.equal((await commit(payload(), 1, "0")).status, 409);
  assert.equal((await commit(payload(), 2, "0")).status, 410);
  assert.equal((await (await read()).json()).closed, true);
});

test("legacy migration preserves exact 64-bit seeds and ignores later legacy overwrites", async () => {
  const original = payload("PLAYING", "-9223372036854775807");
  legacy.set("room_ABC123", original);
  assert.equal((await (await read()).json()).payload, original);
  assert.equal((await commit(original, 0, "-9223372036854775807")).status, 200);
  legacy.set("room_ABC123", payload());
  assert.equal((await (await read()).json()).payload, original);
  assert.equal(
    (
      await commit(
        payload("CLOSED", "-9223372036854775807"),
        1,
        "-9223372036854775807",
      )
    ).status,
    200,
  );
});

test("unauthenticated and forged-actor writes cannot create or replace rooms", async () => {
  const unauthorized = await worker.fetch(
    new Request("https://worker/v2/rooms/ABC123", {
      method: "POST",
      body: JSON.stringify({
        payload: payload(),
        actorUid: "host",
        expectedRevision: 0,
      }),
    }),
    env,
  );
  assert.equal(unauthorized.status, 401);
  const forged = payload().replaceAll('"host"', '"other"');
  assert.equal(
    (await commit(forged, 0, null, { actorUid: "other" })).status,
    403,
  );
  assert.equal((await (await read()).json()).payload, null);
});

test("legacy tombstone is honored without changing an opaque seed payload", async () => {
  legacy.set("room_ABC123", payload("PLAYING", "123"));
  legacy.set("room_closed_ABC123", "true");
  assert.equal((await (await read()).json()).closed, true);
  assert.equal((await commit(payload(), 0, "123")).status, 410);
});

async function inviteRecipient() {
  const directory = env.NOTIFICATIONS.get("notification-directory-v2");
  const guestToken = "b".repeat(80);
  await directory.storage.put({
    "account:guest": {
      uid: "guest",
      username: "stuart",
      displayName: "Stuart",
      friends: ["host"],
    },
    "name:stuart": "guest",
    "registration:guest-registration": {
      id: "guest-registration",
      uid: "guest",
      revoked: false,
      token: "test-fcm-token",
      updatedAt: Date.now(),
    },
    "device:guest:guest-registration": { id: "guest-registration" },
    ["session:" + (await digest(guestToken))]: {
      uid: "guest",
      registrationId: "guest-registration",
      expiresAt: Date.now() + 60000,
    },
  });
  return { directory, guestToken };
}

function invitation(id = "invitation-regression") {
  const now = Date.now();
  return {
    id,
    kind: "GAME_INVITE",
    toUsername: "stuart",
    roomCode: "ABC123",
    createdAt: now,
    expiresAt: now + 120000,
  };
}

function sendInvitation(body) {
  return worker.fetch(
    new Request("https://worker/v2/events", {
      method: "POST",
      headers: { Authorization: "Bearer " + token },
      body: JSON.stringify(body),
    }),
    env,
  );
}

test("canonical invitation reaches recipient inbox and FCM outbox without a legacy room mirror", async () => {
  const { directory, guestToken } = await inviteRecipient();
  assert.equal((await commit(payload(), 0, null)).status, 200);
  assert.equal(legacy.get("room_ABC123"), undefined);
  const invite = invitation();
  assert.equal((await sendInvitation(invite)).status, 202);
  // A retry uses the same event identity instead of creating a second invitation.
  assert.equal((await sendInvitation(invite)).status, 202);
  const received = await worker.fetch(
    new Request("https://worker/v2/inbox", {
      headers: { Authorization: "Bearer " + guestToken },
    }),
    env,
  );
  assert.equal(received.status, 200);
  const { events } = await received.json();
  assert.equal(events.length, 1);
  assert.equal(events[0].id, invite.id);
  assert.equal(events[0].roomCode, invite.roomCode);
  assert.equal(events[0].createdAt, invite.createdAt);
  assert.equal(events[0].expiresAt, invite.expiresAt);
  assert.equal(events[0].recipientUid, "guest");
  const outbox = await directory.storage.list({
    prefix: "outbox:guest-registration:",
  });
  assert.equal(outbox.size, 1);
  assert.equal([...outbox.values()][0].eventKey, "event:stuart:" + invite.id);
});

test("canonical started and closed rooms reject invites despite stale waiting legacy metadata", async () => {
  const { directory } = await inviteRecipient();
  await commit(payload(), 0, null);
  legacy.set("room_ABC123", payload());
  await commit(payload("PLAYING", "100"), 1, "0");
  assert.equal(
    (await sendInvitation(invitation("started-match-invite"))).status,
    403,
  );
  await commit(payload("CLOSED", "100"), 2, "100");
  assert.equal(
    (await sendInvitation(invitation("closed-match-invite"))).status,
    403,
  );
  assert.equal(
    (await directory.storage.list({ prefix: "event:stuart:" })).size,
    0,
  );
  assert.equal((await directory.storage.list({ prefix: "outbox:" })).size, 0);
});

test("cold room discovery tolerates a healthy legacy response slower than 2.5 seconds", async () => {
  globalThis.fetch = async (_url, { signal }) =>
    new Promise((resolve, reject) => {
      const timer = setTimeout(() => resolve(Response.json(null)), 2800);
      signal.addEventListener(
        "abort",
        () => {
          clearTimeout(timer);
          reject(signal.reason);
        },
        { once: true },
      );
    });
  const result = await read();
  assert.equal(result.status, 200);
  assert.equal((await result.json()).payload, null);
});
