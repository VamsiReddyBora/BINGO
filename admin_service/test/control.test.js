import test from "node:test";
import assert from "node:assert/strict";
import { AdminControl } from "../worker.js";
import worker from "../worker.js";
import {
  applyPolicy,
  decodeLegacy,
  encodeLegacy,
  validateAvatar,
  validatePatch,
  presence,
  passwordHash,
} from "../model.js";
import {
  mqttConnect,
  mqttPublish,
  decodePackets,
  PacketStream,
} from "../mqtt.js";
import { toValue, fromValue } from "../legacy.js";

import { MemoryStorage, FakeLegacy } from "./fixtures.js";

const base = "https://admin.example.test";
const password = "fixture-only-password-12345";
async function fixture() {
  const storage = new MemoryStorage(),
    legacy = new FakeLegacy();
  let sent = [];
  const env = {
    ADMIN_CREDENTIALS: JSON.stringify({
      operator: "owner",
      salt: "fixture-salt",
      hash: await passwordHash(password, "fixture-salt"),
    }),
    PUBLIC_URL: base,
    ALLOWED_ORIGIN: "https://example.test",
    TEST_LEGACY: legacy,
    TEST_BROKER: async (args) => {
      sent.push(args);
      return {
        connected: true,
        presence: Object.fromEntries(
          ["kevin", "stuart", "bob"].map((n) => [
            n,
            JSON.stringify({ status: "ONLINE", timestamp: Date.now() }),
          ]),
        ),
        rooms: {},
        registry: {},
      };
    },
  };
  const control = new AdminControl({ storage }, env);
  let token = "";
  async function request(path, method = "GET", data, extra = {}) {
    const headers = { ...extra };
    if (token) headers.Authorization = "Bearer " + token;
    if (data !== undefined) headers["Content-Type"] = "application/json";
    return control.fetch(
      new Request(base + path, {
        method,
        headers,
        body: data === undefined ? undefined : JSON.stringify(data),
      }),
    );
  }
  const login = await request("/api/login", "POST", { password });
  token = (await login.json()).token;
  return {
    control,
    storage,
    legacy,
    env,
    request,
    get token() {
      return token;
    },
    sent,
  };
}
async function detail(f, n = "kevin") {
  const r = await f.request("/api/players/" + n);
  assert.equal(r.status, 200);
  return r.json();
}
const identity = (p) => ({ revision: p.revision, etag: p.etag });

