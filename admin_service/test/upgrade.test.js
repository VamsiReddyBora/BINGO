import test from "node:test";
import assert from "node:assert/strict";
import { CurrentPlayerStore } from "../current-store.js";
import { releaseFixture } from "./publication-fixture.js";
import { readBody } from "../http.js";
import { toValue, fromValue } from "../legacy.js";
import { roomObservation, rememberRoom, validatePatch } from "../model.js";
import { selectPlayers, selectRooms } from "../../admin/model.js";
import { AdminControl } from "../worker.js";
import { MemoryStorage, FakeLegacy } from "./fixtures.js";
import { TestAuthenticator, enroll, authorize } from "./authenticator.js";
import { passwordHash } from "../model.js";

const json = (value) =>
  new Response(JSON.stringify(value), {
    headers: { "Content-Type": "application/json" },
  });
const doc = (path, data) => ({
  name: "projects/fixture/databases/(default)/documents/" + path,
  fields: Object.fromEntries(
    Object.entries(data).map(([k, v]) => [k, toValue(v)]),
  ),
});
function storeFixture() {
  const docs = new Map([
    [
      "users/uid_bob",
      {
        uid: "uid_bob",
        username: "bob",
        displayName: "Fresh Bob",
        gamesPlayed: 20,
        gamesWon: 7,
        adminRevision: 0,
      },
    ],
    ["usernames/bob", { uid: "uid_bob" }],
    [
      "users/uid_bob/friends/uid_kevin",
      { uid: "uid_kevin", username: "kevin", removed: false },
    ],
    [
      "users/uid_bob/friends/uid_otto",
      { uid: "uid_otto", username: "otto", removed: true },
    ],
    [
      "users/uid_bob/matches/round-2",
      { timestamp: 200, didWin: true, mode: "ONLINE" },
    ],
  ]);
  const writes = [],
    queries = [];
  let failFirebase = false;
  const transport = async (url, options = {}) => {
    const u = new URL(url);
    if (u.hostname !== "firestore.googleapis.com") return json("");
    if (failFirebase) return new Response("offline", { status: 503 });
    const path = decodeURIComponent(u.pathname.split("/documents/")[1] || "");
    if (options.method === "DELETE") {
      docs.delete(path);
      writes.push({ path, deleted: true });
      return new Response(null, { status: 204 });
    }
    if (options.method === "PATCH") {
      const raw = JSON.parse(options.body);
      const converted = Object.fromEntries(
        Object.entries(raw.fields).map(([k, v]) => [k, fromValue(v)]),
      );
      writes.push({ path, fields: raw.fields });
      docs.set(path, { ...docs.get(path), ...converted });
      return json(doc(path, docs.get(path)));
    }
    if (options.method === "POST" && u.pathname.endsWith(":runQuery")) {
      const query = JSON.parse(options.body);
      queries.push(query);
      const q = query.structuredQuery,
        cursor = q.startAt?.values[0].referenceValue;
      return json(
        [...docs]
          .filter(([key]) => key.split("/").at(-2) === q.from[0].collectionId)
          .sort(([a], [b]) => a.localeCompare(b))
          .map(([key, value]) => doc(key, value))
          .filter((d) => !cursor || d.name > cursor)
          .slice(0, q.limit)
          .map((document) => ({ document })),
      );
    }
    if (docs.has(path)) return json(doc(path, docs.get(path)));
    if (path.split("/").length % 2 === 1) {
      return json({
        documents: [...docs]
          .filter(
            ([key]) =>
              key.startsWith(path + "/") &&
              key.split("/").length === path.split("/").length + 1,
          )
          .map(([key, value]) => doc(key, value)),
      });
    }
    return new Response("", { status: 404 });
  };
  const store = new CurrentPlayerStore(
    {
      SERVICE_ACCOUNT_JSON: { project_id: "fixture" },
      LEGACY_API_URL: "https://legacy.fixture",
      LEGACY_APP_KEY: "fixture",
    },
    transport,
  );
  store.access = { value: "fixture", until: Date.now() + 3600000 };
  return {
    store,
    docs,
    writes,
    queries,
    fail: () => {
      failFirebase = true;
    },
  };
}
test("directory uses fresh Firebase profiles and never resurrects a deleted UID", async () => {
  const f = storeFixture();
  f.docs.set("users/uid_removed", { uid: "uid_removed", username: "removed" });
  f.docs.set("adminTombstones/uid_removed", { uid: "uid_removed" });
  assert.deepEqual(await f.store.names(), ["bob"]);
  assert.equal(f.store.directoryProfiles.get("bob").displayName, "Fresh Bob");
  assert.equal(f.writes.length, 0);
});
test("Firebase directory outage is an error rather than an old legacy player list", async () => {
  const f = storeFixture();
  f.fail();
  await assert.rejects(() => f.store.names(), /Could not|storage/);
});
test("fresh inspection rejects a deleted profile even with a retained username mapping", async () => {
  const f = storeFixture();
  f.docs.set("adminTombstones/uid_bob", { uid: "uid_bob" });
  await assert.rejects(
    () => f.store.read("bob"),
    (e) => e.status === 410,
  );
});
test("profile edit patches only requested fields and preserves concurrently accumulated games", async () => {
  const f = storeFixture();
  const original = await f.store.read("bob");
  f.docs.get("users/uid_bob").gamesPlayed = 21;
  const next = await f.store.mirrorProfile("bob", original.profile, {
    revision: 1,
    fields: { displayName: { revision: 1, value: "New Bob" } },
  });
  assert.equal(next.gamesPlayed, 21);
  assert.deepEqual(Object.keys(f.writes[0].fields).sort(), [
    "adminChanges",
    "adminClearMatchesBefore",
    "adminDeletedMatches",
    "adminRevision",
    "displayName",
  ]);
  assert.equal(f.docs.get("users/uid_bob").gamesPlayed, 21);
});
test("photo removal writes explicit null fields and does not fall back to an old avatar", async () => {
  const f = storeFixture();
  f.docs.get("users/uid_bob").avatarUrl = "https://fixture/photo.jpg";
  await f.store.mirrorProfile("bob", (await f.store.read("bob")).profile, {
    revision: 1,
    fields: {
      avatarBase64: { revision: 1, value: null },
      avatarUrl: { revision: 1, value: null },
    },
  });
  assert.equal(f.docs.get("users/uid_bob").avatarUrl, null);
  assert.equal(f.docs.get("users/uid_bob").avatarBase64, null);
});
test("friends read excludes Firebase removal markers instead of merging an obsolete roster", async () => {
  const f = storeFixture();
  assert.deepEqual(
    (await f.store.friends("bob", "uid_bob")).map((f) => f.username),
    ["kevin"],
  );
});
test("recent matches use the actual collection-group path and identify the owning player", async () => {
  const f = storeFixture(),
    matches = await f.store.recentMatches(100);
  assert.equal(matches[0].ownerUid, "uid_bob");
  assert.equal(matches[0].id, "round-2");
  assert.equal(f.queries[0].structuredQuery.limit, 500);
  assert.equal(
    f.queries[0].structuredQuery.orderBy[0].field.fieldPath,
    "__name__",
  );
  assert.equal(f.queries[0].structuredQuery.from[0].allDescendants, true);
});
test("current room aliases mv r and tc are understood without inventing a live turn", () => {
  const r = roomObservation(
    JSON.stringify({
      mv: 7,
      r: 8,
      tc: true,
      status: "PLAYING",
      players: [],
      lastHeartbeat: 10,
    }),
    "ABCDE",
    100000,
  );
  assert.equal(r.metadataProtocolVersion, 7);
  assert.equal(r.revision, 8);
  assert.equal(r.status, "CLOSED");
  assert.equal(r.sourceStatus, "STALE");
});
test("an old retained legacy room cannot replace a current revision", () => {
  const rooms = {};
  rememberRoom(rooms, "ROOM", {
    mv: 7,
    r: 10,
    sessionId: "s",
    createdAt: 200,
    status: "PLAYING",
  });
  rememberRoom(
    rooms,
    "ROOM",
    JSON.stringify({ status: "WAITING", createdAt: 100 }),
  );
  rememberRoom(
    rooms,
    "ROOM",
    JSON.stringify({
      mv: 7,
      r: 9,
      sessionId: "s",
      createdAt: 200,
      status: "WAITING",
    }),
  );
  assert.equal(JSON.parse(rooms.ROOM).status, "PLAYING");
});
test("active-first players sort deterministically while filters still apply", () => {
  const rows = ["OFFLINE", "ONLINE", "PLAYING", "IN_LOBBY"].map(
    (status, i) => ({
      username: "p" + i,
      profile: { provider: "GOOGLE" },
      presence: { status },
    }),
  );
  assert.deepEqual(
    selectPlayers(rows, { sort: "presence" }).map((r) => r.presence.status),
    ["PLAYING", "IN_LOBBY", "ONLINE", "OFFLINE"],
  );
  assert.equal(
    selectPlayers(rows, { sort: "presence", status: "OFFLINE" }).length,
    1,
  );
});
test("room filters include archived rooms only when requested and sort by population", () => {
  const rows = [
    { code: "A", status: "CLOSED" },
    { code: "B", status: "PLAYING", players: [{}, {}] },
    { code: "C", status: "WAITING", hostUsername: "kevin", players: [{}] },
  ];
  assert.deepEqual(
    selectRooms(rows, { sort: "players" }).map((r) => r.code),
    ["B", "C"],
  );
  assert.equal(selectRooms(rows, { query: "kevin" })[0].code, "C");
  assert.equal(selectRooms(rows, { status: "ALL" }).length, 3);
});
test("request size enforcement counts streamed bytes even without Content-Length", async () => {
  const request = new Request("https://fixture.test", {
    method: "POST",
    body: '"éééé"',
  });
  await assert.rejects(
    () => readBody(request, 6),
    (e) => e.status === 413,
  );
});

