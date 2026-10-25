import test from "node:test";
import assert from "node:assert/strict";
import {
  buildMigrationPlan,
  executeMigrationAccount,
  FirebaseMigrationStore,
  migrationDigest,
} from "../migration.js";
import { toValue } from "../legacy.js";
function fixture(name = "fixture", uid = "google_123456789012345678901") {
  const profile = {
    uid,
    username: name,
    displayName: "Original player",
    provider: "GOOGLE",
    email: "private@fictional.test",
    gamesPlayed: 35,
    gamesWon: 1,
    currentStreak: 0,
    bestStreak: 1,
    level: 3,
    xp: 1200,
    avatarBase64: "photo",
  };
  const backup = {
    profile,
    settings: { soundEnabled: false, darkTheme: true },
    matchHistory: [
      {
        id: "match-1",
        mode: "ONLINE",
        timestamp: 10,
        didWin: true,
        boardSize: 5,
      },
    ],
    lastBackupTimestamp: 100,
  };
  return {
    username: name,
    profile,
    backup,
    backupError: false,
    legacyFriends: [],
    pendingRequests: null,
  };
}
const plan = (rows = [fixture()], rest = {}) =>
  buildMigrationPlan({
    rows,
    users: [],
    indexes: [],
    tombstones: [],
    at: 1000,
    ...rest,
  });
