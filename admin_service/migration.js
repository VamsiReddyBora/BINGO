/** One-time import of verified backups. Previewing a plan never writes to storage. */
import { decodeLegacy, username } from "./model.js";
import { toValue } from "./legacy.js";

export const MIGRATION_SOURCE = "keyvalue-extendsclass";
const PROFILE_FIELDS = [
  "displayName",
  "avatarUrl",
  "avatarBase64",
  "gamesPlayed",
  "gamesWon",
  "currentStreak",
  "bestStreak",
  "level",
  "xp",
  "appVersion",
  "appVersionCode",
];
const HISTORY_FIELDS = [
  "mode",
  "opponentName",
  "didWin",
  "boardSize",
  "timestamp",
  "matchTitle",
  "isDraw",
  "opponentUid",
  "turnsTaken",
  "linesCompleted",
  "durationSeconds",
  "finalBoardNumbers",
  "markedNumbers",
];
function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === "object")
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((k) => [k, stable(value[k])]),
    );
  return value;
}
export async function migrationDigest(value) {
  const hash = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(JSON.stringify(stable(value))),
  );
  return [...new Uint8Array(hash)]
    .map((x) => x.toString(16).padStart(2, "0"))
    .join("");
}
const record = (value) =>
  value && typeof value === "object" && !Array.isArray(value);
const ownUid = (row) => row.uid || row._docId;
function validCount(value) {
  return Number.isSafeInteger(value) && value >= 0;
}
function roster(raw) {
  const value = decodeLegacy(raw);
  return Array.isArray(value) ? value.filter(record) : [];
}
async function history(raw, warnings) {
  const matches = new Map();
  for (const item of raw) {
    if (!record(item) || !validCount(item.timestamp) || item.timestamp <= 0) {
      warnings.push(
        "History entry without a usable timestamp retained in the private source archive",
      );
      continue;
    }
    const oldId = typeof item.id === "string" ? item.id : "";
    const id = /^[A-Za-z0-9_.-]{1,200}$/.test(oldId)
      ? oldId
      : "legacy-" + (await migrationDigest(item)).slice(0, 40);
    if (matches.has(id)) {
      if (
        (await migrationDigest(matches.get(id).original)) !==
        (await migrationDigest(item))
      )
        throw Error("Different history records share one match ID");
      warnings.push(
        "Duplicate identical history entry retained in the private source archive",
      );
      continue;
    }
    const data = {
      id,
      ...Object.fromEntries(
        HISTORY_FIELDS.filter((k) => item[k] !== undefined).map((k) => [
          k,
          item[k],
        ]),
      ),
    };
    if (typeof data.didWin !== "boolean" && typeof item.result === "string") {
      const result = item.result.toUpperCase();
      if (
        ![
          "WIN",
          "WON",
          "VICTORY",
          "LOSS",
          "LOST",
          "DEFEAT",
          "DRAW",
          "TIE",
        ].includes(result)
      )
        throw Error("Unknown legacy match result");
      data.didWin = ["WIN", "WON", "VICTORY"].includes(result);
      data.isDraw = ["DRAW", "TIE"].includes(result);
      data.mode ||= item.roomCode ? "ONLINE" : "Legacy";
      data.linesCompleted ??= item.myLinesCompleted;
      warnings.push(
        "Older match result converted to the current history format",
      );
    }
    if (typeof data.didWin !== "boolean")
      throw Error("A match result could not be verified");
    if (oldId !== id && oldId) data.legacyOriginalId = oldId;
    matches.set(id, {
      id,
      data: Object.fromEntries(
        Object.entries(data).filter(([, v]) => v !== undefined),
      ),
      original: item,
    });
  }
  return [...matches.values()].map(({ id, data }) => ({ id, data }));
}