test("APK upload stages a blob but does not publish before the durable operation", async () => {
  const f = releaseFixture(),
    ready = await f.publisher.prepare(f.input);
  assert.equal(ready.manifest.apkSize, 1200);
  assert.equal(ready.manifest.sha256.length, 64);
  assert.equal(f.feed.versionCode, 70);
  assert.equal(
    f.calls.some((c) => c.path === "/git/refs/heads/gh-pages"),
    false,
  );
});
test("update publication changes only stable APK unique APK and metadata then verifies fallback", async () => {
  const f = releaseFixture(),
    ready = await f.publisher.prepare(f.input);
  await f.publisher.publish(ready);
  assert.equal(f.feed.versionCode, 71);
  assert.deepEqual(JSON.parse(f.legacyValue), f.feed);
  const tree = f.calls.find((c) => c.path === "/git/trees").body;
  assert.equal(tree.base_tree, "base-tree");
  assert.deepEqual(tree.tree.map((t) => t.path).sort(), [
    "Bingo-1.0.4-build71.apk",
    "Bingo.apk",
    "version.json",
  ]);
  assert.equal(
    f.calls.find((c) => c.path === "/git/refs/heads/gh-pages").body.force,
    false,
  );
  assert.equal(
    f.calls.some((c) => c.path.startsWith("/releases")),
    false,
  );
});
test("retry after a fallback outage reuses the committed Pages update", async () => {
  const f = releaseFixture(),
    ready = await f.publisher.prepare(f.input);
  f.failFallback = true;
  await assert.rejects(() => f.publisher.publish(ready));
  assert.equal(f.feed.versionCode, 71);
  f.failFallback = false;
  await f.publisher.publish(ready);
  assert.equal(
    f.calls.filter((c) => c.path === "/git/refs/heads/gh-pages").length,
    1,
  );
  assert.equal(JSON.parse(f.legacyValue).versionCode, 71);
});
test("a newer update supersedes a pending publication without overwriting it", async () => {
  const f = releaseFixture(),
    ready = await f.publisher.prepare(f.input);
  f.feed = { versionName: "1.0.5", versionCode: 72 };
  await assert.rejects(
    () => f.publisher.publish(ready),
    (e) => e.status === 409,
  );
  assert.equal(
    f.calls.some((c) => c.path === "/git/refs/heads/gh-pages"),
    false,
  );
});
test("concurrent GitHub branch changes do not update the legacy feed prematurely", async () => {
  const f = releaseFixture(),
    ready = await f.publisher.prepare(f.input);
  f.failCommit = true;
  await assert.rejects(
    () => f.publisher.publish(ready),
    (e) => e.status === 409,
  );
  assert.equal(JSON.parse(f.legacyValue).versionCode, 70);
});
test("wrong checksum stale build arbitrary URL and invalid APK are rejected before publication", async () => {
  for (const patch of [
    { sha256: "0".repeat(64) },
    { expectedVersionCode: 69 },
    { versionCode: 70 },
    { apkBase64: null, downloadUrl: "https://127.0.0.1/private.apk" },
    { apkBase64: Buffer.alloc(1200).toString("base64") },
  ]) {
    const f = releaseFixture();
    await assert.rejects(() => f.publisher.prepare({ ...f.input, ...patch }));
    assert.equal(
      f.calls.some((c) => c.path === "/git/refs/heads/gh-pages"),
      false,
    );
  }
});
test("production release endpoint requires a passkey and persists a compact retryable publication", async () => {
  const f = releaseFixture(),
    storage = new MemoryStorage(),
    legacy = new FakeLegacy();
  const env = {
    TEST_LEGACY: legacy,
    TEST_RELEASES: f.publisher,
    TEST_BROKER: async () => ({
      connected: true,
      presence: {},
      rooms: {},
      registry: {},
    }),
    ALLOWED_ORIGIN: "https://fixture.test",
    PUBLIC_URL: "https://admin.fixture.test",
    ADMIN_CREDENTIALS: JSON.stringify({
      operator: "fixture",
      salt: "fixture",
      hash: await passwordHash("fixture-password-123", "fixture"),
    }),
  };
  const control = new AdminControl({ storage }, env),
    authenticator = new TestAuthenticator(env.ALLOWED_ORIGIN);
  const login = await control.fetch(
    new Request("https://admin.fixture.test/api/login", {
      method: "POST",
      body: JSON.stringify({ password: "fixture-password-123" }),
    }),
  );
  const token = (
    await enroll(control, (await login.json()).token, authenticator)
  ).token;
  let res = await control.fetch(
    new Request("https://admin.fixture.test/api/release", {
      method: "PUT",
      headers: { Authorization: "Bearer " + token },
      body: JSON.stringify(f.input),
    }),
  );
  assert.equal(res.status, 403);
  const id = crypto.randomUUID(),
    proof = await authorize(
      control,
      token,
      authenticator,
      "/api/release",
      "PUT",
      f.input,
      id,
    );
  res = await control.fetch(
    new Request("https://admin.fixture.test/api/release", {
      method: "PUT",
      headers: {
        Authorization: "Bearer " + token,
        "Idempotency-Key": id,
        "X-Action-Proof": proof,
      },
      body: JSON.stringify(f.input),
    }),
  );
  assert.equal(res.status, 200);
  const result = await res.json();
  assert.equal(result.operation.status, "SYNCED");
  assert.equal(result.operation.publication.deploymentPending, true);
  const saved = await storage.get("operation:" + id);
  assert.equal("apkBase64" in saved.data, false);
  assert.ok(JSON.stringify(saved).length < 10000);
  const read = await control.fetch(
    new Request("https://admin.fixture.test/api/release", {
      headers: { Authorization: "Bearer " + token },
    }),
  );
  assert.equal((await read.json()).release.versionCode, 71);
});

