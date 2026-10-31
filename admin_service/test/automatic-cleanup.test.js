import test from "node:test";
import assert from "node:assert/strict";
import { planRetainedCleanup, executeRetainedCleanup } from "../retained-cleanup.js";
import { AdminControl } from "../worker.js";
import { MemoryStorage, FakeLegacy } from "./fixtures.js";
import { AUTO_CLEANUP_INTERVAL_MS } from "../automatic-cleanup.js";

const now = 1793431800000;
const empty = { users: [], usernames: [], tombstones: [] };
function finished(seed = "123", age = 2 * 86400000) {
  const root = `bingo/emqx7/match/AUTO123/${seed}/`;
  const packet = (type, id) => JSON.stringify({ type, roomCode: "AUTO123", seed: 123,
    playerId: id, timestamp: now - age, lastPlayerActivityAt: now - age,
    isMatchFinished: true, winnerPlayerId: "a", boardNumbers: { a: [1], b: [1] },
  }).replace('"seed":123', '"seed":' + seed);
  return { [root + "command/1/a/PICK_NUMBER"]: packet("PICK_NUMBER", "a"),
    [root + "outcome/1/a/PICK_NUMBER"]: packet("PICK_NUMBER", "a"),
    [root + "state/a"]: packet("GAME_SYNC", "a"),
    [root + "state/b"]: packet("GAME_SYNC", "b") };
}
for (const seed of ["-1447092214941116159", "1441841867804559283", "-9223372036854775808", "9223372036854775807"]) {
  test("MC-001 production planner recognizes exact signed Kotlin Long seed " + seed, async () => {
    const p = await planRetainedCleanup(finished(seed), empty, now);
    assert.equal(p.batchRecords, 4);
  });
}
test("MC-001 adjacent 64-bit seeds that round to the same JS number cannot authorize cleanup", async () => {
  const r = finished("9223372036854775807");
  const t = Object.keys(r).find(t => t.endsWith("state/a"));
  r[t] = r[t].replace('"seed":9223372036854775807', '"seed":9223372036854775806');
  assert.equal((await planRetainedCleanup(r, empty, now)).batchRecords, 0);
});
test("MC-002 finished move receipts compact after ten minutes, final boards remain for a day", async () => {
  const p = await planRetainedCleanup(finished("-1447092214941116159", 11 * 60000), empty, now);
  assert.equal(p.batchRecords, 2);
  assert.equal(p.categories.finished_move_receipts, 2);
  assert.ok(p.groups[0].topics.every(t => !t.includes("/state/")));
});
test("MC-002 recent, unfinished, or disagreeing boards never permit move compaction", async () => {
  for (const change of [null, { isMatchFinished: false }, { winnerPlayerId: "b" }]) {
    const r = finished("123", change ? 11 * 60000 : 60000);
    if (change) { const t = Object.keys(r).find(t => t.endsWith("state/a")); r[t] = JSON.stringify({ ...JSON.parse(r[t]), ...change }); }
    assert.equal((await planRetainedCleanup(r, empty, now)).batchRecords, 0);
  }
});
test("MC-002 compaction archives exact raw 64-bit payloads and keeps final states", async () => {
  const r = finished("9223372036854775807", 11 * 60000), initial = structuredClone(r), archives = new Map();
  const store = { collection: async () => [], firestoreGet: async p => archives.get(p), firestoreSet: async (p, v) => archives.set(p, v) };
  const broker = async args => {
    if (args.guardedGroups) {
      const deleted = [];
      for (const g of args.guardedGroups) for (const t of g.topics) { delete r[t]; deleted.push(t); }
      return { cleanup: { acknowledged: deleted } };
    }
    return { retainedRecords: structuredClone(r) };
  };
  const p = await planRetainedCleanup(r, empty, now);
  const result = await executeRetainedCleanup(p, { store, broker, now: () => now });
  assert.equal(result.deletedConfirmed, 2);
  assert.equal(Object.keys(r).length, 2);
  assert.ok(Object.keys(r).every(t => t.includes("/state/")));
  const saved = JSON.parse([...archives.values()][0].recordsJson);
  for (const [t, raw] of Object.entries(saved)) assert.equal(raw, initial[t]);
});

