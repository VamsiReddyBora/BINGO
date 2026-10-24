import { LegacyStore, fromDocument } from "./legacy.js";
import { username, requireThat, applyPolicy, encodeLegacy } from "./model.js";

// Firebase is the current app's profile/social store. Legacy backups are compatibility mirrors.
export class CurrentPlayerStore extends LegacyStore {
  directoryProfiles = new Map();
  extraCleanupCollections = ["friendRequests", "private"];
  async names() {
    const [profiles, tombstones, indexes] = await Promise.all([
      this.collection("users"),
      this.collection("adminTombstones"),
      this.collection("usernames"),
    ]);
    const deleted = new Set(tombstones.map((p) => p.uid || p._docId));
    const mapping = new Map(
      indexes.map((p) => [String(p.username || p._docId).toLowerCase(), p.uid]),
    );
    const groups = new Map();
    this.directoryProfiles.clear();
    this.identityConflicts = [];
    for (const raw of profiles) {
      if (!raw.username || deleted.has(raw.uid || raw._docId)) continue;
      let name;
      try {
        name = username(raw.username);
      } catch {
        continue;
      }
      const profile = this.project(raw, name);
      if (!groups.has(name)) groups.set(name, []);
      groups.get(name).push(profile);
    }
    this.storageAccountCount = profiles.length;
    for (const [name, group] of groups) {
      const selected =
        group.find((p) => p.uid === mapping.get(name)) ||
        [...group].sort(
          (a, b) =>
            (b.lastSeenTimestamp || 0) - (a.lastSeenTimestamp || 0) ||
            a.uid.localeCompare(b.uid),
        )[0];
      this.directoryProfiles.set(name, selected);
      if (
        group.length > 1 ||
        (mapping.has(name) && !group.some((p) => p.uid === mapping.get(name)))
      )
        this.identityConflicts.push({
          username: name,
          canonicalUid: mapping.get(name) || null,
          profiles: group,
        });
    }
    return [...this.directoryProfiles.keys()].sort();
  }
  project(raw, name) {
    const fields = [
      "uid",
      "displayName",
      "avatarUrl",
      "avatarBase64",
      "provider",
      "gamesPlayed",
      "gamesWon",
      "currentStreak",
      "bestStreak",
      "level",
      "xp",
      "lastSeenTimestamp",
      "appVersion",
      "adminRevision",
    ];
    return {
      ...Object.fromEntries(
        fields.filter((k) => raw[k] !== undefined).map((k) => [k, raw[k]]),
      ),
      uid: raw.uid || raw._docId,
      username: name,
    };
  }
  async read(name) {
    name = username(name);
    const index = await this.firestoreGet("usernames/" + name);
    const uid = index?.uid || this.directoryProfiles.get(name)?.uid;
    requireThat(uid, 404, "Player no longer exists in Firebase");
    const [raw, removed] = await Promise.all([
      this.firestoreGet("users/" + uid),
      this.firestoreGet("adminTombstones/" + uid),
    ]);
    requireThat(!removed, 410, "Player has been removed");
    requireThat(
      raw && username(raw.username) === name,
      404,
      "Player profile no longer exists",
    );
    return {
      username: name,
      profile: this.project({ ...raw, uid }, name),
      binId: null,
      backup: null,
      matchHistory: [],
      backupError: false,
      source: "FIREBASE",
      fetchedAt: Date.now(),
    };
  }
  async friends(name, uid) {
    return (await this.collection("users/" + uid + "/friends"))
      .filter((f) => !f.removed)
      .map((f) => ({ ...f, username: username(f.username) }));
  }
  async deletionSources(name, uid) {
    const [binId, avatarBinId] = await Promise.all([
      this.get("user_" + name),
      this.get("ava_" + name),
    ]);
    const bin =
      typeof binId === "string" &&
      /^[A-Za-z0-9_-]{1,80}$/.test(binId) &&
      binId !== "null"
        ? binId
        : null;
    const avatar =
      typeof avatarBinId === "string" &&
      /^[A-Za-z0-9_-]{1,80}$/.test(avatarBinId) &&
      avatarBinId !== "null"
        ? avatarBinId
        : null;
    if (bin) {
      const data = await this.backup(bin);
      requireThat(
        data.profile?.uid === uid,
        409,
        "The linked legacy backup belongs to a different identity; cleanup needs inspection",
      );
    }
    return { binId: bin, avatarBinId: avatar };
  }
  async scanGroup(collection, visit) {
    const endpoint = this.firestoreUrl("").replace(/\/$/, "") + ":runQuery";
    let cursor = null;
    for (let page = 0; page < 35; page++) {
      const query = {
        from: [{ collectionId: collection, allDescendants: true }],
        orderBy: [{ field: { fieldPath: "__name__" }, direction: "ASCENDING" }],
        limit: 500,
      };
      if (cursor)
        query.startAt = { values: [{ referenceValue: cursor }], before: false };
      const res = await this.request(endpoint, {
        method: "POST",
        headers: {
          Authorization: "Bearer " + (await this.token()),
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ structuredQuery: query }),
      });
      const documents = (await res.json())
        .filter((r) => r.document)
        .map((r) => r.document);
      for (const document of documents) visit(document);
      if (documents.length < 500) return;
      const next = documents.at(-1).name;
      requireThat(next !== cursor, 503, "Cloud record scan did not advance");
      cursor = next;
    }
    requireThat(
      false,
      503,
      "Too many cloud records for one overview. Inspect an individual player instead.",
    );
  }
  async friendReferences(uid) {
    const references = [];
    for (const collection of ["friends", "friendRequests"])
      await this.scanGroup(collection, (document) => {
        const data = fromDocument(document);
        if (data.uid === uid || data.fromUid === uid || data.toUid === uid)
          references.push(document.name.split("/documents/")[1]);
      });
    return [...new Set(references)];
  }
  async mirrorProfile(name, profile, policy) {
    const current = await this.read(name);
    requireThat(
      current.profile.uid === profile.uid,
      409,
      "Player identity changed",
    );
    const next = applyPolicy(current.profile, policy);
    const changed = Object.fromEntries(
      Object.entries(policy?.fields || {})
        .filter(([, v]) => v.revision > (current.profile.adminRevision || 0))
        .map(([k, v]) => [k, v.value]),
    );
    if (Object.hasOwn(changed, "avatarBase64"))
      changed.avatarUrl = changed.avatarBase64 || changed.avatarUrl || null;
    await this.firestoreSet("users/" + profile.uid, {
      ...changed,
      adminRevision: policy.revision,
    });
    // A compact lookup mirror cannot overwrite the canonical Firebase profile on an admin read.
    await this.set(
      "reg_" + name,
      encodeLegacy({
        ...next,
        avatarBase64: undefined,
        avatarUrl: next.avatarBase64 ? null : next.avatarUrl,
      }),
    );
    return next;
  }
  async removeDuplicate(uid, name, progress, checkpoint) {
    const index = await this.firestoreGet("usernames/" + name);
    requireThat(
      index?.uid && index.uid !== uid,
      409,
      "This identity is registered or has no verified replacement; use the normal player removal flow",
    );
    if (!progress) {
      const profile = await this.firestoreGet("users/" + uid);
      requireThat(
        profile && username(profile.username) === name,
        409,
        "The duplicate identity changed; refresh Storage audit",
      );
      progress = {
        references: await this.friendReferences(uid),
        cursor: 0,
        stage: 0,
      };
      await checkpoint(progress);
    }
    await this.firestoreSet("adminTombstones/" + uid, {
      uid,
      username: name,
      deletedAt: Date.now(),
      reason: "duplicate-identity-removal",
    });
    for (const path of progress.references.slice(
      progress.cursor,
      progress.cursor + 8,
    )) {
      if (path.includes("/friendRequests/")) await this.firestoreDelete(path);
      else
        await this.firestoreSet(path, {
          uid,
          username: name,
          removed: true,
          removalId: "admin-duplicate:" + uid,
        });
      progress.cursor++;
      await checkpoint(progress);
    }
    requireThat(
      progress.cursor >= progress.references.length,
      503,
      "Duplicate blocked; related records cleanup is continuing",
    );
    const collections = ["friends", "friendRequests", "matches", "private"];
    while (progress.stage < collections.length) {
      const collection = collections[progress.stage],
        docs = await this.collection("users/" + uid + "/" + collection);
      for (const doc of docs.slice(0, 15))
        await this.firestoreDelete(
          "users/" + uid + "/" + collection + "/" + doc._docId,
        );
      requireThat(
        docs.length <= 15,
        503,
        "Duplicate blocked; " + collection + " cleanup is continuing",
      );
      progress.stage++;
      await checkpoint(progress);
      if (progress.stage < collections.length)
        throw new Error("Continue cleanup in the next batch");
    }
    // Username index, retained presence and username-based backups belong to the registered player.
    await this.firestoreDelete("users/" + uid);
  }
  async recentMatches(limit = 100) {
    const matches = [];
    await this.scanGroup("matches", (document) => {
      const parts = document.name.split("/documents/")[1].split("/");
      const value = {
        ...fromDocument(document),
        id: decodeURIComponent(parts.at(-1)),
        ownerUid: decodeURIComponent(parts[1]),
      };
      const compare = (a, b) =>
        (Number(b.timestamp) || 0) - (Number(a.timestamp) || 0) ||
        a.id.localeCompare(b.id);
      let low = 0,
        high = matches.length;
      while (low < high) {
        const middle = (low + high) >>> 1;
        if (compare(value, matches[middle]) < 0) high = middle;
        else low = middle + 1;
      }
      matches.splice(low, 0, value);
      if (matches.length > limit) matches.length = limit;
    });
    return matches;
  }
}