test("duplicate profiles use the username index and remain visible in storage audit", async () => {
  const f = storeFixture();
  f.docs.set("users/uid_newer", {
    uid: "uid_newer",
    username: "bob",
    displayName: "Another Bob",
    lastSeenTimestamp: 9999999999999,
  });
  assert.deepEqual(await f.store.names(), ["bob"]);
  assert.equal(f.store.directoryProfiles.get("bob").uid, "uid_bob");
  assert.equal(f.store.identityConflicts[0].profiles.length, 2);
});
test("match scan paginates without a custom field index and still returns the newest records", async () => {
  const f = storeFixture();
  for (let i = 0; i < 501; i++)
    f.docs.set("users/uid_bob/matches/z" + String(i).padStart(4, "0"), {
      timestamp: 1000 + i,
    });
  const records = await f.store.recentMatches(100);
  assert.equal(records.length, 100);
  assert.equal(records[0].timestamp, 1500);
  assert.equal(f.queries.length, 2);
  assert.ok(f.queries[1].structuredQuery.startAt);
});
test("duplicate cleanup preserves the registered identity and removes only the selected UID", async () => {
  const f = storeFixture();
  f.docs.set("users/uid_old", { uid: "uid_old", username: "bob" });
  f.docs.set("users/uid_old/private/account", {
    email: "fictional@example.test",
  });
  f.docs.set("users/uid_kevin/friends/uid_old", {
    uid: "uid_old",
    username: "bob",
  });
  f.docs.set("users/uid_kevin/friendRequests/uid_old", {
    fromUid: "uid_old",
    toUid: "uid_kevin",
  });
  let progress;
  for (let i = 0; i < 8 && f.docs.has("users/uid_old"); i++) {
    try {
      await f.store.removeDuplicate("uid_old", "bob", progress, async (p) => {
        progress = structuredClone(p);
      });
    } catch (e) {
      assert.match(e.message, /batch|cleanup/);
    }
  }
  assert.equal(f.docs.has("users/uid_old"), false);
  assert.equal(f.docs.has("users/uid_old/private/account"), false);
  assert.equal(f.docs.get("usernames/bob").uid, "uid_bob");
  assert.equal(f.docs.has("users/uid_bob"), true);
  assert.equal(f.docs.has("users/uid_kevin/friendRequests/uid_old"), false);
  assert.equal(f.docs.has("adminTombstones/uid_old"), true);
  assert.equal(f.docs.get("users/uid_kevin/friends/uid_old").removed, true);
  await assert.rejects(
    () => f.store.removeDuplicate("uid_bob", "bob", null, async () => {}),
    (e) => e.status === 409,
  );
});
test("announcement outbox retries a failed broker delivery without generating another notice", async () => {
  const legacy = new FakeLegacy(),
    storage = new MemoryStorage();
  let outage = true,
    sent = [];
  const control = new AdminControl(
    { storage },
    {
      ALLOWED_ORIGIN: "https://fixture.test",
      PUBLIC_URL: "https://admin.fixture.test",
      TEST_LEGACY: legacy,
      TEST_BROKER: async (args) => {
        if (outage) throw Error("offline");
        sent.push(args);
      },
    },
  );
  const notice = {
    id: "same-notice",
    title: "Fixture",
    message: "Fixture only",
    active: true,
  };
  const result = await control.saveOperation(
    {
      operator: "fixture",
      operationId: "fixture-operation-12345",
      requestHash: "fixture",
    },
    "ANNOUNCEMENT",
    "ALL",
    { notice },
    null,
    notice,
  );
  assert.equal(result.status, 202);
  assert.equal(
    (await storage.get("operation:fixture-operation-12345")).status,
    "PENDING",
  );
  outage = false;
  await control.execute(await storage.get("operation:fixture-operation-12345"));
  assert.equal(
    (await storage.get("operation:fixture-operation-12345")).status,
    "SYNCED",
  );
  assert.equal(JSON.parse(sent[0].messages[0].payload).id, "same-notice");
});

