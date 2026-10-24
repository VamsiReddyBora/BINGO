import { SignJWT, importPKCS8 } from "jose";
import {
  Problem,
  requireThat,
  decodeLegacy,
  encodeLegacy,
  directory,
  username,
  mapLimit,
  applyPolicy,
} from "./model.js";
export class LegacyStore {
  constructor(env, transport = fetch) {
    this.env = env;
    this.transport = (...args) => transport(...args);
    this.access = null;
  }
  async request(url, options = {}) {
    let res;
    try {
      res = await this.transport(url, {
        ...options,
        signal: AbortSignal.timeout(8000),
      });
    } catch {
      throw new Problem(503, "Player storage is temporarily unavailable");
    }
    requireThat(res.ok, 503, "Player storage rejected the operation");
    return res;
  }
  async get(key) {
    const res = await this.request(
      `${this.env.LEGACY_API_URL}/GetValue/${this.env.LEGACY_APP_KEY}/${encodeURIComponent(key)}?_t=${Date.now()}`,
      { cache: "no-store" },
    );
    const raw = await res.text();
    try {
      const parsed = JSON.parse(raw);
      return typeof parsed === "string" ? parsed : raw;
    } catch {
      return raw;
    }
  }
  async set(key, value) {
    await this.request(
      `${this.env.LEGACY_API_URL}/UpdateValue/${this.env.LEGACY_APP_KEY}/${encodeURIComponent(key)}?value=${encodeURIComponent(value)}`,
      { method: "POST", body: "" },
    );
  }
  async names() {
    return directory(await this.get("user_directory"));
  }
  async createAccount(profile) {
    const name = username(profile.username);
    const existing = await this.names();
    const identity = await this.firestoreGet("users/" + profile.uid);
    requireThat(
      !identity || identity.username === name,
      409,
      "This player UID is already linked to a different username",
    );
    const pointer = await this.get("user_" + name);
    let previous = null;
    if (pointer) {
      previous = await this.backup(pointer);
      requireThat(
        previous?.profile?.uid === profile.uid,
        409,
        "Player username belongs to another identity",
      );
    }
    requireThat(
      !existing.includes(name) || previous,
      409,
      "Player username is already registered",
    );
    await this.writeBackup(
      name,
      pointer || null,
      previous || {
        profile,
        settings: {},
        matchHistory: [],
        lastBackupTimestamp: Date.now(),
      },
    );
    if (previous?.profile) profile = previous.profile;
    await this.set("reg_" + name, encodeLegacy(profile));
    await this.firestoreSet("users/" + profile.uid, profile);
    await this.firestoreSet("usernames/" + name, {
      uid: profile.uid,
      username: name,
      updatedAt: Date.now(),
    });
    const latest = await this.names();
    await this.set(
      "user_directory",
      [...new Set([...latest, name])].sort().join(","),
    );
  }
  async backup(bin) {
    requireThat(
      /^[a-zA-Z0-9_-]{1,80}$/.test(bin),
      503,
      "Player backup identifier is invalid",
    );
    return (
      await this.request("https://extendsclass.com/api/json-storage/bin/" + bin)
    ).json();
  }
  async read(name) {
    name = username(name);
    const reads = await Promise.allSettled([
      this.get("user_" + name),
      this.get("reg_" + name),
    ]);
    const rawBin = reads[0].status === "fulfilled" ? reads[0].value : null;
    const rawReg = reads[1].status === "fulfilled" ? reads[1].value : null;
    const reg = decodeLegacy(rawReg);
    let bin = typeof rawBin === "string" ? rawBin.trim() : null;
    if (!bin || bin === "null") bin = null;
    let backup = null,
      backupError = reads[0].status !== "fulfilled";
    if (bin) {
      try {
        backup = await this.backup(bin);
      } catch {
        backupError = true;
      }
    }
    let profile =
      backup?.profile ||
      (reg && typeof reg === "object" && !Array.isArray(reg) ? reg : null);
    if (!profile?.uid) {
      const mapped = await this.firestoreGet("usernames/" + name);
      if (mapped?.uid) {
        profile = await this.firestoreGet("users/" + mapped.uid);
        backupError = true;
      }
    }
    requireThat(
      profile && typeof profile.uid === "string" && profile.uid.length > 0,
      503,
      "Player profile could not be verified",
    );
    const avatarBinId =
      (await this.get("ava_" + name).catch(() => null))?.trim() || null;
    if (
      !profile.avatarBase64 &&
      (!profile.avatarUrl || !profile.avatarUrl.startsWith("https://")) &&
      avatarBinId
    ) {
      try {
        const photo = await this.backup(avatarBinId);
        if (photo?.avatar) profile = { ...profile, avatarBase64: photo.avatar };
      } catch {}
    }
    return {
      username: name,
      profile: { ...profile, username: name },
      binId: bin,
      avatarBinId,
      backup,
      backupError,
      matchHistory: Array.isArray(backup?.matchHistory)
        ? backup.matchHistory
        : [],
      fetchedAt: Date.now(),
    };
  }
  async writeBackup(name, bin, data) {
    if (bin) {
      const updated = await this.transport(
        "https://extendsclass.com/api/json-storage/bin/" + bin,
        {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(data),
          signal: AbortSignal.timeout(8000),
        },
      );
      if (updated.ok) return bin;
      requireThat(
        [404, 405, 501].includes(updated.status),
        503,
        "Could not update player backup",
      );
    }
    const res = await this.request(
      "https://extendsclass.com/api/json-storage/bin",
      {
        method: "POST",
        headers: { "Content-Type": "text/plain" },
        body: JSON.stringify(data),
      },
    );
    const created = await res.json();
    requireThat(
      typeof created.id === "string",
      503,
      "Could not save player backup",
    );
    await this.set("user_" + name, created.id);
    if (data.profile?.uid?.startsWith("google_"))
      await this.set("gid_" + data.profile.uid.slice(7), created.id);
    return created.id;
  }
  async mirrorProfile(name, profile, policy = null) {
    const current = await this.read(name);
    if (policy) profile = applyPolicy(current.profile, policy);
    requireThat(
      !current.backupError,
      503,
      "Backup must be readable before profile changes can sync",
    );
    await this.writeBackup(name, current.binId, {
      ...(current.backup || {}),
      profile,
      matchHistory: current.matchHistory,
      lastBackupTimestamp: Date.now(),
    });
    const compact = {
      ...profile,
      avatarBase64: undefined,
      avatarUrl:
        typeof profile.avatarUrl === "string" && profile.avatarUrl.length < 500
          ? profile.avatarUrl
          : null,
    };
    await this.set("reg_" + name, encodeLegacy(compact));
    await this.firestoreSet("users/" + profile.uid, {
      ...profile,
      avatarUrl: profile.avatarBase64 || profile.avatarUrl || null,
    });
    if (profile.avatarBase64) {
      let saved = false;
      if (current.avatarBinId) {
        const response = await this.transport(
          "https://extendsclass.com/api/json-storage/bin/" +
            current.avatarBinId,
          {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ avatar: profile.avatarBase64 }),
            signal: AbortSignal.timeout(8000),
          },
        );
        saved = response.ok;
        requireThat(
          saved || response.status === 404 || response.status === 405,
          503,
          "Photo synchronization is pending",
        );
      }
      if (!saved) {
        const res = await this.request(
          "https://extendsclass.com/api/json-storage/bin",
          {
            method: "POST",
            headers: { "Content-Type": "text/plain" },
            body: JSON.stringify({ avatar: profile.avatarBase64 }),
          },
        );
        const data = await res.json();
        requireThat(data.id, 503, "Avatar storage failed");
        await this.set("ava_" + name, data.id);
      }
    } else if (policy?.fields?.avatarBase64) await this.set("ava_" + name, "");
    return profile;
  }
  async token() {
    if (this.access && this.access.until > Date.now() + 60000)
      return this.access.value;
    const c =
      typeof this.env.SERVICE_ACCOUNT_JSON === "string"
        ? JSON.parse(this.env.SERVICE_ACCOUNT_JSON)
        : this.env.SERVICE_ACCOUNT_JSON;
    requireThat(
      c?.private_key && c?.client_email && c?.project_id,
      503,
      "Administrative backup access is not configured",
    );
    const now = Math.floor(Date.now() / 1000);
    const key = await importPKCS8(c.private_key, "RS256");
    const assertion = await new SignJWT({
      scope: "https://www.googleapis.com/auth/datastore",
    })
      .setProtectedHeader({ alg: "RS256" })
      .setIssuer(c.client_email)
      .setAudience("https://oauth2.googleapis.com/token")
      .setIssuedAt(now)
      .setExpirationTime(now + 3600)
      .sign(key);
    const res = await this.request("https://oauth2.googleapis.com/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }),
    });
    const data = await res.json();
    requireThat(
      data.access_token,
      503,
      "Administrative backup authentication failed",
    );
    this.access = {
      value: data.access_token,
      until: Date.now() + Math.min(data.expires_in || 3600, 3600) * 1000,
    };
    return data.access_token;
  }
  firestoreUrl(path) {
    const c =
      typeof this.env.SERVICE_ACCOUNT_JSON === "string"
        ? JSON.parse(this.env.SERVICE_ACCOUNT_JSON)
        : this.env.SERVICE_ACCOUNT_JSON;
    return (
      `https://firestore.googleapis.com/v1/projects/${c.project_id}/databases/(default)/documents/` +
      path.split("/").map(encodeURIComponent).join("/")
    );
  }
  async firestoreGet(path) {
    const res = await this.transport(this.firestoreUrl(path), {
      headers: { Authorization: "Bearer " + (await this.token()) },
      signal: AbortSignal.timeout(8000),
    });
    if (res.status === 404) return null;
    requireThat(res.ok, 503, "Could not read the player backup");
    return fromDocument(await res.json());
  }
  async firestoreSet(path, data) {
    const keys = Object.keys(data).filter((k) => data[k] !== undefined);
    const query = new URLSearchParams();
    keys.forEach((k) => query.append("updateMask.fieldPaths", k));
    await this.request(this.firestoreUrl(path) + "?" + query, {
      method: "PATCH",
      headers: {
        Authorization: "Bearer " + (await this.token()),
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        fields: Object.fromEntries(keys.map((k) => [k, toValue(data[k])])),
      }),
    });
  }
  async firestoreDelete(path) {
    const res = await this.transport(this.firestoreUrl(path), {
      method: "DELETE",
      headers: { Authorization: "Bearer " + (await this.token()) },
      signal: AbortSignal.timeout(8000),
    });
    requireThat(
      res.ok || res.status === 404,
      503,
      "Could not remove the player backup",
    );
  }
  async collection(path) {
    const all = [];
    let next = "";
    do {
      const q = new URLSearchParams({ pageSize: "300" });
      if (next) q.set("pageToken", next);
      const res = await this.request(this.firestoreUrl(path) + "?" + q, {
        headers: { Authorization: "Bearer " + (await this.token()) },
      });
      const data = await res.json();
      for (const doc of data.documents || [])
        all.push({
          ...fromDocument(doc),
          _docId: decodeURIComponent(doc.name.split("/").pop()),
        });
      next = data.nextPageToken || "";
      requireThat(
        all.length <= 20000,
        503,
        "Backup collection exceeds the safe page limit",
      );
    } while (next);
    return all;
  }
  async friends(name, uid) {
    const docs = await this.collection("users/" + uid + "/friends");
    const raw = decodeLegacy(await this.get("friends_" + name));
    const list = Array.isArray(raw) ? raw : [];
    const removed = new Set(
      docs.filter((d) => d.removed).flatMap((d) => [d.uid, d.username]),
    );
    const map = new Map();
    for (const f of [...list, ...docs.filter((d) => !d.removed)]) {
      const n = String(f.username || "")
        .toLowerCase()
        .replace(/^@/, "");
      if (n && !removed.has(n) && !removed.has(f.uid))
        map.set(n, { ...f, username: n });
    }
    return [...map.values()];
  }
  async mirrorFriend(a, b, present, operationId) {
    for (const [own, other] of [
      [a, b],
      [b, a],
    ]) {
      const path = "users/" + own.profile.uid + "/friends/" + other.profile.uid;
      const previous = await this.firestoreGet(path);
      const f = {
        uid: other.profile.uid,
        username: other.username,
        displayName: other.profile.displayName || other.username,
        avatarUrl: other.profile.avatarUrl || null,
      };
      await this.firestoreSet(path, {
        ...f,
        removed: !present,
        removalId: present ? previous?.removalId || "" : operationId,
        restoredRemovalId: present ? previous?.removalId || "" : "",
        adminUpdatedAt: Date.now(),
      });
      const roster = (await this.friends(own.username, own.profile.uid)).filter(
        (x) => x.username !== other.username,
      );
      if (present) roster.push(f);
      await this.set(
        "friends_" + own.username,
        encodeLegacy(
          roster.map((x) => ({
            uid: x.uid,
            username: x.username,
            displayName: x.displayName,
          })),
        ),
      );
    }
  }
  async deleteBin(bin) {
    requireThat(
      /^[a-zA-Z0-9_-]{1,80}$/.test(bin),
      503,
      "External backup identifier is invalid",
    );
    const res = await this.transport(
      "https://extendsclass.com/api/json-storage/bin/" + bin,
      { method: "DELETE", signal: AbortSignal.timeout(8000) },
    );
    requireThat(
      res.ok || res.status === 404,
      503,
      "External backup cleanup is pending",
    );
  }
  async friendReferences(uid) {
    const endpoint = this.firestoreUrl("").replace(/\/$/, "") + ":runQuery";
    const res = await this.request(endpoint, {
      method: "POST",
      headers: {
        Authorization: "Bearer " + (await this.token()),
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        structuredQuery: {
          from: [{ collectionId: "friends", allDescendants: true }],
          where: {
            fieldFilter: {
              field: { fieldPath: "uid" },
              op: "EQUAL",
              value: { stringValue: uid },
            },
          },
        },
      }),
    });
    return (await res.json())
      .filter((row) => row.document)
      .map((row) => row.document.name.split("/documents/")[1]);
  }
  async deleteAccount(
    name,
    uid,
    knownBin = null,
    progress = null,
    checkpoint = async () => {},
  ) {
    // Checkpoint bounded batches so even large histories fit Worker subrequest limits.
    let state = progress;
    await this.firestoreSet("adminTombstones/" + uid, {
      uid,
      username: name,
      deletedAt: Date.now(),
    });
    if (!state) {
      const existing = this.deletionSources
        ? await this.deletionSources(name, uid)
        : await this.read(name).catch(() => null);
      const references = await this.friendReferences(uid);
      state = {
        stage: "references",
        cursor: 0,
        references,
        bin: existing?.binId || knownBin,
        avatarBin: existing?.avatarBinId || null,
      };
      await checkpoint(state);
    }
    if (state.stage === "references") {
      for (const reference of state.references.slice(
        state.cursor,
        state.cursor + 5,
      )) {
        if (reference.includes("/friendRequests/")) {
          await this.firestoreDelete(reference);
          state.cursor++;
          await checkpoint(state);
          continue;
        }
        const ownerUid = reference.split("/")[1];
        await this.firestoreSet(reference, {
          uid,
          username: name,
          removed: true,
          removalId: "admin-account-delete:" + uid,
        });
        if (ownerUid !== uid) {
          const owner = await this.firestoreGet("users/" + ownerUid);
          if (owner?.username) {
            const raw = decodeLegacy(
              await this.get("friends_" + owner.username),
            );
            requireThat(
              Array.isArray(raw) || raw === null,
              503,
              "Related friends list could not be verified",
            );
            await this.set(
              "friends_" + owner.username,
              encodeLegacy(
                (raw || []).filter((f) => f.uid !== uid && f.username !== name),
              ),
            );
          }
        }
        state.cursor++;
        await checkpoint(state);
      }
      if (state.cursor < state.references.length)
        throw new Problem(
          503,
          "Account blocked; related friends cleanup is continuing",
        );
      state = { ...state, stage: "friends" };
      await checkpoint(state);
      // Leave the next network batch to the alarm rather than exhausting a request.
      throw new Problem(
        503,
        "Account blocked; linked record cleanup is continuing",
      );
    }
    const collections = [
      "friends",
      "matches",
      ...(this.extraCleanupCollections || []),
    ];
    for (const collection of collections) {
      if (state.stage !== collection) continue;
      const docs = await this.collection("users/" + uid + "/" + collection);
      for (const doc of docs.slice(0, 15))
        await this.firestoreDelete(
          "users/" + uid + "/" + collection + "/" + doc._docId,
        );
      if (docs.length > 15)
        throw new Problem(
          503,
          "Account blocked; " + collection + " cleanup is continuing",
        );
      state = {
        ...state,
        stage: collections[collections.indexOf(collection) + 1] || "profile",
      };
      await checkpoint(state);
      if (collection !== collections.at(-1))
        throw new Problem(503, "Account blocked; match cleanup is continuing");
    }
    if (state.stage === "profile") {
      await this.firestoreDelete("users/" + uid);
      await this.firestoreDelete("usernames/" + name);
      if (state.bin) await this.deleteBin(state.bin);
      if (state.avatarBin && state.avatarBin !== state.bin)
        await this.deleteBin(state.avatarBin);
      for (const key of [
        "reg_",
        "user_",
        "friends_",
        "freq_",
        "ava_",
        "user_msg_",
      ])
        await this.set(key + name, "");
      if (uid.startsWith("google_")) await this.set("gid_" + uid.slice(7), "");
      await this.set("pres_" + name, "OFFLINE:0");
      const names = await this.names();
      await this.set(
        "user_directory",
        names.filter((n) => n !== name).join(","),
      );
      state = { ...state, stage: "done" };
      await checkpoint(state);
    }
  }
}
export function toValue(v) {
  if (v === null) return { nullValue: null };
  if (typeof v === "boolean") return { booleanValue: v };
  if (typeof v === "number")
    return Number.isInteger(v)
      ? { integerValue: String(v) }
      : { doubleValue: v };
  if (typeof v === "string") return { stringValue: v };
  if (Array.isArray(v)) return { arrayValue: { values: v.map(toValue) } };
  return {
    mapValue: {
      fields: Object.fromEntries(
        Object.entries(v || {})
          .filter(([, x]) => x !== undefined)
          .map(([k, x]) => [k, toValue(x)]),
      ),
    },
  };
}
export function fromValue(v) {
  if ("nullValue" in v) return null;
  if ("stringValue" in v) return v.stringValue;
  if ("integerValue" in v) return Number(v.integerValue);
  if ("doubleValue" in v) return v.doubleValue;
  if ("booleanValue" in v) return v.booleanValue;
  if ("timestampValue" in v) return Date.parse(v.timestampValue);
  if (v.arrayValue) return (v.arrayValue.values || []).map(fromValue);
  if (v.mapValue)
    return Object.fromEntries(
      Object.entries(v.mapValue.fields || {}).map(([k, x]) => [
        k,
        fromValue(x),
      ]),
    );
  return null;
}
export function fromDocument(d) {
  return Object.fromEntries(
    Object.entries(d.fields || {}).map(([k, v]) => [k, fromValue(v)]),
  );
}