test("browser auth flags and default PIN do not authorize backend reads", async () => {
  const f = await fixture();
  const r = await f.control.fetch(
    new Request(base + "/api/players", {
      headers: { Cookie: "bingo_admin_auth=true" },
    }),
  );
  assert.equal(r.status, 401);
  const login = await f.request("/api/login", "POST", {
    password: "bingo2026",
  });
  assert.equal(login.status, 401);
});
test("CORS rejects untrusted origins and preflights only allowlisted admin", async () => {
  const f = await fixture();
  f.env.ADMIN = { idFromName: (x) => x, get: () => f.control };
  assert.equal(
    (
      await worker.fetch(
        new Request(base + "/api/players", {
          headers: { Origin: "https://attacker.example" },
        }),
        f.env,
      )
    ).status,
    403,
  );
  const r = await worker.fetch(
    new Request(base + "/api/players", {
      method: "OPTIONS",
      headers: { Origin: "https://example.test" },
    }),
    f.env,
  );
  assert.equal(r.status, 204);
  assert.equal(
    r.headers.get("Access-Control-Allow-Origin"),
    "https://example.test",
  );
});
test("logout revokes the server token", async () => {
  const f = await fixture();
  assert.equal((await f.request("/api/logout", "POST", {})).status, 200);
  assert.equal((await f.request("/api/players")).status, 401);
});
test("expired and idle sessions are rejected", async () => {
  const f = await fixture();
  const [key, value] = [...(await f.storage.list({ prefix: "session:" }))][0];
  await f.storage.put(key, { ...value, lastSeen: Date.now() - 1800001 });
  assert.equal((await f.request("/api/session")).status, 401);
});
test("login attempts are rate limited", async () => {
  const f = await fixture();
  for (let i = 0; i < 8; i++)
    assert.equal(
      (await f.request("/api/login", "POST", { password: "bad" })).status,
      401,
    );
  assert.equal(
    (await f.request("/api/login", "POST", { password })).status,
    429,
  );
});
test("directory failure returns unavailable and performs no repair write", async () => {
  const f = await fixture();
  f.legacy.failDirectory = true;
  assert.equal((await f.request("/api/players")).status, 503);
  assert.deepEqual(f.legacy.writes, []);
});
test("failed profile load never authorizes a stale edit", async () => {
  const f = await fixture();
  const p = await detail(f);
  f.legacy.failRead = true;
  const r = await f.request("/api/players/kevin", "PATCH", {
    ...identity(p),
    patch: { displayName: "Edited" },
  });
  assert.equal(r.status, 503);
  assert.deepEqual(f.legacy.writes, []);
});
test("profile edit checks source etag as well as admin revision", async () => {
  const f = await fixture();
  const p = await detail(f);
  f.legacy.players.get("kevin").profile.gamesPlayed++;
  const r = await f.request("/api/players/kevin", "PATCH", {
    ...identity(p),
    patch: { displayName: "Edited" },
  });
  assert.equal(r.status, 409);
  assert.deepEqual(f.legacy.writes, []);
});
test("profile statistics validate consistency instead of storing invalid totals", () => {
  assert.throws(
    () => validatePatch({ gamesWon: 30 }, { gamesPlayed: 10 }),
    /Wins/,
  );
  assert.throws(
    () => validatePatch({ currentStreak: 8 }, { bestStreak: 3 }),
    /streak/,
  );
  assert.throws(() => validatePatch({ uid: "attacker" }), /Unsupported/);
});
test("failed external writes stay pending and are never reported synchronized", async () => {
  const f = await fixture(),
    p = await detail(f);
  f.legacy.failWrite = true;
  const r = await f.request("/api/players/kevin", "PATCH", {
    ...identity(p),
    patch: { displayName: "Edited" },
  });
  assert.equal(r.status, 202);
  const data = await r.json();
  assert.equal(data.operation.status, "PENDING");
  assert.equal((await detail(f)).profile.displayName, "Edited");
  assert.equal((await f.control.policy("kevin")).revision, 1);
  f.legacy.failWrite = false;
  await f.control.alarm();
  assert.equal(
    (await f.storage.get("operation:" + data.operation.id)).status,
    "SYNCED",
  );
});
test("deletion marker excludes a user even if stale legacy data reappears", async () => {
  const f = await fixture(),
    p = await detail(f);
  f.legacy.failWrite = true;
  const r = await f.request("/api/players/kevin", "DELETE", {
    ...identity(p),
    confirm: "kevin",
  });
  assert.equal(r.status, 202);
  const players = await (await f.request("/api/players")).json();
  assert.equal(players.total, 2);
  assert.equal(
    players.players.some((p) => p.username === "kevin"),
    false,
  );
  assert.equal((await f.request("/api/players/kevin")).status, 410);
  const policy = await (await f.request("/client/policy/kevin")).json();
  assert.equal(policy.deleted, true);
  assert.equal(
    (await (await f.request("/client/deletions")).json()).deleted[0].uid,
    "uid_kevin",
  );
});
test("delete requires typed identity and cannot target a changed account", async () => {
  const f = await fixture(),
    p = await detail(f);
  assert.equal(
    (
      await f.request("/api/players/kevin", "DELETE", {
        ...identity(p),
        confirm: "stuart",
      })
    ).status,
    400,
  );
  assert.equal((await f.request("/api/players/kevin")).status, 200);
});
test("photo deletion is explicit null and cannot fall back to an old URL", async () => {
  const f = await fixture(),
    p = await detail(f);
  const r = await f.request("/api/players/kevin/avatar", "PUT", {
    ...identity(p),
    photo: null,
  });
  assert.equal(r.status, 200);
  const current = await detail(f);
  assert.equal(current.profile.avatarUrl, null);
  assert.equal(current.profile.avatarBase64, null);
  f.legacy.players.get("kevin").profile = {
    ...p.profile,
    avatarUrl: "https://example.test/old.jpg",
  };
  assert.equal((await detail(f)).profile.avatarUrl, null);
});
test("avatar validation rejects SVG, attribute injection, wrong signatures and oversized data", () => {
  for (const input of [
    "data:image/svg+xml;base64,PHN2Zz4=",
    "https://example.test/photo.jpg",
    "data:image/png;base64,YWJjZGVmZ2hpamtsbW5vcA==",
    "data:image/png;base64," + "a".repeat(90000),
  ])
    assert.throws(() => validateAvatar(input));
  assert.equal(validateAvatar(null), null);
});
test("native app can acknowledge an admin stats reset once and continue future matches", () => {
  const policy = {
    revision: 2,
    fields: {
      gamesPlayed: { revision: 1, value: 0 },
      gamesWon: { revision: 1, value: 0 },
      displayName: { revision: 2, value: "New Name" },
    },
  };
  const applied = applyPolicy(
    { gamesPlayed: 10, gamesWon: 5, adminRevision: 0 },
    policy,
  );
  assert.equal(applied.gamesPlayed, 0);
  const next = applyPolicy({ ...applied, gamesPlayed: 1, gamesWon: 1 }, policy);
  assert.equal(next.gamesPlayed, 1);
  assert.equal(next.gamesWon, 1);
  assert.equal(
    applyPolicy({ ...next, adminRevision: 1 }, policy).gamesPlayed,
    1,
  );
});
test("friend addition and removal synchronize both sides", async () => {
  const f = await fixture();
  let a = await detail(f),
    b = await detail(f, "stuart");
  const data = { ...identity(a), otherRevision: b.revision, otherEtag: b.etag };
  assert.equal(
    (await f.request("/api/players/kevin/friends/stuart", "PUT", data)).status,
    200,
  );
  assert.equal((await detail(f)).friends[0].username, "stuart");
  assert.equal((await detail(f, "stuart")).friends[0].username, "kevin");
  a = await detail(f);
  b = await detail(f, "stuart");
  assert.equal(
    (
      await f.request("/api/players/kevin/friends/stuart", "DELETE", {
        ...identity(a),
        otherRevision: b.revision,
        otherEtag: b.etag,
      })
    ).status,
    200,
  );
  assert.deepEqual((await detail(f)).friends, []);
  assert.deepEqual((await detail(f, "stuart")).friends, []);
});
test("deleted friends are filtered from old rosters", async () => {
  const f = await fixture();
  f.legacy.rosters.set("stuart", [{ uid: "uid_kevin", username: "kevin" }]);
  const p = await detail(f);
  f.legacy.failWrite = true;
  await f.request("/api/players/kevin", "DELETE", {
    ...identity(p),
    confirm: "kevin",
  });
  assert.deepEqual((await detail(f, "stuart")).friends, []);
});
test("operation retry with one idempotency key does not repeat a successful write", async () => {
  const f = await fixture(),
    p = await detail(f),
    data = { ...identity(p), patch: { displayName: "Edited" } },
    headers = { "Idempotency-Key": "fixture-operation-12345678" };
  assert.equal(
    (await f.request("/api/players/kevin", "PATCH", data, headers)).status,
    200,
  );
  const count = f.legacy.writes.length;
  assert.equal(
    (await f.request("/api/players/kevin", "PATCH", data, headers)).status,
    200,
  );
  assert.equal(f.legacy.writes.length, count);
  assert.equal(
    (
      await f.request(
        "/api/players/kevin",
        "PATCH",
        { ...data, patch: { displayName: "Other" } },
        headers,
      )
    ).status,
    409,
  );
});
test("match removal remains hidden if an older backup uploads the same record", async () => {
  const f = await fixture(),
    p = await detail(f);
  assert.equal(
    (await f.request("/api/players/kevin/matches/m1", "DELETE", identity(p)))
      .status,
    200,
  );
  f.legacy.players.get("kevin").matchHistory.push({ id: "m1", timestamp: 100 });
  const next = await detail(f);
  assert.deepEqual(
    next.matches.map((m) => m.id),
    ["m2"],
  );
});
test("closing a playing room is rejected without sending any removal", async () => {
  const f = await fixture();
  f.control.broker = async () => ({
    connected: true,
    rooms: {
      LIVE: JSON.stringify({ status: "PLAYING", lastHeartbeat: Date.now() }),
    },
  });
  assert.equal(
    (await f.request("/api/rooms/LIVE/close", "POST", {})).status,
    409,
  );
  assert.deepEqual(f.legacy.writes, []);
});
test("presence preserves rich activity and distinguishes unavailable data from offline", () => {
  const now = Date.now();
  assert.equal(
    presence(
      { status: "PLAYING", timestamp: now, activityDetail: "Online 1v1" },
      now,
    ).detail,
    "Online 1v1",
  );
  assert.equal(
    presence({ status: "ONLINE", timestamp: now - 20000 }, now).status,
    "UNKNOWN",
  );
  assert.equal(presence(null, now, false).status, "UNKNOWN");
  assert.equal(
    presence({ status: "OFFLINE", timestamp: 0 }, now).status,
    "OFFLINE",
  );
});
test("legacy JSON and Unicode base64 preserve complete data", () => {
  const value = { displayName: "Kevin 🎲", friends: ["stuart"] };
  assert.deepEqual(decodeLegacy(encodeLegacy(value)), value);
  assert.deepEqual(fromValue(toValue(value)), value);
});
test("MQTT binary framing roundtrips retained profile data without exposing credentials", () => {
  assert.equal(
    decodePackets(mqttConnect("fixture", "user", "secret"))[0].type,
    1,
  );
  const p = decodePackets(
    mqttPublish("bingo/v3/registry/kevin", '{"uid":"x"}', true, 12),
  )[0];
  assert.equal(p.topic, "bingo/v3/registry/kevin");
  assert.equal(p.id, 12);
  assert.equal(p.payload, '{"uid":"x"}');
});
test("audit records redact photo contents and never contain server secrets", async () => {
  const f = await fixture(),
    p = await detail(f);
  await f.request("/api/players/kevin", "PATCH", {
    ...identity(p),
    patch: { displayName: "Changed" },
  });
  const data = await (await f.request("/api/audit")).json();
  assert.equal(data.entries[0].operator, "owner");
  assert.equal(JSON.stringify(data).includes(password), false);
});