export async function buildMigrationPlan({
  rows,
  users,
  indexes,
  tombstones,
  deleted = [],
  at = Date.now(),
}) {
  const removed = [...tombstones, ...deleted];
  const removedUids = new Set(removed.map(ownUid).filter(Boolean));
  const removedNames = new Set(removed.map((p) => p.username).filter(Boolean));
  const existingUids = new Set(users.map(ownUid));
  const reservedNames = new Map(
    indexes.map((p) => [p.username || p._docId, p.uid]),
  );
  const plan = {
    schema: 1,
    createdAt: at,
    accounts: [],
    skipped: [],
    conflicts: [],
    unreadable: [],
  };
  for (const row of rows) {
    const name = row.username;
    if (removedNames.has(name) || removedUids.has(row.profile?.uid)) {
      plan.skipped.push({ username: name, reason: "DELETED_ACCOUNT" });
      continue;
    }
    if (!row.backup || row.backupError || !record(row.backup.profile)) {
      plan.unreadable.push({
        username: name,
        reason: "No complete readable live backup; no empty account created",
      });
      continue;
    }
    try {
      const profile = row.profile,
        source = row.backup.profile;
      const uid = profile?.uid;
      if (
        username(name) !== username(source.username) ||
        source.uid !== uid ||
        !/^[A-Za-z0-9_-]{4,128}$/.test(uid || "")
      )
        throw Error("Backup identity does not match its registered account");
      if (existingUids.has(uid)) {
        plan.skipped.push({
          username: name,
          reason: "EXISTING_FIREBASE_PROFILE",
        });
        continue;
      }
      if (
        (reservedNames.has(name) && reservedNames.get(name) !== uid) ||
        users.some((p) => p.username === name && ownUid(p) !== uid)
      )
        throw Error("Username belongs to another Firebase identity");
      if (plan.accounts.some((p) => p.uid === uid || p.username === name))
        throw Error("Duplicate legacy identity needs review");
      for (const key of [
        "gamesPlayed",
        "gamesWon",
        "currentStreak",
        "level",
        "xp",
      ])
        if (!validCount(profile[key]))
          throw Error("Missing or invalid stored statistic: " + key);
      if (profile.level < 1 || profile.gamesWon > profile.gamesPlayed)
        throw Error("Stored statistics are inconsistent");
      const provider = profile.provider ?? "PLAY_GAMES"; // Original serialization default; never invent a Google identity.
      if (!["GOOGLE", "GUEST", "PLAY_GAMES"].includes(provider))
        throw Error("Unknown original account provider");
      if (provider === "GOOGLE" && !/^google_\d{21}$/.test(uid))
        throw Error("Google account identifier needs verification");
      if (provider === "GUEST" && !uid.startsWith("guest_"))
        throw Error("Guest account identifier needs verification");
      const warnings = [];
      if (provider !== "GOOGLE")
        warnings.push(
          "Original " +
            provider +
            " identity preserved; Google account ownership was not created or linked",
        );
      const digest = await migrationDigest({
        backup: row.backup,
        profile: row.profile,
        friends: row.legacyFriends,
        requests: row.pendingRequests,
      });
      const migration = {
        schema: 1,
        id: "legacy-v1-" + digest.slice(0, 32),
        source: MIGRATION_SOURCE,
        sourceSha256: digest,
        lastBackupTimestamp: Number(row.backup.lastBackupTimestamp) || 0,
        importedAt: at,
      };
      const publicProfile = {
        uid,
        username: name,
        provider,
        ...Object.fromEntries(
          PROFILE_FIELDS.filter((k) => profile[k] !== undefined).map((k) => [
            k,
            profile[k],
          ]),
        ),
        bestStreak: Math.max(profile.bestStreak || 0, profile.currentStreak),
        adminRevision: 0,
        legacyMigration: migration,
        lastSeenTimestamp:
          Number(profile.lastSeenTimestamp) || migration.lastBackupTimestamp,
      };
      if (
        !publicProfile.avatarBase64 &&
        publicProfile.avatarUrl &&
        !/^https:\/\//.test(publicProfile.avatarUrl)
      ) {
        publicProfile.avatarUrl = null;
        warnings.push(
          "Device-local photo path is unavailable; original retained in private source archive",
        );
      }
      const matches = await history(row.backup.matchHistory || [], warnings);
      const settings = record(row.backup.settings) ? row.backup.settings : {};
      const documents = [
        { path: "users/" + uid, data: publicProfile },
        {
          path: "users/" + uid + "/private/legacyMigrationBackup",
          data: {
            migration,
            originalBackupJson: JSON.stringify(row.backup),
            legacyFriendsJson: JSON.stringify(row.legacyFriends ?? null),
            legacyRequestsJson: JSON.stringify(row.pendingRequests ?? null),
          },
        },
        ...matches.map((m) => ({
          path: "users/" + uid + "/matches/" + m.id,
          data: m.data,
        })),
      ];
      if (Object.keys(settings).length)
        documents.push({
          path: "users/" + uid + "/private/settings",
          data: settings,
        });
      if (typeof profile.email === "string" && profile.email)
        documents.push({
          path: "users/" + uid + "/private/account",
          data: { email: profile.email },
        });
      if (!reservedNames.has(name))
        documents.push({
          path: "usernames/" + name,
          data: { uid, username: name, updatedAt: at },
        });
      plan.accounts.push({
        uid,
        username: name,
        provider,
        migration,
        profile: publicProfile,
        documents,
        friendSource: roster(row.legacyFriends),
        warnings: [...new Set(warnings)],
        matches: matches.length,
        settings: Object.keys(settings).length,
      });
    } catch (error) {
      plan.conflicts.push({ username: name, reason: error.message });
    }
  }
  const identities = new Map();
  for (const row of users) {
    const name = row.username,
      uid = ownUid(row);
    if (!name || removedNames.has(name) || removedUids.has(uid)) continue;
    if (
      reservedNames.get(name) === uid ||
      (!reservedNames.has(name) &&
        users.filter((p) => p.username === name).length === 1)
    )
      identities.set(name, { ...row, uid });
  }
  for (const account of plan.accounts)
    identities.set(account.username, account.profile);
  for (const account of plan.accounts) {
    const seen = new Set();
    account.friends = 0;
    for (const friend of account.friendSource) {
      let name;
      try {
        name = username(friend.username);
      } catch {
        account.warnings.push("Malformed friend retained in private archive");
        continue;
      }
      if (
        friend.removed ||
        removedNames.has(name) ||
        removedUids.has(friend.uid)
      )
        continue;
      const target = identities.get(name);
      if (
        !target ||
        (friend.uid && friend.uid !== target.uid) ||
        target.uid === account.uid
      ) {
        account.warnings.push(
          "Unverified friend @" + name + " retained in private archive",
        );
        continue;
      }
      if (seen.has(target.uid)) continue;
      seen.add(target.uid);
      account.documents.push({
        path: "users/" + account.uid + "/friends/" + target.uid,
        data: {
          uid: target.uid,
          username: name,
          displayName: target.displayName || name,
          avatarUrl: target.avatarBase64 || target.avatarUrl || null,
          removed: false,
        },
      });
      account.friends++;
    }
    delete account.friendSource;
    if (account.documents.length > 450)
      throw Error(
        "Account exceeds one safe atomic import batch: " + account.username,
      );
    for (const doc of account.documents)
      if (new TextEncoder().encode(JSON.stringify(doc.data)).length > 750_000)
        throw Error("Document exceeds safe import size: " + doc.path);
  }
  return plan;
}