test("setting only level to 100 also sets compatible XP for the next real match", () => {
  assert.deepEqual(validatePatch({ level: 100 }, { level: 1, xp: 0 }), {
    level: 100,
    xp: 40000,
  });
  assert.deepEqual(validatePatch({ level: 100 }, { level: 100, xp: 40100 }), {
    level: 100,
  });
});
test("Firebase policy carries exact reductions, null photos and history removals to the app", async () => {
  const f = storeFixture();
  const policy = {
    revision: 2,
    deletedMatches: ["round-2"],
    clearMatchesBefore: 150,
    fields: {
      gamesPlayed: { revision: 2, value: 1 },
      gamesWon: { revision: 2, value: 0 },
      avatarUrl: { revision: 2, value: null },
      avatarBase64: { revision: 2, value: null },
    },
  };
  await f.store.mirrorProfile(
    "bob",
    (await f.store.read("bob")).profile,
    policy,
  );
  const data = f.docs.get("users/uid_bob");
  assert.deepEqual(data.adminChanges, policy.fields);
  assert.deepEqual(data.adminDeletedMatches, ["round-2"]);
  assert.equal(data.adminClearMatchesBefore, 150);
  assert.equal(data.gamesPlayed, 1);
  assert.equal(data.avatarUrl, null);
  data.gamesPlayed = 2;
  await f.store.mirrorProfile(
    "bob",
    (await f.store.read("bob")).profile,
    policy,
  );
  assert.equal(
    f.docs.get("users/uid_bob").gamesPlayed,
    2,
    "Retry must preserve subsequent gameplay",
  );
});

test("an old client upload cannot hide a pending admin edit in fresh inspection", async () => {
  const f = storeFixture();
  Object.assign(f.docs.get("users/uid_bob"), {
    level: 1,
    adminRevision: 2,
    adminAppliedRevision: 0,
    adminChanges: {
      level: { revision: 2, value: 100 },
      gamesPlayed: { revision: 2, value: 1 },
    },
  });
  assert.equal((await f.store.read("bob")).profile.level, 100);
  assert.equal((await f.store.read("bob")).profile.gamesPlayed, 1);
  Object.assign(f.docs.get("users/uid_bob"), {
    level: 101,
    gamesPlayed: 2,
    adminAppliedRevision: 2,
  });
  assert.equal((await f.store.read("bob")).profile.level, 101);
  assert.equal((await f.store.read("bob")).profile.gamesPlayed, 2);
});
