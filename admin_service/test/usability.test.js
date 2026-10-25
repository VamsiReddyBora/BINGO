import test from "node:test";
import assert from "node:assert/strict";
import {
  SESSION_KEY,
  readSession,
  rememberSession,
  forgetSession,
} from "../../admin/session.js";
import {
  playerVersion,
  playerActivity,
  currentPresence,
  relativeTime,
  selectPlayers,
  playersCsv,
} from "../../admin/model.js";

function memory() {
  const data = new Map();
  return {
    data,
    getItem: (k) => data.get(k) ?? null,
    setItem: (k, v) => data.set(k, v),
    removeItem: (k) => data.delete(k),
  };
}
const session = () => ({
  token: "a".repeat(64),
  expiresAt: Date.now() + 60000,
  operator: "fixture",
  setupRequired: false,
  authMethod: "passkey",
});
test("only a validated passkey session is remembered for the current tab", () => {
  const store = memory(),
    value = session();
  assert.equal(rememberSession(value, store), true);
  assert.deepEqual(readSession(store), value);
  assert.equal(
    rememberSession(
      { ...value, setupRequired: true, authMethod: "setup" },
      store,
    ),
    false,
  );
  assert.equal(readSession(store), null);
});
test("expired, malformed, incomplete and recovery tokens do not restore a console", () => {
  for (const value of [
    "bad",
    JSON.stringify({ ...session(), token: "fake" }),
    JSON.stringify({ ...session(), expiresAt: 1 }),
    JSON.stringify({ ...session(), authMethod: "recovery" }),
    JSON.stringify({ ...session(), setupRequired: true }),
  ]) {
    const store = memory();
    store.setItem(SESSION_KEY, value);
    assert.equal(readSession(store), null);
    assert.equal(store.getItem(SESSION_KEY), null);
  }
});
test("session storage contains no player snapshot, recovery codes or action proof", () => {
  const store = memory();
  rememberSession(
    {
      ...session(),
      players: [{ username: "private" }],
      recoveryCodes: ["private"],
      proof: "secret",
    },
    store,
  );
  assert.deepEqual(Object.keys(JSON.parse(store.getItem(SESSION_KEY))).sort(), [
    "authMethod",
    "expiresAt",
    "operator",
    "setupRequired",
    "token",
  ]);
});
test("locking forgets admin sessions while preserving unrelated origin storage", () => {
  const store = memory();
  store.setItem("bingo-game-fixture", "keep");
  store.setItem("bingo_admin_session_v3", "old");
  rememberSession(session(), store);
  forgetSession(store);
  assert.equal(store.getItem(SESSION_KEY), null);
  assert.equal(store.getItem("bingo_admin_session_v3"), null);
  assert.equal(store.getItem("bingo-game-fixture"), "keep");
});
test("blocked or full browser storage does not break in-memory sign-in or logout", () => {
  const blocked = {
    getItem() {
      throw Error("blocked");
    },
    setItem() {
      throw Error("full");
    },
    removeItem() {
      throw Error("blocked");
    },
  };
  assert.equal(readSession(blocked), null);
  assert.equal(rememberSession(session(), blocked), false);
  assert.doesNotThrow(() => forgetSession(blocked));
});
test("reported app version and build come from the same source", () => {
  const row = {
    profile: {
      appVersion: "1.0.3",
      appVersionCode: 71,
      lastSeenTimestamp: 100,
    },
    presence: { appVersion: "1.0.4", appVersionCode: 74, observedAt: 200 },
  };
  assert.equal(playerVersion(row).label, "1.0.4 · Build 74");
  row.profile.lastSeenTimestamp = 300;
  assert.equal(playerVersion(row).label, "1.0.3 · Build 71");
});
test("a missing live build is never filled using an unrelated saved build", () => {
  const row = {
    profile: { appVersion: "1.0.3", appVersionCode: 71 },
    presence: { appVersion: "1.0.4", observedAt: 100 },
  };
  assert.equal(playerVersion(row).label, "1.0.4");
  assert.equal(playerVersion(row).build, null);
  assert.equal(playerVersion({}).label, "Not reported");
  assert.equal(
    playerVersion({ profile: { appVersionCode: 73 } }).label,
    "Build 73",
  );
});
test("presence expires without inventing offline or online activity", () => {
  const now = 100000,
    row = { presence: { status: "PLAYING", observedAt: now - 1000 } };
  assert.equal(currentPresence(row, true, now).status, "PLAYING");
  assert.equal(currentPresence(row, true, now + 20000).status, "UNKNOWN");
  assert.equal(currentPresence(row, false, now).status, "UNKNOWN");
  assert.equal(currentPresence({}, true, now).status, "UNKNOWN");
  assert.equal(
    currentPresence(
      { presence: { status: "ONLINE", observedAt: now + 120000 } },
      true,
      now,
    ).status,
    "UNKNOWN",
  );
  assert.equal(
    currentPresence(
      { presence: { status: "OFFLINE", observedAt: 1 } },
      true,
      now,
    ).status,
    "OFFLINE",
  );
});
test("missing activity and days-old activity have readable labels", () => {
  assert.equal(playerActivity({}).label, "No activity reported");
  assert.equal(relativeTime(1000, 1000 + 2 * 86400000), "2d ago");
  assert.equal(relativeTime(0), "Not reported");
});
test("a recent profile sync is labelled separately from confirmed live activity", () => {
  const row = {
    presence: { observedAt: 1000 },
    profile: { lastSeenTimestamp: 5000 },
  };
  assert.equal(playerActivity(row, 6000).label, "Profile synced 1s ago");
  assert.equal(
    playerActivity({ ...row, presence: { observedAt: 5500 } }, 6000).source,
    "MQTT status",
  );
  assert.equal(
    playerActivity({ profile: { lastSeenTimestamp: 1e15 } }, 6000).at,
    0,
  );
});
test("build and sync filters combine with name and status filters", () => {
  const rows = [
    {
      username: "fixture-a",
      profile: { appVersion: "1.0.3", appVersionCode: 73 },
      presence: { status: "ONLINE" },
      sync: { status: "WAITING_APP" },
    },
    {
      username: "fixture-b",
      profile: { appVersionCode: 72 },
      presence: { status: "OFFLINE" },
      sync: { status: "APPLIED" },
    },
    {
      username: "fixture-c",
      profile: {},
      presence: { status: "UNKNOWN" },
      sync: { status: "PENDING_CLOUD" },
    },
  ];
  assert.deepEqual(
    selectPlayers(rows, {
      build: "73",
      sync: "WAITING",
      status: "ONLINE",
      query: "fixture",
    }).map((r) => r.username),
    ["fixture-a"],
  );
  assert.deepEqual(
    selectPlayers(rows, { build: "UNKNOWN", sync: "WAITING" }).map(
      (r) => r.username,
    ),
    ["fixture-c"],
  );
  assert.deepEqual(
    selectPlayers(rows, { sort: "build" }).map((r) => r.username),
    ["fixture-a", "fixture-b", "fixture-c"],
  );
});
test("CSV exports app build and version using the same report as the UI", () => {
  const csv = playersCsv([
    {
      username: "fixture",
      profile: { appVersion: "1.0.3", appVersionCode: 71 },
      presence: { appVersion: "1.0.4", appVersionCode: 74, observedAt: 1000 },
    },
  ]);
  assert.match(csv, /"App version","App build"/);
  assert.match(csv, /"1.0.4","74"/);
  assert.doesNotMatch(csv, /"71"/);
});
test("username searches accept the advertised @ prefix", () => {
  const rows = [
    { username: "fixture", profile: { displayName: "Original player" } },
  ];
  assert.equal(selectPlayers(rows, { query: "@FIXTURE" }).length, 1);
  assert.equal(selectPlayers(rows, { query: "@missing" }).length, 0);
});