/** The transaction reads both the identity reservation and deletion markers before creating data. */
export async function executeMigrationAccount(store, account) {
  const tx = await store.begin();
  let committed = false;
  try {
    const existing = await store.get("users/" + account.uid, tx);
    if (existing)
      return {
        status:
          existing.legacyMigration?.id === account.migration.id
            ? "ALREADY_IMPORTED"
            : "SKIPPED_CURRENT_PROFILE",
      };
    const index = await store.get("usernames/" + account.username, tx);
    if (index && index.uid !== account.uid)
      return { status: "CONFLICT_USERNAME" };
    if (await store.get("adminTombstones/" + account.uid, tx))
      return { status: "SKIPPED_DELETED" };
    if (
      (await store.find("adminTombstones", "username", account.username, tx))
        .length
    )
      return { status: "SKIPPED_DELETED" };
    if (
      (await store.find("users", "username", account.username, tx)).some(
        (p) => p.uid !== account.uid,
      )
    )
      return { status: "CONFLICT_USERNAME" };
    const documents = account.documents.filter(
      (doc) => !index || doc.path !== "usernames/" + account.username,
    );
    for (const doc of documents) {
      if (
        doc.path.includes("/friends/") &&
        (await store.get("adminTombstones/" + doc.data.uid, tx))
      )
        return { status: "SKIPPED_DELETED_FRIEND_REPLAN_REQUIRED" };
    }
    // Existing children or removal markers must never be replaced, even with a missing parent profile.
    for (const doc of documents)
      if (
        doc.path !== "users/" + account.uid &&
        (await store.get(doc.path, tx))
      )
        return { status: "CONFLICT_EXISTING_LINKED_RECORD" };
    await store.commit(tx, documents);
    committed = true;
    return { status: "IMPORTED", documents: documents.length };
  } finally {
    if (!committed) await store.rollback(tx).catch(() => {});
  }
}