test("older failed friendship removal cannot overwrite a newer add on alarm retry", async () => {
  const f = await fixture();
  let a = await detail(f),
    b = await detail(f, "stuart");
  f.legacy.failWrite = true;
  const response = await f.request(
    "/api/players/kevin/friends/stuart",
    "DELETE",
    { ...identity(a), otherRevision: b.revision, otherEtag: b.etag },
  );
  const old = (await response.json()).operation;
  f.legacy.failWrite = false;
  a = await detail(f);
  b = await detail(f, "stuart");
  assert.equal(
    (
      await f.request("/api/players/stuart/friends/kevin", "PUT", {
        ...identity(b),
        otherRevision: a.revision,
        otherEtag: a.etag,
      })
    ).status,
    200,
  );
  await f.control.alarm();
  assert.equal(
    (await f.storage.get("operation:" + old.id)).status,
    "SUPERSEDED",
  );
  assert.equal((await detail(f)).friends[0].username, "stuart");
});
test("account deletion supersedes pending friendship operations", async () => {
  const f = await fixture(),
    a = await detail(f),
    b = await detail(f, "stuart");
  f.legacy.failWrite = true;
  const response = await f.request("/api/players/kevin/friends/stuart", "PUT", {
    ...identity(a),
    otherRevision: b.revision,
    otherEtag: b.etag,
  });
  const old = (await response.json()).operation;
  await f.request("/api/players/stuart", "DELETE", {
    ...identity(b),
    confirm: "stuart",
  });
  f.legacy.failWrite = false;
  await f.control.alarm();
  assert.equal(
    (await f.storage.get("operation:" + old.id)).status,
    "SUPERSEDED",
  );
  assert.deepEqual((await detail(f)).friends, []);
});
test("MQTT packet stream accepts fragmented and combined WebSocket frames", () => {
  const a = mqttPublish("bingo/v3/presence/kevin", "ONLINE", true, 1),
    b = mqttPublish("bingo/v3/presence/stuart", "OFFLINE", true, 2),
    stream = new PacketStream();
  assert.deepEqual(stream.push(a.slice(0, 5)), []);
  const packets = stream.push(Uint8Array.from([...a.slice(5), ...b]));
  assert.deepEqual(
    packets.map((p) => p.payload),
    ["ONLINE", "OFFLINE"],
  );
});
test("password rotation invalidates every previous admin session", async () => {
  const f = await fixture();
  assert.equal(
    (
      await f.request("/api/password", "POST", {
        password: "new-fixture-password-123456",
      })
    ).status,
    200,
  );
  assert.equal((await f.request("/api/session")).status, 401);
  assert.equal(
    (await f.request("/api/login", "POST", { password })).status,
    401,
  );
  assert.equal(
    (
      await f.request("/api/login", "POST", {
        password: "new-fixture-password-123456",
      })
    ).status,
    200,
  );
});
test("new player registration rejects a removed identity", async () => {
  const f = await fixture(),
    p = await detail(f);
  await f.request("/api/players/kevin", "DELETE", {
    ...identity(p),
    confirm: "kevin",
  });
  assert.equal(
    (
      await f.request("/api/players", "POST", {
        username: "other",
        uid: "uid_kevin",
        displayName: "Other",
      })
    ).status,
    409,
  );
});
test("new player record is visible only after backing storage accepts it", async () => {
  const f = await fixture();
  assert.equal(
    (
      await f.request("/api/players", "POST", {
        username: "new_player",
        uid: "guest_new_player",
        displayName: "New Player",
      })
    ).status,
    200,
  );
  assert.equal((await detail(f, "new_player")).profile.uid, "guest_new_player");
});
