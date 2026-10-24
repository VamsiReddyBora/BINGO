import test from "node:test";
import assert from "node:assert/strict";
import { AdminControl } from "../worker.js";
import { passwordHash } from "../model.js";
import { MemoryStorage, FakeLegacy } from "./fixtures.js";
import { TestAuthenticator, enroll, authorize } from "./authenticator.js";

async function fixture() {
  const storage = new MemoryStorage(),
    legacy = new FakeLegacy();
  const env = {
    ALLOWED_ORIGIN: "https://example.test",
    PUBLIC_URL: "https://admin.test",
    ADMIN_CREDENTIALS: JSON.stringify({
      operator: "owner",
      salt: "fixture",
      hash: await passwordHash("fixture-password-123456", "fixture"),
    }),
    TEST_LEGACY: legacy,
    TEST_BROKER: async () => ({
      connected: true,
      presence: {},
      rooms: {},
      registry: {},
    }),
  };
  const control = new AdminControl({ storage }, env);
  let token = "";
  const request = (path, method = "GET", input, headers = {}) =>
    control.fetch(
      new Request("https://admin.test" + path, {
        method,
        headers: {
          ...(token ? { Authorization: "Bearer " + token } : {}),
          ...headers,
        },
        body: input === undefined ? undefined : JSON.stringify(input),
      }),
    );
  token = (
    await (
      await request("/api/login", "POST", {
        password: "fixture-password-123456",
      })
    ).json()
  ).token;
  const bootstrapToken = token,
    authenticator = new TestAuthenticator(env.ALLOWED_ORIGIN),
    registered = await enroll(control, token, authenticator);
  token = registered.token;
  return {
    control,
    storage,
    legacy,
    authenticator,
    registered,
    bootstrapToken,
    request,
    get token() {
      return token;
    },
    set token(value) {
      token = value;
    },
  };
}
async function begin(f, purpose = "login", input = {}) {
  const response = await f.request(
    "/api/auth/" + purpose + "/options",
    "POST",
    input,
  );
  assert.equal(response.status, 200);
  return response.json();
}
async function login(f, options = {}) {
  const b = await begin(f);
  return f.request("/api/auth/login/verify", "POST", {
    challengeId: b.challengeId,
    response: f.authenticator.assertion(b.options, options),
  });
}
async function player(f) {
  return await (await f.request("/api/players/kevin")).json();
}
async function proof(
  f,
  path,
  method,
  input,
  operationId = crypto.randomUUID(),
) {
  return {
    proof: await authorize(
      f.control,
      f.token,
      f.authenticator,
      path,
      method,
      input,
      operationId,
    ),
    operationId,
  };
}
const proofHeaders = (p) => ({
  "X-Action-Proof": p.proof,
  "Idempotency-Key": p.operationId,
});

test("password bootstrap cannot inspect players before passkey enrollment", async () => {
  const env = {
    ALLOWED_ORIGIN: "https://example.test",
    PUBLIC_URL: "https://admin.test",
    ADMIN_CREDENTIALS: JSON.stringify({
      operator: "owner",
      salt: "fixture",
      hash: await passwordHash("fixture-password-123456", "fixture"),
    }),
    TEST_LEGACY: new FakeLegacy(),
    TEST_BROKER: async () => ({
      connected: true,
      presence: {},
      rooms: {},
      registry: {},
    }),
  };
  const control = new AdminControl({ storage: new MemoryStorage() }, env);
  const login = await control.fetch(
    new Request("https://admin.test/api/login", {
      method: "POST",
      body: JSON.stringify({ password: "fixture-password-123456" }),
    }),
  );
  const { token } = await login.json();
  assert.ok(token);
  const response = await control.fetch(
    new Request("https://admin.test/api/players", {
      headers: { Authorization: "Bearer " + token },
    }),
  );
  assert.equal(response.status, 403);
});