test("complete verified account migrates profile, original UID, statistics, private settings, email and history", async () => {
  const p = await plan();
  assert.equal(p.accounts.length, 1);
  const a = p.accounts[0];
  assert.equal(a.profile.gamesPlayed, 35);
  assert.equal(a.profile.level, 3);
  assert.equal(a.uid, fixture().profile.uid);
  assert.equal(a.profile.adminRevision, 0);
  assert.ok(a.profile.legacyMigration.id);
  assert.equal(a.profile.lastSeenTimestamp, 100);
  assert.equal(a.profile.email, undefined);
  assert.equal(a.profile.settings, undefined);
  assert.equal(
    a.documents.find((x) => x.path.endsWith("/private/account")).data.email,
    "private@fictional.test",
  );
  assert.equal(
    a.documents.find((x) => x.path.endsWith("/private/settings")).data
      .soundEnabled,
    false,
  );
  assert.equal(
    JSON.parse(
      a.documents.find((x) => x.path.endsWith("/private/legacyMigrationBackup"))
        .data.originalBackupJson,
    ).matchHistory.length,
    1,
  );
  assert.equal(a.matches, 1);
  assert.ok(a.documents.some((x) => x.path === "usernames/fixture"));
});
test("existing Firebase data and explicit deletion markers are skipped without replacing statistics", async () => {
  const r = fixture();
  const p = await plan([r], {
    users: [{ ...r.profile, gamesPlayed: 100, adminRevision: 4 }],
  });
  assert.equal(p.accounts.length, 0);
  assert.equal(p.skipped[0].reason, "EXISTING_FIREBASE_PROFILE");
  const removed = await plan([r], {
    tombstones: [{ uid: "another-uid", username: "fixture" }],
  });
  assert.equal(removed.accounts.length, 0);
  assert.equal(removed.skipped[0].reason, "DELETED_ACCOUNT");
  const byUid = await plan([r], {
    deleted: [{ uid: r.profile.uid, username: "oldname" }],
  });
  assert.equal(byUid.skipped[0].reason, "DELETED_ACCOUNT");
});
test("missing backups and account identity conflicts never produce zero-stat or guessed accounts", async () => {
  const r = fixture();
  assert.equal((await plan([{ ...r, backup: null }])).unreadable.length, 1);
  assert.equal(
    (
      await plan([r], {
        indexes: [{ username: "fixture", uid: "someone-else" }],
      })
    ).conflicts.length,
    1,
  );
  assert.equal(
    (
      await plan([
        {
          ...r,
          backup: { ...r.backup, profile: { ...r.profile, uid: "other" } },
        },
      ])
    ).conflicts.length,
    1,
  );
});
test("migration identity is deterministic across reruns and ignores JSON object key ordering", async () => {
  const first = await plan(),
    second = await plan();
  assert.equal(first.accounts[0].migration.id, second.accounts[0].migration.id);
  assert.equal(
    await migrationDigest({ a: 1, b: 2 }),
    await migrationDigest({ b: 2, a: 1 }),
  );
});
test("older WIN/LOSS history becomes current didWin format without changing career totals", async () => {
  const r = fixture();
  r.backup.matchHistory = [
    {
      id: "a",
      timestamp: 10,
      result: "WIN",
      roomCode: "ROOM",
      myLinesCompleted: 5,
    },
    { id: "b", timestamp: 20, result: "LOSS", roomCode: "ROOM" },
  ];
  const a = (await plan([r])).accounts[0];
  const docs = a.documents.filter((x) => x.path.includes("/matches/"));
  assert.equal(docs[0].data.didWin, true);
  assert.equal(docs[0].data.linesCompleted, 5);
  assert.equal(docs[1].data.didWin, false);
  assert.equal(a.profile.gamesPlayed, 35);
});
test("source archives preserve unknown original data and invalid-device photo paths do not leak publicly", async () => {
  const r = fixture();
  r.profile.avatarBase64 = null;
  r.profile.avatarUrl = "/data/user/private/photo.png";
  r.backup.profile = { ...r.profile };
  r.backup.unrecognized = {
    nested: [
      [1, 2],
      [3, 4],
    ],
  };
  const a = (await plan([r])).accounts[0];
  assert.equal(a.profile.avatarUrl, null);
  const archive = a.documents.find((x) =>
    x.path.endsWith("/private/legacyMigrationBackup"),
  );
  assert.deepEqual(
    JSON.parse(archive.data.originalBackupJson).unrecognized,
    r.backup.unrecognized,
  );
});
test("friends resolve only to verified canonical identities and deleted players stay excluded", async () => {
  const r = fixture();
  r.legacyFriends = [
    { username: "friend", uid: "friend-uid" },
    { username: "removed", uid: "gone-uid" },
    { username: "unknown" },
  ];
  const a = (
    await plan([r], {
      users: [{ uid: "friend-uid", username: "friend", displayName: "Friend" }],
      indexes: [{ username: "friend", uid: "friend-uid" }],
      tombstones: [{ uid: "gone-uid", username: "removed" }],
    })
  ).accounts[0];
  assert.equal(a.friends, 1);
  assert.equal(
    a.documents.find((x) => x.path.includes("/friends/")).data.uid,
    "friend-uid",
  );
  assert.ok(a.warnings.some((x) => x.includes("@unknown")));
});
test("guest and older provider identities preserve original IDs without inventing a Google account", async () => {
  const r = fixture("guest", "guest_original");
  r.profile.provider = "GUEST";
  const a = (await plan([r])).accounts[0];
  assert.equal(a.uid, "guest_original");
  assert.equal(a.profile.provider, "GUEST");
  delete r.profile.provider;
  r.profile.uid = "google_old_play_games_identifier";
  const older = (await plan([r])).accounts[0];
  assert.equal(older.profile.provider, "PLAY_GAMES");
  assert.ok(older.warnings.length);
});
class MemoryImport {
  constructor(docs = {}) {
    this.docs = new Map(Object.entries(docs));
    this.commits = 0;
    this.closed = 0;
  }
  async begin() {
    return "transaction";
  }
  async get(p) {
    return this.docs.get(p) || null;
  }
  async find(c, k, v) {
    return [...this.docs]
      .filter(
        ([p, d]) =>
          p.split("/").length === 2 && p.startsWith(c + "/") && d[k] === v,
      )
      .map(([, d]) => d);
  }
  async commit(tx, documents) {
    assert.equal(tx, "transaction");
    for (const d of documents) assert.ok(!this.docs.has(d.path));
    for (const d of documents) this.docs.set(d.path, d.data);
    this.commits++;
  }
  async rollback() {
    this.closed++;
  }
}
test("actual import executor creates atomically once and reruns without overwriting a new game", async () => {
  const a = (await plan()).accounts[0],
    store = new MemoryImport();
  assert.equal((await executeMigrationAccount(store, a)).status, "IMPORTED");
  store.docs.get("users/" + a.uid).gamesPlayed = 50;
  assert.equal(
    (await executeMigrationAccount(store, a)).status,
    "ALREADY_IMPORTED",
  );
  assert.equal(store.docs.get("users/" + a.uid).gamesPlayed, 50);
  assert.equal(store.commits, 1);
});
test("concurrent sign-in, deletion, identity conflict or child removal marker blocks the whole import", async () => {
  const a = (await plan()).accounts[0];
  for (const [docs, status] of [
    [
      { ["users/" + a.uid]: { uid: a.uid, gamesPlayed: 9 } },
      "SKIPPED_CURRENT_PROFILE",
    ],
    [{ ["adminTombstones/" + a.uid]: { uid: a.uid } }, "SKIPPED_DELETED"],
    [
      { "adminTombstones/other": { uid: "other", username: a.username } },
      "SKIPPED_DELETED",
    ],
    [{ "usernames/fixture": { uid: "other" } }, "CONFLICT_USERNAME"],
    [
      { ["users/" + a.uid + "/matches/match-1"]: { id: "match-1" } },
      "CONFLICT_EXISTING_LINKED_RECORD",
    ],
  ]) {
    const store = new MemoryImport(docs);
    assert.equal((await executeMigrationAccount(store, a)).status, status);
    assert.equal(store.commits, 0);
    assert.equal(store.closed, 1);
  }
});
test("Firebase REST adapter sends real transaction ID and create-only preconditions", async () => {
  const calls = [],
    prefix = "projects/fixture/databases/(default)/documents/";
  const legacy = {
    firestoreUrl: () => "https://firestore.googleapis.com/v1/" + prefix,
    token: async () => "fixture-token",
    transport: async (url, init) => {
      calls.push({ url, init });
      if (url.endsWith(":beginTransaction"))
        return Response.json({ transaction: "tx" });
      if (url.includes("?transaction="))
        return new Response("", { status: 404 });
      if (url.endsWith(":runQuery")) return Response.json([]);
      if (url.endsWith(":commit")) return Response.json({ writeResults: [{}] });
      return Response.json({});
    },
  };
  const store = new FirebaseMigrationStore(legacy),
    tx = await store.begin();
  assert.equal(await store.get("users/fixture", tx), null);
  await store.commit(tx, [{ path: "users/fixture", data: { uid: "fixture" } }]);
  const commit = JSON.parse(
    calls.find((c) => c.url.endsWith(":commit")).init.body,
  );
  assert.equal(commit.transaction, "tx");
  assert.deepEqual(commit.writes[0].currentDocument, { exists: false });
  assert.equal(commit.writes[0].update.name, prefix + "users/fixture");
  assert.deepEqual(commit.writes[0].update.fields.uid, toValue("fixture"));
});

