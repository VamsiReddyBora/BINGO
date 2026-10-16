import {
  HttpError,
  requireThat,
  response,
  readBody,
  username,
  validId,
  digest,
  validateEvent,
  fresh,
  legacyRead,
} from "./protocol.js";
import { sendFcm } from "./fcm.js";
const DAY = 86400000,
  SESSION_TTL = 7 * DAY;
const keyEvent = (name, id) => `event:${name}:${id}`;
const random = () => crypto.randomUUID() + crypto.randomUUID();

/** This object serializes notification inbox mutations only. It never owns a game or a turn. */
export class NotificationInbox {
  constructor(state, env) {
    this.state = state;
    this.storage = state.storage;
    this.env = env;
    this.queue = Promise.resolve();
  }
  serial(task) {
    const run = this.queue.then(task);
    this.queue = run.catch(() => {});
    return run;
  }
  fetch(request) {
    return this.serial(() => this.handle(request));
  }
  async alarm() {
    const jobs = await this.serial(() => this.claimJobs());
    for (let i = 0; i < jobs.length; i += 4)
      await Promise.all(
        jobs.slice(i, i + 4).map(async (job) => {
          let result;
          try {
            result = await sendFcm(this.env, job.token, job.payload);
          } catch {
            result = { retryable: true };
          }
          await this.serial(() => this.finishJob(job, result));
        }),
      );
    await this.serial(() => this.cleanup());
  }
  async handle(request) {
    try {
      const path = new URL(request.url).pathname;
      const body = request.method === "POST" ? await readBody(request) : {};
      if (path === "/internal/auth-rate") {
        await this.throttle("auth:" + body.key, 30);
        return response({ ok: true });
      }
      if (path === "/internal/enroll") {
        await this.throttle("enroll:" + body.uid, 12);
        return response(await this.enroll(body));
      }
      if (path === "/v2/revoke") {
        const reg = await this.storage.get(
          "registration:" + body.registrationId,
        );
        if (
          reg &&
          typeof body.revokeSecret === "string" &&
          (await digest(body.revokeSecret)) === reg.revokeHash
        )
          await this.revoke(reg);
        return response({ ok: true });
      }
      const session = await this.authenticate(request);
      if (path === "/v2/register" && request.method === "POST") {
        requireThat(
          typeof body.token === "string" && body.token.length <= 4096,
          400,
          "Invalid token",
        );
        requireThat(
          Number.isSafeInteger(body.revision) && body.revision >= 0,
          400,
          "Invalid token revision",
        );
        const reg = await this.storage.get(
          "registration:" + session.registrationId,
        );
        if (reg.invalidToken && reg.invalidToken === body.token)
          return response({ ok: true, refreshToken: true });
        if (body.revision >= (reg.tokenRevision || 0)) {
          reg.token = body.token;
          reg.tokenRevision = body.revision;
          reg.updatedAt = Date.now();
          await this.storage.put("registration:" + reg.id, reg);
          await this.queuePending(reg);
        }
        return response({ ok: true });
      }
      if (path === "/v2/friends" && request.method === "POST") {
        requireThat(
          Array.isArray(body.uids) &&
            body.uids.length <= 1000 &&
            body.uids.every((v) => typeof v === "string" && v.length <= 100),
          400,
          "Invalid friends",
        );
        requireThat(
          Number.isSafeInteger(body.revision) && body.revision > 0,
          400,
          "Invalid friend revision",
        );
        const reg = await this.storage.get(
          "registration:" + session.registrationId,
        );
        if (body.revision < (reg.friendRevision || 0))
          return response({ ok: true, stale: true });
        reg.friendRevision = body.revision;
        const account = await this.storage.get("account:" + session.uid);
        account.friends = [...new Set(body.uids)].filter(
          (u) => u !== session.uid,
        );
        await this.storage.put({
          ["account:" + session.uid]: account,
          ["registration:" + reg.id]: reg,
        });
        return response({ ok: true });
      }
      if (path === "/v2/events" && request.method === "POST")
        return response(await this.submit(session, body), 202);
      if (path === "/v2/inbox" && request.method === "GET") {
        const account = await this.storage.get("account:" + session.uid),
          events = [];
        for (const name of account.aliases || [session.username]) {
          const records = await this.storage.list({ prefix: `event:${name}:` });
          for (const e of records.values())
            if (
              !e.handled &&
              fresh(e) &&
              (!e.recipientUid || e.recipientUid === session.uid) &&
              (e.kind !== "FRIEND_ONLINE" ||
                account.friends.includes(e.senderUid))
            )
              events.push({
                ...this.publicEvent(e, session.uid),
                recipientUsername: account.username,
              });
        }
        return response({ events });
      }
      if (path === "/v2/ack" && request.method === "POST") {
        requireThat(validId(body.id), 400, "Invalid event ID");
        const account = await this.storage.get("account:" + session.uid);
        for (const name of account.aliases || [session.username]) {
          const key = keyEvent(name, body.id),
            event = await this.storage.get(key);
          if (
            event &&
            (!event.recipientUid || event.recipientUid === session.uid)
          ) {
            event.handled = true;
            await this.storage.put(key, event);
          }
        }
        return response({ ok: true });
      }
      throw new HttpError(404, "Not found");
    } catch (e) {
      return response(
        {
          error:
            e instanceof HttpError
              ? e.message
              : "Notification service temporarily unavailable",
        },
        e.status || 503,
      );
    }
  }
  async revoke(reg) {
    reg.revoked = true;
    reg.token = "";
    await this.storage.put("registration:" + reg.id, reg);
    if (reg.sessionHash)
      await this.storage.delete("session:" + reg.sessionHash);
    await this.storage.delete(`device:${reg.uid}:${reg.id}`);
  }
  async enroll(identity) {
    const now = Date.now(),
      installKey = "installation:" + identity.installationId;
    const installation = await this.storage.get(installKey);
    requireThat(
      !installation || identity.sequence >= installation.sequence,
      409,
      "Superseded installation generation",
    );
    requireThat(
      !installation ||
        identity.sequence !== installation.sequence ||
        (installation.generation === identity.generation &&
          installation.uid === identity.uid),
      409,
      "Generation collision",
    );
    const owner = await this.storage.get("name:" + identity.username);
    requireThat(
      !owner || owner === identity.uid,
      409,
      "Username belongs to another account",
    );
    if (!owner) {
      // Only used for migration from the existing app. Never read FCM tokens from the public directory.
      const legacy = await legacyRead(this.env, "reg_" + identity.username);
      requireThat(
        !legacy || legacy.uid === identity.uid,
        403,
        "Username identity mismatch",
      );
    }
    if (identity.nonce) {
      const nonceKey = "nonce:" + identity.uid + ":" + identity.nonce;
      requireThat(
        !(await this.storage.get(nonceKey)),
        409,
        "Proof already used",
      );
      await this.storage.put(nonceKey, now + 120000);
    }
    const existing = await this.storage.get("account:" + identity.uid);
    if (existing && existing.username !== identity.username) {
      const legacy = await legacyRead(this.env, "reg_" + identity.username);
      requireThat(
        legacy?.uid === identity.uid,
        403,
        "Verified username rename required",
      );
    }
    const id = await digest(
      `${identity.uid}\n${identity.installationId}\n${identity.generation}`,
    );
    if (installation && installation.registrationId !== id) {
      const old = await this.storage.get(
        "registration:" + installation.registrationId,
      );
      if (old) await this.revoke(old);
    }
    const previous = await this.storage.get("registration:" + id);
    requireThat(!previous?.revoked, 409, "Registration has been revoked");
    const session = random(),
      sessionHash = await digest(session),
      revokeSecret = random();
    const registration = {
      id,
      uid: identity.uid,
      username: identity.username,
      installationId: identity.installationId,
      generation: identity.generation,
      sequence: identity.sequence,
      token:
        (previous?.tokenRevision || 0) > (identity.tokenRevision || 0)
          ? previous.token
          : identity.token,
      tokenRevision: Math.max(
        previous?.tokenRevision || 0,
        identity.tokenRevision || 0,
      ),
      invalidToken: previous?.invalidToken || "",
      friendRevision: previous?.friendRevision || 0,
      sessionHash,
      revokeHash: await digest(revokeSecret),
      updatedAt: now,
      revoked: false,
    };
    if (previous?.sessionHash)
      await this.storage.delete("session:" + previous.sessionHash);
    const account = {
      uid: identity.uid,
      username: identity.username,
      displayName: identity.displayName,
      friends: existing?.friends || [],
      aliases: [
        ...new Set(
          [
            ...(existing?.aliases || []),
            existing?.username,
            identity.username,
          ].filter(Boolean),
        ),
      ],
    };
    await this.storage.put({
      ["account:" + identity.uid]: account,
      ["name:" + identity.username]: identity.uid,
      ["registration:" + id]: registration,
      [`device:${identity.uid}:${id}`]: { id, updatedAt: now },
      [installKey]: {
        registrationId: id,
        uid: identity.uid,
        generation: identity.generation,
        sequence: identity.sequence,
      },
      ["session:" + sessionHash]: {
        uid: identity.uid,
        username: identity.username,
        registrationId: id,
        expiresAt: now + SESSION_TTL,
      },
    });
    const devices = await this.storage.list({
      prefix: `device:${identity.uid}:`,
    });
    if (devices.size > 8)
      for (const [key, device] of [...devices]
        .sort((a, b) => a[1].updatedAt - b[1].updatedAt)
        .slice(0, devices.size - 8)) {
        const old = await this.storage.get("registration:" + device.id);
        if (old) await this.revoke(old);
        await this.storage.delete(key);
      }
    await this.queuePending(registration);
    await this.schedule(now + 60000);
    return {
      session,
      expiresAt: now + SESSION_TTL,
      registrationId: id,
      revokeSecret,
    };
  }
  async authenticate(request) {
    const auth = request.headers.get("Authorization") || "";
    requireThat(
      /^Bearer [a-zA-Z0-9-]{60,160}$/.test(auth),
      401,
      "Authentication required",
    );
    const session = await this.storage.get(
      "session:" + (await digest(auth.slice(7))),
    );
    requireThat(
      session && session.expiresAt > Date.now(),
      401,
      "Session expired",
    );
    const reg = await this.storage.get(
      "registration:" + session.registrationId,
    );
    requireThat(
      reg && !reg.revoked && reg.uid === session.uid,
      401,
      "Registration revoked",
    );
    const account = await this.storage.get("account:" + session.uid);
    return { ...session, username: account.username };
  }
  async throttle(key, max, window = 60000) {
    const now = Date.now(),
      stored = await this.storage.get("rate:" + key);
    const record =
      stored && stored.until > now ? stored : { count: 0, until: now + window };
    requireThat(record.count < max, 429, "Notification rate limit exceeded");
    record.count++;
    await this.storage.put("rate:" + key, record);
  }
  publicEvent(e, recipientUid) {
    const { handled, toUsername, purgeAt, ...safe } = e;
    return { ...safe, recipientUid, recipientUsername: toUsername };
  }
  async submit(session, body) {
    const input = validateEvent(body),
      key = keyEvent(input.toUsername, input.id),
      existing = await this.storage.get(key);
    if (existing) {
      requireThat(
        existing.senderUid === session.uid &&
          existing.kind === input.kind &&
          existing.roomCode === (input.roomCode || "") &&
          existing.requestId === (input.requestId || ""),
        409,
        "Event identity conflict",
      );
      return { status: "queued", id: existing.id };
    }
    requireThat(
      input.toUsername !== session.username,
      400,
      "Cannot notify yourself",
    );
    await this.throttle(session.uid, 60);
    const account = await this.storage.get("account:" + session.uid),
      targetUid = await this.storage.get("name:" + input.toUsername);
    const target = targetUid
      ? await this.storage.get("account:" + targetUid)
      : null;
    if (input.kind === "GAME_INVITE") {
      const room = await legacyRead(this.env, "room_" + input.roomCode);
      requireThat(
        room &&
          room.hostId === session.uid &&
          username(room.hostUsername) === session.username &&
          room.status === "WAITING",
        403,
        "Only the active room host may send this invitation",
      );
    }
    if (input.kind === "FRIEND_ONLINE")
      requireThat(
        target?.friends?.includes(session.uid),
        403,
        "Recipient has not authorized friend alerts",
      );
    if (input.kind === "FRIEND_ACCEPTED") {
      const request = await this.storage.get("request:" + input.requestId);
      requireThat(
        request
          ? request.toUsername === session.username &&
              request.senderUsername === input.toUsername
          : target &&
              account.friends.includes(target.uid) &&
              target.friends.includes(session.uid),
        request ? 403 : 425,
        "Matching request or mutual friendship is not available yet",
      );
      const old = request
        ? await this.storage.get(keyEvent(session.username, request.eventId))
        : null;
      if (old) {
        old.handled = true;
        await this.storage.put(
          keyEvent(session.username, request.eventId),
          old,
        );
      }
      account.friends = [
        ...new Set([...account.friends, request?.senderUid || target.uid]),
      ];
      await this.storage.put("account:" + session.uid, account);
      if (target) {
        target.friends = [...new Set([...target.friends, session.uid])];
        await this.storage.put("account:" + target.uid, target);
      }
    }
    await this.throttle(
      `${session.uid}:${input.kind}:${input.toUsername}`,
      input.kind === "FRIEND_ONLINE" ? 2 : 10,
    );
    const inbox = await this.storage.list({
      prefix: `event:${input.toUsername}:`,
    });
    for (const [oldKey, old] of inbox)
      if (old.purgeAt <= Date.now()) {
        await this.storage.delete(oldKey);
        inbox.delete(oldKey);
      }
    requireThat(
      inbox.size < 1024 &&
        [...inbox.values()].filter((e) => !e.handled && fresh(e)).length < 256,
      429,
      "Recipient inbox is full",
    );
    const event = {
      id: input.id,
      kind: input.kind,
      senderUid: session.uid,
      senderUsername: session.username,
      senderDisplayName: account.displayName,
      toUsername: input.toUsername,
      recipientUid: targetUid || "",
      createdAt: input.createdAt,
      expiresAt: input.expiresAt,
      roomCode: input.roomCode || "",
      requestId: input.requestId || "",
      requestTimestamp: input.requestTimestamp || 0,
      presenceSessionId: input.presenceSessionId || "",
      handled: false,
      purgeAt: input.expiresAt + DAY,
    };
    // Per-event keys and serialized mutations prevent lost updates across different senders and devices.
    const writes = { [key]: event };
    if (input.kind === "FRIEND_REQUEST") {
      const old = await this.storage.get("request:" + input.requestId);
      requireThat(
        !old ||
          (old.senderUid === session.uid &&
            old.toUsername === input.toUsername),
        409,
        "Request identity conflict",
      );
    }
    if (input.kind === "FRIEND_REQUEST")
      writes["request:" + input.requestId] = {
        senderUid: session.uid,
        senderUsername: session.username,
        toUsername: input.toUsername,
        eventId: input.id,
        purgeAt: input.expiresAt + DAY,
      };
    if (targetUid) {
      const devices = await this.storage.list({
        prefix: `device:${targetUid}:`,
      });
      for (const device of devices.values()) {
        const reg = await this.storage.get("registration:" + device.id);
        if (
          reg &&
          !reg.revoked &&
          reg.token &&
          reg.updatedAt > Date.now() - 30 * DAY
        )
          writes[`outbox:${reg.id}:${event.id}`] = {
            registrationId: reg.id,
            eventKey: key,
            nextAttempt: Date.now(),
            attempts: 0,
            purgeAt: event.expiresAt,
          };
      }
    }
    await this.storage.put(writes);
    await this.schedule(Date.now() + 1000);
    return { status: "queued", id: event.id };
  }
  async schedule(when) {
    const old = await this.storage.getAlarm();
    if (!old || old > when) await this.storage.setAlarm(when);
  }
  async queuePending(reg) {
    if (reg.revoked || !reg.token) return;
    const account = await this.storage.get("account:" + reg.uid);
    for (const name of account.aliases || [account.username]) {
      const events = await this.storage.list({ prefix: `event:${name}:` });
      for (const [eventKey, e] of events) {
        if (
          e.handled ||
          !fresh(e) ||
          (e.recipientUid && e.recipientUid !== reg.uid) ||
          (e.kind === "FRIEND_ONLINE" && !account.friends.includes(e.senderUid))
        )
          continue;
        const key = `outbox:${reg.id}:${e.id}`;
        // Do not reset an existing lease, retry count or original event lifetime.
        const delivered = await this.storage.get(`delivery:${reg.id}:${e.id}`);
        if (delivered?.tokenRevision === (reg.tokenRevision || 0)) continue;
        if (!(await this.storage.get(key)))
          await this.storage.put(key, {
            registrationId: reg.id,
            eventKey,
            nextAttempt: Date.now(),
            attempts: 0,
            purgeAt: e.expiresAt,
          });
      }
    }
    await this.schedule(Date.now() + 1000);
  }
  async claimJobs() {
    const now = Date.now(),
      jobs = await this.storage.list({ prefix: "outbox:", limit: 64 }),
      claimed = [];
    let next = now + 3600000;
    for (const [key, job] of jobs) {
      if (job.nextAttempt > now) {
        next = Math.min(next, job.nextAttempt);
        continue;
      }
      const event = await this.storage.get(job.eventKey),
        reg = await this.storage.get("registration:" + job.registrationId);
      const target = reg ? await this.storage.get("account:" + reg.uid) : null;
      if (
        !event ||
        event.handled ||
        !fresh(event, now) ||
        !reg ||
        reg.revoked ||
        !reg.token ||
        (event.kind === "FRIEND_ONLINE" &&
          !target?.friends?.includes(event.senderUid))
      ) {
        await this.storage.delete(key);
        continue;
      }
      job.lease = crypto.randomUUID();
      job.nextAttempt = now + 30000;
      await this.storage.put(key, job);
      claimed.push({
        key,
        job,
        token: reg.token,
        tokenRevision: reg.tokenRevision || 0,
        payload: {
          ...this.publicEvent(event, reg.uid),
          recipientUsername: target.username,
          installationId: reg.installationId,
          generation: reg.generation,
        },
      });
      next = Math.min(next, job.nextAttempt);
      if (claimed.length === 8) {
        next = Math.min(next, now + 1000);
        break;
      }
    }
    await this.storage.setAlarm(Math.max(now + 1000, next));
    return claimed;
  }
  async finishJob(claimed, result) {
    const current = await this.storage.get(claimed.key);
    if (!current || current.lease !== claimed.job.lease) return;
    const reg = await this.storage.get(
        "registration:" + current.registrationId,
      ),
      event = await this.storage.get(current.eventKey);
    if (
      reg &&
      !reg.revoked &&
      reg.token &&
      reg.token !== claimed.token &&
      event &&
      !event.handled &&
      fresh(event)
    ) {
      current.nextAttempt = Date.now();
      delete current.lease;
      await this.storage.put(claimed.key, current);
      await this.schedule(Date.now() + 1000);
      return;
    }
    if (
      result.invalid &&
      reg?.token === claimed.token &&
      (reg.tokenRevision || 0) === claimed.tokenRevision
    ) {
      reg.invalidToken = reg.token;
      reg.token = "";
      await this.storage.put("registration:" + reg.id, reg);
    }
    if (result.accepted && event)
      await this.storage.put(`delivery:${current.registrationId}:${event.id}`, {
        tokenRevision: claimed.tokenRevision,
        purgeAt: event.expiresAt + DAY,
      });
    if (
      result.accepted ||
      result.invalid ||
      result.expired ||
      !result.retryable ||
      !event ||
      event.handled ||
      !fresh(event) ||
      !reg ||
      reg.revoked
    ) {
      await this.storage.delete(claimed.key);
      return;
    }
    current.attempts++;
    if (current.attempts >= 8) {
      await this.storage.delete(claimed.key);
      return;
    }
    current.nextAttempt =
      Date.now() + Math.min(10000 * 2 ** (current.attempts - 1), 300000);
    delete current.lease;
    await this.storage.put(claimed.key, current);
    await this.schedule(Math.min(current.nextAttempt, event.expiresAt));
  }
  async cleanup() {
    const now = Date.now(),
      cursor = await this.storage.get("cleanup-cursor");
    let all = await this.storage.list({
      limit: 100,
      ...(cursor ? { startAfter: cursor } : {}),
    });
    if (all.size === 0 && cursor) all = await this.storage.list({ limit: 100 });
    await this.storage.put(
      "cleanup-cursor",
      all.size === 100 ? [...all.keys()].at(-1) : "",
    );
    for (const [key, value] of all) {
      if (
        (key.startsWith("event:") ||
          key.startsWith("request:") ||
          key.startsWith("delivery:")) &&
        value.purgeAt <= now
      )
        await this.storage.delete(key);
      else if (key.startsWith("session:") && value.expiresAt <= now)
        await this.storage.delete(key);
      else if (key.startsWith("rate:") && value.until <= now)
        await this.storage.delete(key);
      else if (key.startsWith("nonce:") && value <= now)
        await this.storage.delete(key);
      else if (
        key.startsWith("registration:") &&
        value.updatedAt < now - 30 * DAY
      ) {
        await this.revoke(value);
        await this.storage.delete(key);
      }
    }
    const remaining = await this.storage.list({ prefix: "outbox:", limit: 64 });
    let next = now + 3600000;
    for (const job of remaining.values())
      next = Math.min(next, job.nextAttempt);
    const existing = await this.storage.getAlarm();
    if (existing && existing > now) next = Math.min(next, existing);
    await this.storage.setAlarm(Math.max(now + 1000, next));
  }
}