test("first enrollment requires real user verification and revokes password setup sessions", async () => {
  const f = await fixture();
  assert.equal(f.registered.authMethod, "passkey");
  assert.equal(f.registered.recoveryCodes.length, 8);
  const keys = await f.control.security.keys();
  assert.equal(keys.length, 1);
  assert.ok(keys[0].publicKey);
  assert.equal(keys[0].privateKey, undefined);
  assert.equal(
    (
      await f.request("/api/players", "GET", undefined, {
        Authorization: "Bearer " + f.bootstrapToken,
      })
    ).status,
    401,
  );
  assert.equal(
    (
      await f.request("/api/login", "POST", {
        password: "fixture-password-123456",
      })
    ).status,
    403,
  );
});
test("legacy password sessions cannot survive the passkey deployment", async () => {
  const f = await fixture();
  const [key, value] = [...(await f.storage.list({ prefix: "session:" }))][0];
  await f.storage.put(key, { ...value, authMethod: undefined });
  assert.equal((await f.request("/api/players")).status, 401);
});
test("valid registered passkey can sign in without the administrator password", async () => {
  const f = await fixture();
  const response = await login(f);
  assert.equal(response.status, 200);
  const data = await response.json();
  assert.equal(data.authMethod, "passkey");
  assert.ok(data.token);
});
for (const [label, options] of [
  ["missing user verification", { uv: false }],
  ["wrong origin", { origin: "https://attacker.test" }],
  ["wrong RP ID", { rpID: "attacker.test" }],
  ["wrong challenge", { challenge: "unissued-challenge" }],
  ["forged signature", { corrupt: true }],
])
  test("login rejects " + label, async () => {
    const f = await fixture();
    assert.equal((await login(f, options)).status, 403);
  });
