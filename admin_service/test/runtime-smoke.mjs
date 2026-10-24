// Explicit isolated deployment smoke test; never points at the production admin object.
import fs from "node:fs/promises";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { TestAuthenticator } from "./authenticator.js";
import { digest } from "../model.js";
const base = process.env.STAGING_URL;
assert.ok(
  base &&
    new URL(base).hostname.startsWith("bingo-admin-passkey-runtime-test."),
  "Use the isolated passkey runtime test Worker only",
);
assert.ok(
  process.env.STAGING_PASSWORD_FILE,
  "Supply the private staging password file",
);
const password = (
  await fs.readFile(process.env.STAGING_PASSWORD_FILE, "utf8")
).trim();
const origin = "https://vamsireddybora.github.io";
const authenticator = new TestAuthenticator(origin);
const results = [];
async function request(path, method = "GET", input, token, extra = {}) {
  const response = await fetch(base + "/api/" + path, {
    method,
    headers: {
      Origin: origin,
      "Content-Type": "application/json",
      ...(token ? { Authorization: "Bearer " + token } : {}),
      ...extra,
    },
    ...(input !== undefined ? { body: JSON.stringify(input) } : {}),
  });
  const value = await response.json();
  return { status: response.status, value };
}
async function check(name, run) {
  await run();
  results.push({ case: name, status: "PASS" });
  console.log("PASS " + name);
}
let setup, session, proof, operationId;
await check(
  "First setup required on isolated Cloudflare deployment",
  async () => {
    const state = await request("auth/status");
    assert.equal(state.status, 200);
    assert.equal(state.value.configured, false);
    const login = await request("login", "POST", { password });
    assert.equal(login.status, 200);
    assert.equal(login.value.setupRequired, true);
    setup = login.value.token;
    assert.equal(
      (await request("players", "GET", undefined, setup)).status,
      403,
    );
  },
);
await check(
  "Actual Workers runtime validates signed WebAuthn registration",
  async () => {
    const options = await request(
      "auth/register/options",
      "POST",
      { label: "Isolated runtime fixture" },
      setup,
    );
    assert.equal(options.status, 200);
    const result = await request(
      "auth/register/verify",
      "POST",
      {
        challengeId: options.value.challengeId,
        response: authenticator.registration(options.value.options),
      },
      setup,
    );
    assert.equal(result.status, 200);
    assert.equal(result.value.recoveryCodes.length, 8);
    session = result.value.token;
    assert.equal(
      (await request("session", "GET", undefined, setup)).status,
      401,
    );
  },
);
await check("Password alone cannot unlock after enrollment", async () => {
  assert.equal((await request("login", "POST", { password })).status, 403);
});
await check(
  "Actual Workers runtime validates signed passkey login",
  async () => {
    const options = await request("auth/login/options", "POST", {});
    assert.equal(options.status, 200);
    const result = await request("auth/login/verify", "POST", {
      challengeId: options.value.challengeId,
      response: authenticator.assertion(options.value.options),
    });
    assert.equal(result.status, 200);
    session = result.value.token;
    assert.equal(
      (await request("session", "GET", undefined, session)).status,
      200,
    );
  },
);
await check("Unverified mutation rejected by deployed guard", async () => {
  assert.equal(
    (await request("auth/recovery/rotate", "POST", {}, session)).status,
    403,
  );
});
await check(
  "Signed approval authorizes exact body and action once",
  async () => {
    operationId = randomUUID();
    const body = {};
    const options = await request(
      "auth/action/options",
      "POST",
      {
        path: "/api/auth/recovery/rotate",
        method: "POST",
        bodyHash: await digest(JSON.stringify(body)),
        operationId,
      },
      session,
    );
    assert.equal(options.status, 200);
    const verified = await request(
      "auth/action/verify",
      "POST",
      {
        challengeId: options.value.challengeId,
        response: authenticator.assertion(options.value.options),
      },
      session,
    );
    assert.equal(verified.status, 200);
    proof = verified.value.proof;
    const headers = { "Idempotency-Key": operationId, "X-Action-Proof": proof };
    assert.equal(
      (
        await request(
          "auth/recovery/rotate",
          "POST",
          { tampered: true },
          session,
          headers,
        )
      ).status,
      403,
    );
    const rotated = await request(
      "auth/recovery/rotate",
      "POST",
      body,
      session,
      headers,
    );
    assert.equal(rotated.status, 200);
    assert.equal(rotated.value.recoveryCodes.length, 8);
    assert.equal(
      (await request("auth/recovery/rotate", "POST", body, session, headers))
        .status,
      403,
    );
  },
);
await check("Deployed runtime rejects absent user verification", async () => {
  const options = await request("auth/login/options", "POST", {});
  const result = await request("auth/login/verify", "POST", {
    challengeId: options.value.challengeId,
    response: authenticator.assertion(options.value.options, { uv: false }),
  });
  assert.equal(result.status, 403);
});
await check("Deployed logout revokes the session", async () => {
  assert.equal((await request("logout", "POST", {}, session)).status, 200);
  assert.equal(
    (await request("session", "GET", undefined, session)).status,
    401,
  );
});
if (process.env.ADMIN_EVIDENCE)
  await fs.writeFile(
    process.env.ADMIN_EVIDENCE + "/runtime-results.json",
    JSON.stringify(
      {
        timestamp: new Date().toISOString(),
        results,
        productionPlayerDataMutated: false,
        isolatedWorker: true,
      },
      null,
      2,
    ),
  );
