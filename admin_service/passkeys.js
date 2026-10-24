import {
  generateRegistrationOptions,
  verifyRegistrationResponse,
  generateAuthenticationOptions,
  verifyAuthenticationResponse,
} from "@simplewebauthn/server";
import { Problem, requireThat, digest, randomToken } from "./model.js";
import { json, readBody } from "./http.js";

export const base64 = (value) =>
  btoa(String.fromCharCode(...new Uint8Array(value)))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
export const binary = (value) =>
  Uint8Array.from(atob(value.replaceAll("-", "+").replaceAll("_", "/")), (c) =>
    c.charCodeAt(0),
  );
const protectedError = (message, code = "VERIFICATION_REQUIRED") =>
  Object.assign(new Problem(403, message), { code });
export async function actionHash(path, method, input, operationId = "") {
  return digest(
    JSON.stringify({
      path,
      method,
      bodyHash: await digest(JSON.stringify(input)),
      operationId,
    }),
  );
}

/** A new, single-use WebAuthn assertion authorizes one exact mutation. */
export class PasskeySecurity {
  constructor(control) {
    this.control = control;
    this.storage = control.storage;
    this.origin = control.env.ALLOWED_ORIGIN;
    this.rpID = control.env.PASSKEY_RP_ID || new URL(this.origin).hostname;
  }
  async keys() {
    return [...(await this.storage.list({ prefix: "passkey:" })).values()];
  }
  async requireStrong(session) {
    if (session.authMethod !== "passkey")
      throw protectedError(
        "Set up your passkey before accessing player data or making changes.",
        "PASSKEY_SETUP_REQUIRED",
      );
  }
  async rate(request) {
    const id = await digest(
        request.headers.get("CF-Connecting-IP") || "unknown",
      ),
      key = "auth-rate:" + id,
      now = Date.now();
    await this.storage.transaction(async (store) => {
      let r = (await store.get(key)) || { start: now, count: 0 };
      if (now - r.start > 300000) r = { start: now, count: 0 };
      requireThat(
        r.count < 40,
        429,
        "Too many verification attempts. Try again in five minutes.",
      );
      await store.put(key, { ...r, count: r.count + 1 });
    });
  }
  async challenge(purpose, options, session = null, extra = {}) {
    const id = randomToken();
    await this.storage.put("challenge:" + id, {
      purpose,
      challenge: options.challenge,
      sessionKey: session?.key || null,
      expiresAt: Date.now() + 120000,
      ...extra,
    });
    await this.storage.setAlarm(Date.now() + 60000);
    return { challengeId: id, options };
  }
  async take(id, purpose, session = null) {
    requireThat(
      typeof id === "string" && /^[a-f0-9]{64}$/.test(id),
      400,
      "Invalid verification challenge",
    );
    return this.storage.transaction(async (store) => {
      const key = "challenge:" + id,
        c = await store.get(key);
      requireThat(
        c &&
          c.expiresAt > Date.now() &&
          c.purpose === purpose &&
          c.sessionKey === (session?.key || null),
        403,
        "Verification expired or does not belong to this session. Start again.",
      );
      await store.delete(key);
      return c;
    });
  }
  async authenticate(response, challenge) {
    requireThat(
      response && typeof response.id === "string" && response.id.length < 2048,
      403,
      "Invalid passkey response",
    );
    const saved = await this.storage.get("passkey:" + response.id);
    requireThat(saved, 403, "This passkey is not registered");
    let result;
    try {
      result = await verifyAuthenticationResponse({
        response,
        expectedChallenge: challenge.challenge,
        expectedOrigin: this.origin,
        expectedRPID: this.rpID,
        requireUserVerification: true,
        credential: {
          id: saved.id,
          publicKey: binary(saved.publicKey),
          counter: saved.counter,
          transports: saved.transports,
        },
      });
    } catch {
      throw new Problem(403, "Device verification failed. Please try again.");
    }
    requireThat(result.verified, 403, "Device verification is required");
    await this.storage.put("passkey:" + saved.id, {
      ...saved,
      counter: result.authenticationInfo.newCounter,
      lastUsedAt: Date.now(),
    });
    return saved;
  }
  async options() {
    return generateAuthenticationOptions({
      rpID: this.rpID,
      userVerification: "required",
      timeout: 60000,
      allowCredentials: (await this.keys()).map((k) => ({
        id: k.id,
        transports: k.transports,
      })),
    });
  }
  async guard(request, path, session, input) {
    await this.requireStrong(session);
    const proof = request.headers.get("X-Action-Proof");
    if (!proof || !/^[a-f0-9]{64}$/.test(proof))
      throw protectedError("Verify on your device before making this change.");
    const key = "proof:" + (await digest(proof)),
      expected = await actionHash(
        path,
        request.method,
        input,
        request.headers.get("Idempotency-Key") || "",
      );
    await this.storage.transaction(async (store) => {
      const saved = await store.get(key);
      requireThat(
        saved &&
          saved.expiresAt > Date.now() &&
          saved.sessionKey === session.key &&
          saved.actionHash === expected,
        403,
        "Verification expired or belongs to another action. Verify again.",
      );
      await store.delete(key);
    });
  }
  async codes() {
    const previous = await this.storage.list({ prefix: "recovery:" });
    for (const key of previous.keys()) await this.storage.delete(key);
    const codes = [];
    for (let i = 0; i < 8; i++) {
      const raw = randomToken().slice(0, 32);
      codes.push(raw.match(/.{8}/g).join("-"));
      await this.storage.put("recovery:" + (await digest(raw)), {
        createdAt: Date.now(),
      });
    }
    return codes;
  }
  async revoke() {
    for (const key of (await this.storage.list({ prefix: "session:" })).keys())
      await this.storage.delete(key);
  }
  async route(request, path) {
    if (path === "/api/auth/status" && request.method === "GET") {
      const keys = await this.keys();
      return json({
        configured: keys.length > 0,
        initialized: !!(await this.storage.get("passkeys-enabled")),
        verificationRequired: true,
      });
    }
    const input = request.method === "GET" ? {} : await readBody(request);
    if (path === "/api/auth/login/options" && request.method === "POST") {
      await this.rate(request);
      requireThat(
        (await this.keys()).length > 0,
        409,
        "Set up your first passkey using your administrator password.",
      );
      return json(await this.challenge("login", await this.options()));
    }
    if (path === "/api/auth/login/verify" && request.method === "POST") {
      await this.rate(request);
      const challenge = await this.take(input.challengeId, "login");
      const key = await this.authenticate(input.response, challenge);
      return json(await this.control.issueSession("passkey", key.id));
    }
    if (path === "/api/auth/recover" && request.method === "POST") {
      await this.rate(request);
      await this.control.verifyPassword(input.password);
      const normalized = String(input.code || "")
        .toLowerCase()
        .replaceAll("-", "")
        .trim();
      requireThat(
        /^[a-f0-9]{32}$/.test(normalized),
        401,
        "Invalid recovery credentials",
      );
      const key = "recovery:" + (await digest(normalized));
      await this.storage.transaction(async (store) => {
        requireThat(await store.get(key), 401, "Invalid recovery credentials");
        await store.delete(key);
      });
      return json(await this.control.issueSession("recovery"));
    }
    const session = await this.control.authenticate(request);
    if (path === "/api/auth/register/options" && request.method === "POST") {
      const keys = await this.keys();
      requireThat(
        keys.length < 10,
        409,
        "A maximum of ten passkeys can be registered",
      );
      if (session.authMethod === "passkey")
        await this.guard(request, path, session, input);
      else if (session.authMethod === "setup")
        requireThat(
          keys.length === 0 && !(await this.storage.get("passkeys-enabled")),
          403,
          "First-time setup is already complete. Sign in with your passkey.",
        );
      else
        requireThat(
          session.authMethod === "recovery",
          403,
          "Passkey setup is not authorized",
        );
      let user = await this.storage.get("passkey-user");
      if (!user) {
        user = randomToken();
        await this.storage.put("passkey-user", user);
      }
      const options = await generateRegistrationOptions({
        rpName: "Bingo Control",
        rpID: this.rpID,
        userID: new TextEncoder().encode(user),
        userName: "bingo-owner",
        userDisplayName: "Bingo administrator",
        attestationType: "none",
        supportedAlgorithmIDs: [-7, -257],
        timeout: 60000,
        excludeCredentials: keys.map((k) => ({
          id: k.id,
          transports: k.transports,
        })),
        authenticatorSelection: {
          residentKey: "required",
          userVerification: "required",
        },
      });
      return json(
        await this.challenge("registration", options, session, {
          label:
            String(input.label || "My passkey")
              .trim()
              .slice(0, 60) || "My passkey",
        }),
      );
    }
    if (path === "/api/auth/register/verify" && request.method === "POST") {
      const challenge = await this.take(
        input.challengeId,
        "registration",
        session,
      );
      let result;
      try {
        result = await verifyRegistrationResponse({
          response: input.response,
          expectedChallenge: challenge.challenge,
          expectedOrigin: this.origin,
          expectedRPID: this.rpID,
          requireUserVerification: true,
          supportedAlgorithmIDs: [-7, -257],
        });
      } catch {
        throw new Problem(
          403,
          "Passkey setup could not be verified. Please try again.",
        );
      }
      requireThat(
        result.verified && result.registrationInfo,
        403,
        "Device verification is required to create a passkey",
      );
      const credential = result.registrationInfo.credential;
      requireThat(
        !(await this.storage.get("passkey:" + credential.id)),
        409,
        "This passkey is already registered",
      );
      const first =
        !(await this.keys()).length || session.authMethod === "recovery";
      if (session.authMethod === "recovery")
        for (const key of (
          await this.storage.list({ prefix: "passkey:" })
        ).keys())
          await this.storage.delete(key);
      await this.storage.put("passkey:" + credential.id, {
        id: credential.id,
        publicKey: base64(credential.publicKey),
        counter: credential.counter,
        transports: credential.transports || [],
        label: challenge.label,
        createdAt: Date.now(),
        lastUsedAt: Date.now(),
      });
      await this.storage.put("passkeys-enabled", true);
      if (first) await this.revoke();
      const recoveryCodes = first ? await this.codes() : undefined;
      await this.control.audit(
        session,
        "REGISTER_PASSKEY",
        credential.id,
        null,
        { label: challenge.label, recovery: session.authMethod === "recovery" },
      );
      return json({
        ...(await this.control.issueSession("passkey", credential.id)),
        recoveryCodes,
      });
    }
    await this.requireStrong(session);
    if (path === "/api/auth/action/options" && request.method === "POST") {
      requireThat(
        typeof input.path === "string" &&
          input.path.startsWith("/api/") &&
          input.path.length < 400 &&
          ["POST", "PUT", "PATCH", "DELETE"].includes(input.method) &&
          /^[a-f0-9]{64}$/.test(input.bodyHash || "") &&
          typeof input.operationId === "string" &&
          /^[A-Za-z0-9-]{16,80}$/.test(input.operationId),
        400,
        "Invalid action verification request",
      );
      const hash = await digest(
        JSON.stringify({
          path: input.path,
          method: input.method,
          bodyHash: input.bodyHash,
          operationId: input.operationId,
        }),
      );
      return json(
        await this.challenge("action", await this.options(), session, {
          actionHash: hash,
        }),
      );
    }
    if (path === "/api/auth/action/verify" && request.method === "POST") {
      const challenge = await this.take(input.challengeId, "action", session),
        key = await this.authenticate(input.response, challenge),
        proof = randomToken();
      await this.storage.put("proof:" + (await digest(proof)), {
        sessionKey: session.key,
        actionHash: challenge.actionHash,
        credentialId: key.id,
        expiresAt: Date.now() + 60000,
      });
      return json({ proof });
    }
    if (path === "/api/auth/passkeys" && request.method === "GET")
      return json({
        passkeys: (await this.keys()).map(
          ({ publicKey, counter, ...key }) => key,
        ),
      });
    if (path === "/api/auth/recovery/rotate" && request.method === "POST") {
      await this.guard(request, path, session, input);
      const recoveryCodes = await this.codes();
      await this.control.audit(
        session,
        "ROTATE_RECOVERY_CODES",
        "owner",
        null,
        { rotated: true },
      );
      return json({ recoveryCodes });
    }
    const deletion = /^\/api\/auth\/passkeys\/([A-Za-z0-9_-]{1,2048})$/.exec(
      path,
    );
    if (deletion && request.method === "DELETE") {
      await this.guard(request, path, session, input);
      requireThat(
        (await this.keys()).length > 1,
        409,
        "Add a backup passkey before removing your last passkey",
      );
      requireThat(
        await this.storage.get("passkey:" + deletion[1]),
        404,
        "Passkey not found",
      );
      await this.storage.delete("passkey:" + deletion[1]);
      await this.revoke();
      await this.control.audit(session, "REMOVE_PASSKEY", deletion[1], null, {
        removed: true,
      });
      return json({ signInAgain: true });
    }
    throw new Problem(404, "Unknown authentication endpoint");
  }
  async cleanup(now) {
    for (const prefix of ["challenge:", "proof:"])
      for (const [key, value] of await this.storage.list({ prefix }))
        if (value.expiresAt <= now) await this.storage.delete(key);
    for (const [key, value] of await this.storage.list({
      prefix: "auth-rate:",
    }))
      if (now - value.start > 300000) await this.storage.delete(key);
  }
}
