import {
  Problem,
  requireThat,
  username,
  decodeLegacy,
  encodeLegacy,
  validatePatch,
  validateAvatar,
  rasterPhoto,
  applyPolicy,
  presence,
  safeAudit,
  mapLimit,
  digest,
  randomToken,
  passwordHash,
  constantEqual,
} from "./model.js";
import { LegacyStore } from "./legacy.js";
import { brokerTcp } from "./mqtt.js";

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
async function body(request) {
  const text = await request.text();
  requireThat(text.length <= 110000, 413, "Request is too large");
  try {
    return JSON.parse(text || "{}");
  } catch {
    throw new Problem(400, "Invalid JSON");
  }
}
export default {
  async fetch(request, env) {
    const origin = request.headers.get("Origin");
    const headers = {
      "Access-Control-Allow-Origin": env.ALLOWED_ORIGIN,
      "Access-Control-Allow-Methods": "GET,POST,PATCH,PUT,DELETE,OPTIONS",
      "Access-Control-Allow-Headers":
        "Authorization,Content-Type,Idempotency-Key",
      "Access-Control-Max-Age": "600",
      Vary: "Origin",
    };
    if (origin && origin !== env.ALLOWED_ORIGIN)
      return json({ error: "Origin is not permitted" }, 403);
    if (request.method === "OPTIONS")
      return new Response(null, { status: 204, headers });
    let response;
    try {
      if (new URL(request.url).pathname === "/health")
        response = json({
          service: "bingo-admin-control",
          version: 1,
          authenticationConfigured: !!env.ADMIN_CREDENTIALS,
        });
      else
        response = await env.ADMIN.get(
          env.ADMIN.idFromName("control-v1"),
        ).fetch(request);
    } catch {
      response = json(
        { error: "Admin service is temporarily unavailable" },
        503,
      );
    }
    const result = new Response(response.body, response);
    for (const [k, v] of Object.entries(headers)) result.headers.set(k, v);
    return result;
  },
};