test("a friend deleted after the preview cannot be reintroduced by importing an older roster", async () => {
  const r = fixture();
  r.legacyFriends = [{ username: "peer", uid: "peer-uid" }];
  const a = (
    await plan([r], {
      users: [{ uid: "peer-uid", username: "peer", displayName: "Peer" }],
      indexes: [{ username: "peer", uid: "peer-uid" }],
    })
  ).accounts[0];
  const store = new MemoryImport({
    "adminTombstones/peer-uid": { uid: "peer-uid", username: "peer" },
  });
  assert.equal(
    (await executeMigrationAccount(store, a)).status,
    "SKIPPED_DELETED_FRIEND_REPLAN_REQUIRED",
  );
  assert.equal(store.commits, 0);
});

test("missing legacy lookup is restored only for its verified original backup", async () => {
  const { restoreLegacyLookup } = await import("../migration.js");
  const row = { ...fixture(), binId: "original-bin" };
  let value = null,
    writes = 0;
  const legacy = {
    get: async () => value,
    backup: async () => row.backup,
    set: async (k, v) => {
      assert.equal(k, "user_fixture");
      value = v;
      writes++;
    },
  };
  assert.equal(
    (await restoreLegacyLookup(legacy, row)).status,
    "MISSING_LOOKUP_RESTORED",
  );
  assert.equal(writes, 1);
  assert.equal(
    (await restoreLegacyLookup(legacy, row)).status,
    "EXISTING_LOOKUP_PRESERVED",
  );
  assert.equal(writes, 1);
});
test("existing or concurrently changed compatibility lookups are not replaced", async () => {
  const { restoreLegacyLookup } = await import("../migration.js");
  const row = { ...fixture(), binId: "original-bin" };
  let writes = 0;
  const conflict = {
    get: async () => "other-bin",
    backup: async () => ({ profile: { uid: "another-owner" } }),
    set: async () => writes++,
  };
  assert.equal(
    (await restoreLegacyLookup(conflict, row)).status,
    "CONFLICT_EXISTING_LEGACY_OWNER",
  );
  assert.equal(writes, 0);
  let calls = 0;
  const changed = {
    get: async () => (++calls === 1 ? null : "other-bin"),
    backup: async () => row.backup,
    set: async () => writes++,
  };
  assert.equal(
    (await restoreLegacyLookup(changed, row)).status,
    "LOOKUP_CHANGED_RETRY_REQUIRED",
  );
  assert.equal(writes, 0);
});