async function automaticFixture({ enabled = true, failArchive = false, initial } = {}) {
  const storage = new MemoryStorage(), legacy = new FakeLegacy(), archives = new Map(), writes = [];
  const records = initial || finished("-1447092214941116159", 11 * 60000);
  legacy.firestoreGet = async p => archives.get(p);
  legacy.firestoreSet = async (p, v) => { if (failArchive) throw Error("unavailable"); archives.set(p, v); };
  const broker = async args => {
    if (args.guardedGroups) {
      const acknowledged = [];
      for (const g of args.guardedGroups) for (const t of g.topics) { delete records[t]; writes.push(t); acknowledged.push(t); }
      return { cleanup: { acknowledged, skipped: 0 } };
    }
    return { retainedRecords: structuredClone(records) };
  };
  let ready;
  const env = { PUBLIC_URL: "https://admin.test", ALLOWED_ORIGIN: "https://example.test",
    TEST_LEGACY: legacy, TEST_BROKER: broker, MQTT_AUTOCLEAN_ENABLED: String(enabled) };
  const control = new AdminControl({ storage, blockConcurrencyWhile: f => (ready = f()) }, env);
  await ready;
  return { control, storage, legacy, archives, writes, records };
}
test("MC-003 authorized service boots an alarm without an open admin browser or phone", async () => {
  const f = await automaticFixture();
  assert.ok(f.storage.alarmAt >= Date.now() - 1000);
  assert.equal(f.writes.length, 0);
  await f.control.alarm();
  assert.equal(f.writes.length, 2);
  assert.equal(f.archives.size, 1);
  const saved = await f.storage.get("mqtt-cleanup:automatic");
  assert.equal(saved.status, "OK");
  assert.equal(saved.result.automatic, true);
  assert.equal(saved.result.deletedConfirmed, 2);
  assert.ok(saved.nextRunAt >= Date.now() + AUTO_CLEANUP_INTERVAL_MS - 1000);
  assert.equal(f.storage.alarmAt, saved.nextRunAt);
});
test("MC-003 concurrent/repeated alarm delivery does not repeat the same batch", async () => {
  const f = await automaticFixture();
  await Promise.all([f.control.alarm(), f.control.alarm(), f.control.alarm()]);
  assert.equal(f.writes.length, 2);
  assert.equal(f.archives.size, 1);
});
test("MC-003 disabled deployment has no cleanup alarm or broker writes", async () => {
  const f = await automaticFixture({ enabled: false });
  await f.control.alarm();
  assert.equal(f.storage.alarmAt, 0);
  assert.equal(f.writes.length, 0);
  assert.equal(f.archives.size, 0);
});
test("MC-003 archive failure preserves messages and schedules a later fresh attempt", async () => {
  const f = await automaticFixture({ failArchive: true });
  await f.control.alarm();
  assert.equal(f.writes.length, 0);
  const saved = await f.storage.get("mqtt-cleanup:automatic");
  assert.equal(saved.status, "FAILED");
  assert.equal(f.storage.alarmAt, saved.nextRunAt);
});
test("MC-003 backlog is bounded at 200 deletions and continues a minute later", async () => {
  const r = finished("123", 2 * 86400000), raw = Object.values(r)[0];
  for (let i = 0; i < 220; i++) r[`bingo/emqx7/match/AUTO123/123/command/${i}/a/PICK_NUMBER`] = raw;
  const f = await automaticFixture({ initial: r });
  await f.control.alarm();
  assert.equal(f.writes.length, 200);
  assert.equal(Object.keys(f.records).filter(t => t.includes("/state/")).length, 2);
  const saved = await f.storage.get("mqtt-cleanup:automatic");
  assert.ok(saved.nextRunAt <= Date.now() + 61000);
});
test("MC-003 unattended mode never deletes Firebase account mirrors", async () => {
  const r = { "bingo/v3/registry/example": JSON.stringify({ uid: "u", username: "example" }) };
  const firebase = { users: [{ uid: "u", username: "example" }], usernames: [{ username: "example", uid: "u" }], tombstones: [] };
  assert.equal((await planRetainedCleanup(r, firebase, now)).batchRecords, 1);
  assert.equal((await planRetainedCleanup(r, firebase, now, { automatic: true })).batchRecords, 0);
});