export class AdminControl {
  constructor(state, env) {
    this.state = state;
    this.storage = state.storage;
    this.env = env;
    this.legacy = env.TEST_LEGACY || new LegacyStore(env);
    this.broker = env.TEST_BROKER || ((args) => brokerTcp(env, args));
    this.mutations = Promise.resolve();
    this.cachedSnapshot = null;
  }
  async fetch(request) {
    try {
      const url = new URL(request.url),
        path = url.pathname;
      if (path.startsWith("/client/") && request.method === "GET")
        return await this.publicRead(path, url);
      if (path === "/api/login" && request.method === "POST")
        return await this.login(request);
      const session = await this.authenticate(request);
      if (request.method === "GET") return await this.read(path, url, session);
      const task = this.mutations.then(() =>
        this.mutate(request, path, session),
      );
      this.mutations = task.catch(() => {});
      return await task;
    } catch (e) {
      return json(
        {
          error:
            e instanceof Problem
              ? e.message
              : "Admin operation failed; no success has been confirmed",
        },
        e instanceof Problem ? e.status : 503,
      );
    }
  }
  async credentials() {
    const saved = await this.storage.get("credentials");
    if (saved) return saved;
    requireThat(
      this.env.ADMIN_CREDENTIALS,
      503,
      "Administrator login is not configured",
    );
    return JSON.parse(this.env.ADMIN_CREDENTIALS);
  }
  async login(request) {
    const ip = await digest(
      request.headers.get("CF-Connecting-IP") || "unknown",
    );
    const key = "login:" + ip;
    const now = Date.now();
    await this.storage.transaction(async (store) => {
      let rate = (await store.get(key)) || { start: now, count: 0 };
      if (now - rate.start > 300000) rate = { start: now, count: 0 };
      requireThat(
        rate.count < 8,
        429,
        "Too many login attempts. Try again in five minutes.",
      );
      await store.put(key, { start: rate.start, count: rate.count + 1 });
    });
    const input = await body(request);
    requireThat(
      typeof input.password === "string" && input.password.length <= 200,
      401,
      "Invalid administrator credentials",
    );
    const config = await this.credentials();
    const hash = await passwordHash(input.password, config.salt);
    requireThat(
      constantEqual(hash, config.hash),
      401,
      "Invalid administrator credentials",
    );
    const token = randomToken(),
      expiresAt = now + 12 * 3600000;
    await this.storage.put("session:" + (await digest(token)), {
      operator: config.operator || "owner",
      createdAt: now,
      lastSeen: now,
      expiresAt,
    });
    await this.storage.delete(key);
    await this.storage.setAlarm(now + 60000);
    return json({ token, expiresAt, operator: config.operator || "owner" });
  }
  async authenticate(request) {
    const value = request.headers.get("Authorization") || "";
    requireThat(
      /^Bearer [a-f0-9]{64}$/.test(value),
      401,
      "Administrator sign-in required",
    );
    const key = "session:" + (await digest(value.slice(7)));
    const session = await this.storage.get(key),
      now = Date.now();
    requireThat(
      session && session.expiresAt > now && now - session.lastSeen < 1800000,
      401,
      "Admin session expired; sign in again",
    );
    if (now - session.lastSeen > 30000)
      await this.storage.put(key, { ...session, lastSeen: now });
    return { ...session, key };
  }
  async policy(name) {
    return (
      (await this.storage.get("player:" + name)) || {
        username: name,
        revision: 0,
        fields: {},
        deleted: false,
        deletedMatches: [],
      }
    );
  }
  async policies() {
    return [...(await this.storage.list({ prefix: "player:" })).values()];
  }
  async publicRead(path, url) {
    if (path === "/client/deletions") {
      const deleted = (await this.policies()).filter((p) => p.deleted);
      return json({
        deleted: deleted.map((p) => ({ username: p.username, uid: p.uid })),
        fetchedAt: Date.now(),
      });
    }
    const match = /^\/client\/(policy|avatar)\/([^/]+)$/.exec(path);
    requireThat(match, 404, "Unknown endpoint");
    const name = username(decodeURIComponent(match[2])),
      policy = await this.policy(name);
    if (match[1] === "policy")
      return json({
        username: name,
        uid: policy.uid || null,
        deleted: policy.deleted,
        revision: policy.revision,
        fields: policy.fields,
        deletedMatches: policy.deletedMatches || [],
        clearMatchesBefore: policy.clearMatchesBefore || 0,
      });
    requireThat(!policy.deleted, 404, "Photo not available");
    const row = await this.player(name);
    const photo = row.profile.avatarBase64 || row.profile.avatarUrl;
    if (rasterPhoto(photo)) {
      const valid = rasterPhoto(photo),
        split = valid.indexOf(",");
      const bytes = Uint8Array.from(atob(valid.slice(split + 1)), (c) =>
        c.charCodeAt(0),
      );
      return new Response(bytes, {
        headers: {
          "Content-Type": valid.slice(5, valid.indexOf(";")),
          "Cache-Control": "public, max-age=60",
          "X-Content-Type-Options": "nosniff",
        },
      });
    }
    if (typeof photo === "string" && /^https:\/\//.test(photo))
      return Response.redirect(photo, 302);
    throw new Problem(404, "Photo not available");
  }
  async player(name, force = false, cacheOnly = false, registry = null) {
    const policy = await this.policy(name);
    requireThat(!policy.deleted, 410, "Player has been removed");
    let cached = await this.storage.get("cache:" + name),
      raw;
    if (cacheOnly) {
      if (cached)
        raw = { ...cached, stale: Date.now() - cached.fetchedAt > 60000 };
      else if (registry?.uid)
        raw = {
          username: name,
          profile: { ...registry, username: name },
          fetchedAt: Number(registry.lastSeenTimestamp) || 0,
          registryOnly: true,
        };
      else throw new Problem(503, "Profile not yet available");
    } else if (!force && cached && Date.now() - cached.fetchedAt < 15000)
      raw = cached;
    else
      try {
        raw = await this.legacy.read(name);
        const small = { ...raw, backup: undefined, matchHistory: undefined };
        await this.storage.put("cache:" + name, small);
      } catch (e) {
        if (!cached) throw e;
        raw = { ...cached, stale: true, error: "Profile source unavailable" };
      }
    requireThat(
      !(await this.policies()).some(
        (p) => p.deleted && p.uid && p.uid === raw.profile.uid,
      ),
      410,
      "Player identity has been removed",
    );
    const profile = applyPolicy(raw.profile, policy);
    const etag = await digest(
      JSON.stringify({
        profile,
        revision: policy.revision,
        deletedMatches: policy.deletedMatches,
        clearMatchesBefore: policy.clearMatchesBefore,
      }),
    );
    return {
      ...raw,
      profile,
      revision: policy.revision,
      policy,
      etag,
      sourceApplied:
        (Number(raw.profile.adminRevision) || 0) >= policy.revision,
    };
  }
  async list(force = false) {
    if (
      !force &&
      this.cachedSnapshot &&
      Date.now() - this.cachedSnapshot.fetchedAt < 4000
    )
      return this.cachedSnapshot;
    const names = await this.legacy.names(),
      policies = await this.policies(),
      deleted = new Set(
        policies.filter((p) => p.deleted).map((p) => p.username),
      );
    let broker;
    try {
      broker = await this.broker({ snapshot: true });
    } catch (error) {
      broker = {
        presence: {},
        rooms: {},
        connected: false,
        error: error.message || "Broker unavailable",
      };
    }
    const active = names.filter((n) => !deleted.has(n));
    // Bound per-invocation external calls; cold directories can contain thousands of profiles.
    const cursor = this.profileCursor || 0;
    const refreshNames = new Set(
      Array.from(
        { length: Math.min(6, active.length) },
        (_, i) => active[(cursor + i) % active.length],
      ),
    );
    this.profileCursor = active.length ? (cursor + 6) % active.length : 0;
    const rows = await mapLimit(active, 5, async (name) => {
      try {
        const row = await this.player(
          name,
          refreshNames.has(name),
          !refreshNames.has(name),
          decodeLegacy(broker.registry?.[name]),
        );
        const p = presence(broker.presence[name], Date.now(), broker.connected);
        const raw = row.profile.avatarBase64 || row.profile.avatarUrl || "";
        const photoVersion = raw ? await digest(raw) : null;
        const profile = { ...row.profile };
        delete profile.avatarBase64;
        if (rasterPhoto(raw))
          profile.avatarUrl = `${this.env.PUBLIC_URL}/client/avatar/${encodeURIComponent(name)}?v=${photoVersion.slice(0, 16)}`;
        return {
          username: name,
          profile,
          revision: row.revision,
          etag: row.etag,
          sourceApplied: row.sourceApplied,
          sourceStatus: row.stale
            ? "STALE"
            : row.registryOnly
              ? "REGISTRY"
              : row.backupError
                ? "PARTIAL"
                : "FRESH",
          fetchedAt: row.fetchedAt,
          presence: p,
        };
      } catch (error) {
        if (error.status === 410) return null;
        return {
          username: name,
          profile: { username: name, displayName: name },
          revision: (await this.policy(name)).revision,
          sourceStatus: "UNAVAILABLE",
          fetchedAt: 0,
          presence: presence(
            broker.presence[name],
            Date.now(),
            broker.connected,
          ),
        };
      }
    });
    const rooms = Object.entries(broker.rooms).flatMap(([code, raw]) => {
      try {
        const room = JSON.parse(raw);
        if (!room || ["CLOSED", "CANCELLED", "FINISHED"].includes(room.status))
          return [];
        return [
          {
            ...room,
            code,
            sourceStatus:
              Date.now() -
                (room.lastHeartbeat || room.timestamp || room.createdAt || 0) >
              30000
                ? "STALE"
                : "FRESH",
          },
        ];
      } catch {
        return [];
      }
    });
    const visibleRows = rows.filter(Boolean);
    this.cachedSnapshot = {
      players: visibleRows,
      rooms,
      fetchedAt: Date.now(),
      presenceConnected: broker.connected,
      presenceError: broker.error || null,
      directoryVerified: true,
      total: visibleRows.length,
      removedAccounts: deleted.size,
    };
    return this.cachedSnapshot;
  }
  async read(path, url, session) {
    if (path === "/api/session")
      return json({ operator: session.operator, expiresAt: session.expiresAt });
    if (path === "/api/players")
      return json(await this.list(url.searchParams.get("fresh") === "1"));
    if (path === "/api/audit") {
      const entries = [
        ...(
          await this.storage.list({
            prefix: "audit:",
            reverse: true,
            limit: 150,
          })
        ).values(),
      ];
      return json({ entries });
    }
    if (path === "/api/operations") {
      const operations = [
        ...(await this.storage.list({ prefix: "operation:" })).values(),
      ];
      operations.sort(
        (a, b) =>
          Number(b.status === "PENDING") - Number(a.status === "PENDING") ||
          b.createdAt - a.createdAt,
      );
      return json({ operations: operations.slice(0, 100) });
    }
    if (path === "/api/release") {
      const data = decodeLegacy(await this.legacy.get("latest_app_version"));
      requireThat(
        data && Number.isSafeInteger(data.versionCode),
        503,
        "Release metadata is unavailable",
      );
      return json({ release: data, fetchedAt: Date.now() });
    }
    if (path === "/api/announcements")
      return json({
        announcements: [
          ...(await this.storage.list({ prefix: "announcement:" })).values(),
        ],
      });
    const match = /^\/api\/players\/([^/]+)$/.exec(path);
    requireThat(match, 404, "Unknown endpoint");
    const name = username(decodeURIComponent(match[1]));
    const row = await this.player(name, true);
    requireThat(
      !row.stale,
      503,
      "Player source is unavailable; showing cached data would be unsafe for editing",
    );
    const [backup, friends, cloudMatches] = await Promise.all([
      row.binId
        ? this.legacy.backup(row.binId).catch((error) => {
            if (row.backupError) return null;
            throw error;
          })
        : Promise.resolve(null),
      this.legacy.friends(name, row.profile.uid),
      this.legacy.collection("users/" + row.profile.uid + "/matches"),
    ]);
    const deletions = (await this.policies()).filter((p) => p.deleted);
    const removedNames = new Set(deletions.map((p) => p.username)),
      removedIds = new Set(deletions.map((p) => p.uid));
    const history = new Map();
    for (const record of [...(backup?.matchHistory || []), ...cloudMatches]) {
      const id = record.id || record._docId;
      if (id) history.set(id, { ...record, id });
    }
    const matches = [...history.values()].filter(
      (m) =>
        !(row.policy.deletedMatches || []).includes(m.id) &&
        !(
          row.policy.clearMatchesBefore &&
          m.timestamp <= row.policy.clearMatchesBefore
        ),
    );
    return json({
      username: name,
      profile: row.profile,
      revision: row.revision,
      etag: row.etag,
      sourceApplied: row.sourceApplied,
      fetchedAt: Date.now(),
      historyPartial: row.backupError,
      friends: friends.filter(
        (f) => !removedNames.has(f.username) && !removedIds.has(f.uid),
      ),
      matches,
    });
  }
  async saveOperation(
    session,
    type,
    target,
    data,
    before,
    after,
    policy = null,
  ) {
    const id = session.operationId || crypto.randomUUID(),
      now = Date.now();
    const operation = {
      id,
      type,
      target,
      data,
      requestHash: session.requestHash,
      createdAt: now,
      status: "PENDING",
      attempts: 0,
    };
    await this.storage.transaction(async (store) => {
      if (policy) await store.put("player:" + target, policy);
      if (type === "FRIEND")
        await store.put(
          "friend-intent:" + this.friendKey(target, data.other),
          id,
        );
      await store.put("operation:" + id, operation);
      await store.put("audit:" + String(now).padStart(16, "0") + ":" + id, {
        id,
        operator: session.operator,
        type,
        target,
        at: now,
        before: safeAudit(before),
        after: safeAudit(after),
      });
      await store.delete("cache:" + target);
      await store.setAlarm(now + 30000);
    });
    this.cachedSnapshot = null;
    return this.execute(operation);
  }
  friendKey(a, b) {
    return [a, b].sort().join(":");
  }
  async execute(operation) {
    const op = {
      ...operation,
      attempts: operation.attempts + 1,
      updatedAt: Date.now(),
    };
    try {
      if (op.type === "CREATE") {
        await this.legacy.createAccount(op.data.profile);
      } else if (op.type === "PROFILE") {
        const row = await this.player(op.target, true);
        const mirrored = await this.legacy.mirrorProfile(
          op.target,
          applyPolicy(row.profile, await this.policy(op.target)),
          await this.policy(op.target),
        );
        const latestProfile = mirrored || row.profile;
        if (op.data.oldAvatarBin && !latestProfile.avatarBase64)
          await this.legacy.deleteBin(op.data.oldAvatarBin);
        const photo =
          latestProfile.avatarBase64 || latestProfile.avatarUrl || "";
        await this.broker({
          messages: [
            {
              topic: "bingo/v3/avatar_update/" + op.target,
              payload: JSON.stringify({
                username: op.target,
                avatar: photo,
                timestamp: Date.now(),
              }),
              retain: true,
            },
          ],
        });
      } else if (op.type === "FRIEND") {
        const latest = await this.storage.get(
          "friend-intent:" + this.friendKey(op.target, op.data.other),
        );
        if (
          (latest && latest !== op.id) ||
          (await this.policy(op.target)).deleted ||
          (await this.policy(op.data.other)).deleted
        ) {
          op.status = "SUPERSEDED";
          op.error =
            "A newer decision or account deletion superseded this friendship change";
          await this.storage.put("operation:" + op.id, op);
          return json({
            operation: { ...op, data: undefined },
            message: op.error,
          });
        }
        const a = await this.player(op.target, true),
          b = await this.player(op.data.other, true);
        await this.legacy.mirrorFriend(a, b, op.data.present, op.id);
      } else if (op.type === "DELETE") {
        await this.legacy.deleteAccount(
          op.target,
          op.data.uid,
          op.data.binId,
          op.data.cleanup,
          async (cleanup) => {
            op.data.cleanup = cleanup;
            await this.storage.put("operation:" + op.id, op);
          },
        );
        await this.broker({
          messages: [
            {
              topic: "bingo/v3/registry/" + op.target,
              payload: "",
              retain: true,
            },
            {
              topic: "bingo/v3/presence/" + op.target,
              payload: "",
              retain: true,
            },
            {
              topic: "bingo/v3/avatar_update/" + op.target,
              payload: "",
              retain: true,
            },
          ],
        });
      } else if (op.type === "MATCH") {
        const row = await this.player(op.target, true),
          backup = await this.legacy.backup(row.binId),
          p = await this.policy(op.target);
        backup.matchHistory = (backup.matchHistory || []).filter(
          (m) =>
            !p.deletedMatches.includes(m.id) &&
            !(p.clearMatchesBefore && m.timestamp <= p.clearMatchesBefore),
        );
        await this.legacy.writeBackup(op.target, row.binId, backup);
        const docs = await this.legacy.collection(
          "users/" + row.profile.uid + "/matches",
        );
        for (const m of docs)
          if (
            p.deletedMatches.includes(m.id || m._docId) ||
            (p.clearMatchesBefore && m.timestamp <= p.clearMatchesBefore)
          )
            await this.legacy.firestoreDelete(
              "users/" + row.profile.uid + "/matches/" + m._docId,
            );
      }
      op.status = "SYNCED";
      delete op.error;
    } catch (e) {
      op.status = "PENDING";
      op.error =
        e instanceof Problem
          ? e.message
          : "External storage synchronization is pending";
    }
    await this.storage.put("operation:" + op.id, op);
    this.cachedSnapshot = null;
    await this.storage.delete("cache:" + op.target);
    return json(
      {
        operation: { ...op, data: undefined },
        message:
          op.status === "SYNCED"
            ? "Change saved and synchronized"
            : "Change saved in admin control; external synchronization is pending",
      },
      op.status === "SYNCED" ? 200 : 202,
    );
  }
  async mutate(request, path, session) {
    if (path === "/api/logout") {
      await this.storage.delete(session.key);
      return json({ ok: true });
    }
    const input = await body(request);
    const operationId = request.headers.get("Idempotency-Key");
    if (operationId) {
      requireThat(
        /^[a-zA-Z0-9-]{16,80}$/.test(operationId),
        400,
        "Invalid operation identifier",
      );
      session = {
        ...session,
        operationId,
        requestHash: await digest(
          JSON.stringify({ path, method: request.method, input }),
        ),
      };
      const existing = await this.storage.get("operation:" + operationId);
      if (existing) {
        requireThat(
          existing.requestHash === session.requestHash,
          409,
          "Operation identifier was already used for another change",
        );
        return json(
          {
            operation: { ...existing, data: undefined },
            message:
              existing.status === "SYNCED"
                ? "Already synchronized"
                : "External synchronization is pending",
          },
          existing.status === "SYNCED" ? 200 : 202,
        );
      }
    }
    if (path === "/api/password") {
      requireThat(
        typeof input.password === "string" &&
          input.password.length >= 16 &&
          input.password.length <= 200,
        400,
        "Use a password with at least 16 characters",
      );
      const salt = randomToken();
      await this.storage.put("credentials", {
        operator: session.operator,
        salt,
        hash: await passwordHash(input.password, salt),
      });
      const sessions = await this.storage.list({ prefix: "session:" });
      for (const key of sessions.keys()) await this.storage.delete(key);
      return json({ ok: true, signInAgain: true });
    }
    const retry = /^\/api\/operations\/([a-zA-Z0-9-]{16,80})\/retry$/.exec(
      path,
    );
    if (retry) {
      const op = await this.storage.get("operation:" + retry[1]);
      requireThat(op, 404, "Operation not found");
      if (op.status !== "PENDING")
        return json({
          operation: { ...op, data: undefined },
          message: "This operation is already complete",
        });
      return this.execute(op);
    }
    if (path === "/api/players" && request.method === "POST") {
      const name = username(input.username);
      requireThat(
        typeof input.uid === "string" &&
          /^[A-Za-z0-9_-]{4,100}$/.test(input.uid),
        400,
        "A valid existing player UID is required",
      );
      const policy = await this.policy(name);
      requireThat(
        !policy.deleted && !policy.uid,
        409,
        "This username is already managed or was permanently removed",
      );
      requireThat(
        !(await this.legacy.names()).includes(name),
        409,
        "Player already exists",
      );
      requireThat(
        !(await this.policies()).some((p) => p.uid === input.uid && p.deleted),
        409,
        "This player identity was removed",
      );
      const patch = validatePatch({
        displayName: input.displayName || name,
        gamesPlayed: 0,
        gamesWon: 0,
        currentStreak: 0,
        bestStreak: 0,
        level: 1,
        xp: 0,
      });
      const profile = {
        uid: input.uid,
        username: name,
        ...patch,
        provider: input.uid.startsWith("google_") ? "GOOGLE" : "GUEST",
        adminRevision: 1,
      };
      return this.saveOperation(
        session,
        "CREATE",
        name,
        { profile },
        null,
        { uid: profile.uid, username: name },
        {
          username: name,
          uid: profile.uid,
          revision: 1,
          fields: {},
          deleted: false,
          deletedMatches: [],
        },
      );
    }
    if (path === "/api/release") {
      requireThat(request.method === "PUT", 405, "Method not allowed");
      requireThat(
        typeof input.versionName === "string" &&
          input.versionName.length <= 40 &&
          Number.isSafeInteger(input.versionCode) &&
          input.versionCode > 0 &&
          /^https:\/\//.test(input.downloadUrl || ""),
        400,
        "Valid version, build number and HTTPS APK URL required",
      );
      const old = decodeLegacy(await this.legacy.get("latest_app_version"));
      requireThat(
        input.allowRollback === true ||
          !old?.versionCode ||
          input.versionCode >= old.versionCode,
        409,
        "Build number would roll back; explicitly authorize rollback",
      );
      const release = {
        versionName: input.versionName,
        versionCode: input.versionCode,
        tag_name: String(input.tag_name || "v" + input.versionName).slice(
          0,
          80,
        ),
        downloadUrl: input.downloadUrl,
        releaseNotes: String(input.releaseNotes || "").slice(0, 8000),
        apkSize: Number(input.apkSize) || 0,
      };
      await this.legacy.set("latest_app_version", JSON.stringify(release));
      await this.audit(session, "RELEASE", "release", old, release);
      return json({ release, persisted: true });
    }
    if (path === "/api/announcements") {
      const target = input.target === "ALL" ? "ALL" : username(input.target);
      if (target !== "ALL") await this.player(target);
      const notice = {
        id: crypto.randomUUID(),
        title: String(input.title || "")
          .trim()
          .slice(0, 120),
        message: String(input.message || "")
          .trim()
          .slice(0, 4000),
        active: input.active !== false,
        type: ["INFO", "WARNING", "UPDATE", "MAINTENANCE"].includes(input.type)
          ? input.type
          : "INFO",
        deliveryMode: ["STARTUP", "IN_GAME", "BOTH"].includes(
          input.deliveryMode,
        )
          ? input.deliveryMode
          : "STARTUP",
        size: "STANDARD",
        author: session.operator,
        targetUsername: target === "ALL" ? "ALL" : "@" + target,
        isDirect: target !== "ALL",
        timestamp: Date.now(),
      };
      requireThat(
        !notice.active || (notice.title && notice.message),
        400,
        "Announcement title and message required",
      );
      await this.legacy.set(
        target === "ALL" ? "global_broadcast_message" : "user_msg_" + target,
        JSON.stringify(notice),
      );
      await this.storage.put("announcement:" + target, notice);
      let brokerAcknowledged = false;
      try {
        await this.broker({
          messages: [
            {
              topic: "bingo/global/broadcast",
              payload: JSON.stringify(notice),
              retain: target === "ALL",
            },
          ],
        });
        brokerAcknowledged = true;
      } catch {}
      await this.audit(session, "ANNOUNCEMENT", target, null, notice);
      return json({
        notice,
        persisted: true,
        brokerAcknowledged,
        deviceDeliveryConfirmed: false,
      });
    }
    const room = /^\/api\/rooms\/([^/]+)\/close$/.exec(path);
    if (room) {
      const code = decodeURIComponent(room[1]);
      requireThat(/^[A-Za-z0-9_-]{3,32}$/.test(code), 400, "Invalid room code");
      const state = await this.broker({ snapshot: true });
      const meta = decodeLegacy(state.rooms[code]);
      requireThat(
        meta && meta.status !== "PLAYING",
        409,
        "Only an inactive lobby can be closed here; an active match is not an expired room",
      );
      await this.legacy.set(
        "room_" + code,
        encodeLegacy({ ...meta, status: "CLOSED" }),
      );
      await this.broker({
        messages: [
          {
            topic: "bingo/v3/room_meta/" + code,
            payload: JSON.stringify({ ...meta, status: "CLOSED" }),
            retain: true,
          },
        ],
      });
      this.cachedSnapshot = null;
      await this.audit(session, "CLOSE_LOBBY", code, meta, {
        status: "CLOSED",
      });
      return json({ ok: true });
    }
    const match =
      /^\/api\/players\/([^/]+)(?:\/(avatar|friends|matches)(?:\/([^/]+))?)?$/.exec(
        path,
      );
    requireThat(match, 404, "Unknown endpoint");
    const name = username(decodeURIComponent(match[1])),
      kind = match[2],
      other = match[3];
    const current = await this.player(name, true);
    requireThat(
      !current.stale,
      503,
      "Refresh the player before making changes",
    );
    requireThat(
      input.revision === current.revision && input.etag === current.etag,
      409,
      "Player changed since inspection. Refresh and review before saving.",
    );
    const policy = {
      ...current.policy,
      uid: current.profile.uid,
      revision: current.revision + 1,
      updatedAt: Date.now(),
      fields: { ...current.policy.fields },
    };
    if (!kind && request.method === "DELETE") {
      requireThat(
        input.confirm === name,
        400,
        "Type the exact username to confirm removal",
      );
      policy.deleted = true;
      policy.fields = {};
      policy.deletedAt = Date.now();
      return this.saveOperation(
        session,
        "DELETE",
        name,
        { uid: current.profile.uid, binId: current.binId },
        { uid: current.profile.uid, username: name },
        { deleted: true },
        policy,
      );
    }
    if (
      (!kind && request.method === "PATCH") ||
      (kind === "avatar" && request.method === "PUT")
    ) {
      const patch =
        kind === "avatar"
          ? {
              avatarUrl: null,
              avatarBase64: validateAvatar(input.photo)?.split(",")[1] ?? null,
            }
          : validatePatch(input.patch, current.profile);
      for (const [key, value] of Object.entries(patch))
        policy.fields[key] = { value, revision: policy.revision };
      return this.saveOperation(
        session,
        "PROFILE",
        name,
        { oldAvatarBin: kind === "avatar" ? current.avatarBinId : null },
        Object.fromEntries(
          Object.keys(patch).map((k) => [
            k,
            k === "avatarBase64"
              ? "[previous photo]"
              : (current.profile[k] ?? null),
          ]),
        ),
        {
          ...patch,
          ...("avatarBase64" in patch
            ? { avatarBase64: patch.avatarBase64 ? "[uploaded photo]" : null }
            : {}),
        },
        policy,
      );
    }
    if (
      kind === "friends" &&
      other &&
      ["PUT", "DELETE"].includes(request.method)
    ) {
      const target = username(decodeURIComponent(other));
      requireThat(target !== name, 400, "Cannot add yourself as a friend");
      const second = await this.player(target, true);
      requireThat(
        !second.stale &&
          input.otherRevision === second.revision &&
          input.otherEtag === second.etag,
        409,
        "The other player changed; refresh before modifying the friendship",
      );
      // Do not advance profile policy revisions for a friendship; device statistics keep their own history.
      return this.saveOperation(
        session,
        "FRIEND",
        name,
        { other: target, present: request.method === "PUT" },
        null,
        { friend: target, present: request.method === "PUT" },
      );
    }
    if (kind === "matches" && request.method === "DELETE") {
      policy.deletedMatches = [...(policy.deletedMatches || [])];
      if (other)
        policy.deletedMatches = [
          ...new Set([...policy.deletedMatches, decodeURIComponent(other)]),
        ];
      else {
        requireThat(
          input.confirm === name,
          400,
          "Type the exact username to clear history",
        );
        policy.clearMatchesBefore = Date.now();
      }
      requireThat(
        policy.deletedMatches.length <= 5000,
        400,
        "Too many deleted match IDs",
      );
      return this.saveOperation(
        session,
        "MATCH",
        name,
        {},
        null,
        { match: other || "all prior history" },
        policy,
      );
    }
    throw new Problem(405, "Method not allowed");
  }
  async audit(session, type, target, before, after) {
    const id = crypto.randomUUID(),
      at = Date.now();
    await this.storage.put("audit:" + String(at).padStart(16, "0") + ":" + id, {
      id,
      at,
      operator: session.operator,
      type,
      target,
      before: safeAudit(before),
      after: safeAudit(after),
    });
  }
  async alarm() {
    const task = this.mutations.then(() => this.runAlarm());
    this.mutations = task.catch(() => {});
    return task;
  }
  async runAlarm() {
    const now = Date.now();
    const pending = [
      ...(await this.storage.list({ prefix: "operation:" })).values(),
    ]
      .filter((op) => op.status === "PENDING")
      .sort(
        (a, b) => (a.updatedAt || a.createdAt) - (b.updatedAt || b.createdAt),
      );
    for (const op of pending.slice(0, 10)) await this.execute(op);
    for (const [key, s] of await this.storage.list({ prefix: "session:" }))
      if (s.expiresAt <= now || now - s.lastSeen >= 1800000)
        await this.storage.delete(key);
    for (const [key, rate] of await this.storage.list({ prefix: "login:" }))
      if (now - rate.start > 300000) await this.storage.delete(key);
    const audit = await this.storage.list({ prefix: "audit:", reverse: true });
    let i = 0;
    for (const key of audit.keys())
      if (++i > 2000) await this.storage.delete(key);
    const remaining = [
      ...(await this.storage.list({ prefix: "operation:" })).values(),
    ].some((op) => op.status === "PENDING");
    const activeSessions = await this.storage.list({ prefix: "session:" });
    if (remaining || activeSessions.size)
      await this.storage.setAlarm(now + (remaining ? 60000 : 300000));
  }
}
