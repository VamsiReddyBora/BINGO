import test from "node:test";
import assert from "node:assert/strict";
import { LegacyStore, toValue, fromValue } from "../legacy.js";
import { encodeLegacy, decodeLegacy } from "../model.js";

function fixture() {
  const profile = {
    uid: "guest_fixture_kevin",
    username: "kevin",
    displayName: "Kevin",
    gamesPlayed: 5,
    gamesWon: 2,
    adminRevision: 0,
  };
  const kv = new Map([
    ["user_directory", "kevin,stuart"],
    ["user_kevin", "deadbeef1234"],
    ["ava_kevin", "photo1234"],
    ["reg_kevin", encodeLegacy(profile)],
    ["friends_stuart", encodeLegacy([{ uid: profile.uid, username: "kevin" }])],
  ]);
  const bins = new Map([
    [
      "deadbeef1234",
      {
        profile,
        settings: { soundEnabled: false },
        matchHistory: Array.from({ length: 34 }, (_, i) => ({
          id: "m" + i,
          timestamp: i + 1,
        })),
      },
    ],
    ["photo1234", { avatar: "/9j/fictional" }],
  ]);
  const docs = new Map([
    ["users/" + profile.uid, profile],
    ["usernames/kevin", { uid: profile.uid }],
    [
      "users/guest_fixture_stuart",
      { uid: "guest_fixture_stuart", username: "stuart" },
    ],
    [
      "users/guest_fixture_stuart/friends/" + profile.uid,
      { uid: profile.uid, username: "kevin", removed: false },
    ],
  ]);
  for (let i = 0; i < 34; i++)
    docs.set("users/" + profile.uid + "/matches/m" + i, {
      id: "m" + i,
      timestamp: i + 1,
    });
  let failWrite = false,
    created = 0;
  const requests = [];
  const document = (key, data) => ({
    name: "projects/fixture/databases/(default)/documents/" + key,
    fields: Object.fromEntries(
      Object.entries(data).map(([k, v]) => [k, toValue(v)]),
    ),
  });
  const response = (data, status = 200) =>
    new Response(JSON.stringify(data), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  const transport = async function (url, options = {}) {
    assert.equal(
      this,
      undefined,
      "Network function must not receive LegacyStore as its this receiver",
    );
    const u = new URL(url),
      method = options.method || "GET";
    requests.push({ host: u.hostname, method, path: u.pathname });
    if (failWrite && method !== "GET")
      return response({ error: "fixture offline" }, 503);
    if (u.hostname === "kv.fixture") {
      const key = decodeURIComponent(u.pathname.split("/").pop());
      if (method === "GET") return response(kv.get(key) ?? "");
      kv.set(key, u.searchParams.get("value"));
      return response(true);
    }
    if (u.hostname === "extendsclass.com") {
      const id = u.pathname.split("/").pop();
      if (method === "POST") {
        const newId = "created_" + ++created;
        bins.set(newId, JSON.parse(options.body));
        return response({ id: newId });
      }
      if (method === "DELETE") {
        bins.delete(id);
        return response({ ok: true });
      }
      if (!bins.has(id)) return response({}, 404);
      if (method === "PUT") {
        bins.set(id, JSON.parse(options.body));
        return response({ ok: true });
      }
      return response(bins.get(id));
    }
    if (u.hostname === "firestore.googleapis.com") {
      const key = decodeURIComponent(u.pathname.split("/documents/")[1] || "");
      if (u.pathname.endsWith("documents:runQuery")) {
        const uid = JSON.parse(options.body).structuredQuery.where.fieldFilter
          .value.stringValue;
        return response(
          [...docs]
            .filter(
              ([key, data]) => key.includes("/friends/") && data.uid === uid,
            )
            .map(([key, data]) => ({ document: document(key, data) })),
        );
      }
      if (method === "PATCH") {
        const input = JSON.parse(options.body);
        docs.set(key, {
          ...docs.get(key),
          ...Object.fromEntries(
            Object.entries(input.fields).map(([k, v]) => [k, fromValue(v)]),
          ),
        });
        return response(document(key, docs.get(key)));
      }
      if (method === "DELETE") {
        docs.delete(key);
        return response({});
      }
      if (key.split("/").length % 2 === 1)
        return response({
          documents: [...docs]
            .filter(
              ([k]) =>
                k.startsWith(key + "/") &&
                !k.slice(key.length + 1).includes("/"),
            )
            .map(([k, v]) => document(k, v)),
        });
      return docs.has(key)
        ? response(document(key, docs.get(key)))
        : response({}, 404);
    }
    throw Error("Unexpected transport host");
  };
  const store = new LegacyStore(
    {
      LEGACY_API_URL: "https://kv.fixture/api/KeyVal",
      LEGACY_APP_KEY: "fixture",
      SERVICE_ACCOUNT_JSON: { project_id: "fixture" },
    },
    transport,
  );
  store.token = async () => "fixture-only";
  return {
    store,
    kv,
    bins,
    docs,
    requests,
    profile,
    get created() {
      return created;
    },
    set failWrite(v) {
      failWrite = v;
    },
  };
}

test("legacy network functions are invoked without an incorrect receiver", async () => {
  const f = fixture();
  assert.deepEqual(await f.store.names(), ["kevin", "stuart"]);
});
test("backup identifiers that also resemble base64 remain literal identifiers", async () => {
  const f = fixture(),
    row = await f.store.read("kevin");
  assert.equal(row.binId, "deadbeef1234");
  assert.equal(row.profile.gamesPlayed, 5);
  assert.equal(row.backupError, false);
});
test("profile mirroring preserves settings and match logs and updates existing photo bin", async () => {
  const f = fixture(),
    row = await f.store.read("kevin");
  await f.store.mirrorProfile("kevin", {
    ...row.profile,
    displayName: "New",
    avatarBase64: "/9j/updated",
    adminRevision: 1,
  });
  const backup = f.bins.get("deadbeef1234");
  assert.equal(backup.settings.soundEnabled, false);
  assert.equal(backup.matchHistory.length, 34);
  assert.equal(backup.profile.adminRevision, 1);
  assert.equal(f.bins.get("photo1234").avatar, "/9j/updated");
  assert.equal(f.created, 0);
});
test("failed legacy write propagates instead of confirming success", async () => {
  const f = fixture();
  f.failWrite = true;
  await assert.rejects(() => f.store.set("user_directory", ""), /rejected/);
  assert.equal(f.kv.get("user_directory"), "kevin,stuart");
});
test("large account cleanup checkpoints progress and erases profile, photo, history and reverse roster", async () => {
  const f = fixture();
  let progress = null,
    finished = false;
  for (let i = 0; i < 12 && !finished; i++)
    try {
      await f.store.deleteAccount(
        "kevin",
        f.profile.uid,
        "deadbeef1234",
        progress,
        async (p) => {
          progress = structuredClone(p);
        },
      );
      finished = true;
    } catch (error) {
      assert.equal(error.status, 503);
      assert.match(error.message, /continuing/);
    }
  assert.ok(finished);
  assert.equal(progress.stage, "done");
  assert.equal(f.bins.has("deadbeef1234"), false);
  assert.equal(f.bins.has("photo1234"), false);
  assert.equal(f.docs.has("users/" + f.profile.uid), false);
  assert.equal(
    [...f.docs.keys()].some((k) =>
      k.startsWith("users/" + f.profile.uid + "/"),
    ),
    false,
  );
  assert.ok(f.docs.has("adminTombstones/" + f.profile.uid));
  assert.equal(f.kv.get("user_directory"), "stuart");
  assert.equal(f.kv.get("user_kevin"), "");
  assert.deepEqual(decodeLegacy(f.kv.get("friends_stuart")), []);
});

test("Firestore fallback uses an existing identity and never invents an absent account", async () => {
  const f = fixture();
  f.kv.set("user_kevin", "");
  f.kv.set("reg_kevin", "");
  const row = await f.store.read("kevin");
  assert.equal(row.profile.uid, f.profile.uid);
  assert.equal(row.backupError, true);
  await assert.rejects(() => f.store.read("missing_player"), /verified/);
});
test("failed backup pointer read cannot authorize replacing history with an empty backup", async () => {
  const f = fixture(),
    get = f.store.get.bind(f.store);
  f.store.get = async (key) => {
    if (key === "user_kevin") throw Error("offline");
    return get(key);
  };
  const row = await f.store.read("kevin");
  assert.equal(row.profile.uid, f.profile.uid);
  assert.equal(row.backupError, true);
  await assert.rejects(
    () =>
      f.store.mirrorProfile("kevin", { ...row.profile, displayName: "New" }),
    /readable/,
  );
  assert.equal(f.bins.get("deadbeef1234").matchHistory.length, 34);
});