test("a login assertion cannot be replayed", async () => {
  const f = await fixture(),
    b = await begin(f),
    input = {
      challengeId: b.challengeId,
      response: f.authenticator.assertion(b.options),
    };
  assert.equal(
    (await f.request("/api/auth/login/verify", "POST", input)).status,
    200,
  );
  assert.equal(
    (await f.request("/api/auth/login/verify", "POST", input)).status,
    403,
  );
});
test("expired challenges cannot sign in", async () => {
  const f = await fixture(),
    b = await begin(f),
    key = "challenge:" + b.challengeId,
    c = await f.storage.get(key);
  await f.storage.put(key, { ...c, expiresAt: 0 });
  assert.equal(
    (
      await f.request("/api/auth/login/verify", "POST", {
        challengeId: b.challengeId,
        response: f.authenticator.assertion(b.options),
      })
    ).status,
    403,
  );
});
test("deleting a player without a fresh device proof performs no writes", async () => {
  const f = await fixture(),
    p = await player(f);
  const response = await f.request("/api/players/kevin", "DELETE", {
    revision: p.revision,
    etag: p.etag,
    confirm: "kevin",
  });
  assert.equal(response.status, 403);
  assert.deepEqual(f.legacy.writes, []);
  assert.equal((await f.control.policy("kevin")).deleted, false);
});
test("a device proof is bound to the exact payload, route, method and request ID", async () => {
  const f = await fixture(),
    p = await player(f),
    input = { revision: p.revision, etag: p.etag, confirm: "kevin" },
    path = "/api/players/kevin",
    verified = await proof(f, path, "DELETE", input);
  assert.equal(
    (
      await f.request(
        path,
        "DELETE",
        { ...input, confirm: "stuart" },
        proofHeaders(verified),
      )
    ).status,
    403,
  );
  assert.equal(
    (
      await f.request(
        "/api/players/stuart",
        "DELETE",
        input,
        proofHeaders(verified),
      )
    ).status,
    403,
  );
  assert.equal(
    (await f.request(path, "PATCH", input, proofHeaders(verified))).status,
    403,
  );
  assert.equal(
    (
      await f.request(path, "DELETE", input, {
        ...proofHeaders(verified),
        "Idempotency-Key": crypto.randomUUID(),
      })
    ).status,
    403,
  );
  assert.deepEqual(f.legacy.writes, []);
  assert.equal(
    (await f.request(path, "DELETE", input, proofHeaders(verified))).status,
    200,
  );
});
test("a proof cannot be moved to another authenticated session", async () => {
  const f = await fixture(),
    p = await player(f),
    input = {
      revision: p.revision,
      etag: p.etag,
      patch: { displayName: "Changed" },
    },
    verified = await proof(f, "/api/players/kevin", "PATCH", input);
  const second = await (await login(f)).json();
  f.token = second.token;
  assert.equal(
    (
      await f.request(
        "/api/players/kevin",
        "PATCH",
        input,
        proofHeaders(verified),
      )
    ).status,
    403,
  );
  assert.deepEqual(f.legacy.writes, []);
});
test("each mutation needs a separate approval; idempotent response replay does not repeat writes", async () => {
  const f = await fixture(),
    p = await player(f),
    input = {
      revision: p.revision,
      etag: p.etag,
      patch: { displayName: "Changed" },
    },
    verified = await proof(f, "/api/players/kevin", "PATCH", input);
  assert.equal(
    (
      await f.request(
        "/api/players/kevin",
        "PATCH",
        input,
        proofHeaders(verified),
      )
    ).status,
    200,
  );
  const count = f.legacy.writes.length;
  assert.equal(
    (
      await f.request(
        "/api/players/kevin",
        "PATCH",
        input,
        proofHeaders(verified),
      )
    ).status,
    200,
  );
  assert.equal(f.legacy.writes.length, count);
  const next = await player(f);
  assert.equal(
    (
      await f.request(
        "/api/players/kevin",
        "PATCH",
        {
          revision: next.revision,
          etag: next.etag,
          patch: { displayName: "Other" },
        },
        { ...proofHeaders(verified), "Idempotency-Key": crypto.randomUUID() },
      )
    ).status,
    403,
  );
});
test("an action challenge cannot be used as a login challenge", async () => {
  const f = await fixture(),
    input = {
      path: "/api/password",
      method: "POST",
      bodyHash: "a".repeat(64),
      operationId: crypto.randomUUID(),
    },
    b = await begin(f, "action", input);
  assert.equal(
    (
      await f.request("/api/auth/login/verify", "POST", {
        challengeId: b.challengeId,
        response: f.authenticator.assertion(b.options),
      })
    ).status,
    403,
  );
});
test("last passkey cannot be removed, even after a valid approval", async () => {
  const f = await fixture(),
    id = (await f.control.security.keys())[0].id,
    path = "/api/auth/passkeys/" + id,
    input = {},
    verified = await proof(f, path, "DELETE", input);
  assert.equal(
    (await f.request(path, "DELETE", input, proofHeaders(verified))).status,
    409,
  );
  assert.equal((await f.control.security.keys()).length, 1);
});
test("an existing session cannot add a new passkey without its own approval", async () => {
  const f = await fixture();
  assert.equal(
    (
      await f.request("/api/auth/register/options", "POST", {
        label: "Unverified",
      })
    ).status,
    403,
  );
});
test("recovery codes are hashed, single use, and recovery cannot access player data", async () => {
  const f = await fixture(),
    code = f.registered.recoveryCodes[0];
  assert.equal(JSON.stringify([...f.storage.map]).includes(code), false);
  const recovered = await f.request("/api/auth/recover", "POST", {
    password: "fixture-password-123456",
    code,
  });
  assert.equal(recovered.status, 200);
  const session = await recovered.json();
  f.token = session.token;
  assert.equal((await f.request("/api/players")).status, 403);
  assert.equal(
    (
      await f.request("/api/auth/recover", "POST", {
        password: "fixture-password-123456",
        code,
      })
    ).status,
    401,
  );
  const replacement = new TestAuthenticator("https://example.test"),
    registered = await enroll(f.control, f.token, replacement);
  assert.equal(registered.recoveryCodes.length, 8);
  assert.equal((await f.control.security.keys()).length, 1);
  assert.equal((await login(f)).status, 403);
});
test("password without a recovery code cannot recover an enrolled account", async () => {
  const f = await fixture();
  assert.equal(
    (
      await f.request("/api/auth/recover", "POST", {
        password: "fixture-password-123456",
        code: "0".repeat(32),
      })
    ).status,
    401,
  );
});