/** REST adapter using the existing authenticated Firebase storage connection. */
export class FirebaseMigrationStore {
  constructor(legacy) {
    this.legacy = legacy;
  }
  async call(path, method, data) {
    const root = this.legacy.firestoreUrl("").replace(/\/$/, "");
    const response = await this.legacy.transport(root + path, {
      method,
      headers: {
        Authorization: "Bearer " + (await this.legacy.token()),
        "Content-Type": "application/json",
      },
      ...(data === undefined ? {} : { body: JSON.stringify(data) }),
    });
    if (response.status === 404) return null;
    const value = await response.json();
    if (!response.ok) {
      const error = Error(
        "Firebase import HTTP " +
          response.status +
          " " +
          (value.error?.status || ""),
      );
      error.status = response.status;
      throw error;
    }
    return value;
  }
  async begin() {
    return (
      await this.call(":beginTransaction", "POST", {
        options: { readWrite: {} },
      })
    ).transaction;
  }
  async get(path, tx) {
    const data = await this.call(
      "/" +
        path.split("/").map(encodeURIComponent).join("/") +
        "?transaction=" +
        encodeURIComponent(tx),
      "GET",
    );
    if (!data) return null;
    const { fromDocument } = await import("./legacy.js");
    return fromDocument(data);
  }
  async find(collection, field, value, tx) {
    const data = await this.call(":runQuery", "POST", {
      transaction: tx,
      structuredQuery: {
        from: [{ collectionId: collection }],
        where: {
          fieldFilter: {
            field: { fieldPath: field },
            op: "EQUAL",
            value: { stringValue: value },
          },
        },
        limit: 3,
      },
    });
    const { fromDocument } = await import("./legacy.js");
    return data.filter((r) => r.document).map((r) => fromDocument(r.document));
  }
  async commit(tx, documents) {
    const prefix = this.legacy
      .firestoreUrl("")
      .split("/v1/")[1]
      .replace(/\/$/, "");
    return this.call(":commit", "POST", {
      transaction: tx,
      writes: documents.map((doc) => ({
        update: {
          name: prefix + "/" + doc.path,
          fields: Object.fromEntries(
            Object.entries(doc.data)
              .filter(([, v]) => v !== undefined)
              .map(([k, v]) => [k, toValue(v)]),
          ),
        },
        currentDocument: { exists: false },
      })),
    });
  }
  async rollback(tx) {
    return this.call(":rollback", "POST", { transaction: tx });
  }
}

/** Restore a missing compatibility lookup for clients that still check the legacy username index. */
export async function restoreLegacyLookup(legacy, row) {
  const name = username(row.username),
    bin = row.binId;
  if (!/^[A-Za-z0-9_-]{1,80}$/.test(bin || ""))
    throw Error("Unverified source backup link");
  const current = await legacy.get("user_" + name);
  if (current) {
    const owner = await legacy.backup(current);
    if (owner.profile?.uid !== row.profile.uid)
      return { status: "CONFLICT_EXISTING_LEGACY_OWNER" };
    return { status: "EXISTING_LOOKUP_PRESERVED" };
  }
  const source = await legacy.backup(bin);
  if (
    source.profile?.uid !== row.profile.uid ||
    username(source.profile?.username) !== name
  )
    throw Error("Source lookup owner cannot be verified");
  // Recheck immediately before writing. The legacy API has no atomic create-only operation.
  if (await legacy.get("user_" + name))
    return { status: "LOOKUP_CHANGED_RETRY_REQUIRED" };
  await legacy.set("user_" + name, bin);
  if ((await legacy.get("user_" + name)) !== bin)
    throw Error("Restored lookup did not verify");
  return { status: "MISSING_LOOKUP_RESTORED" };
}
