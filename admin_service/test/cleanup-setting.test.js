import test from "node:test";
import assert from "node:assert/strict";
import { generateKeyPair, SignJWT } from "jose";
import { AdminControl } from "../worker.js";
import { MemoryStorage, FakeLegacy } from "./fixtures.js";
import { cleanupSettings, setCleanupEnabled, runAutomaticCleanup } from "../automatic-cleanup.js";
import { verifyNativeAdmin, NATIVE_ADMIN_AUDIENCES } from "../native-admin.js";
import { digest } from "../model.js";

async function fixture(enabled = true) {
  const storage = new MemoryStorage();
  let ready, brokerCalls = 0;
  const env = { PUBLIC_URL: "https://admin.test", ALLOWED_ORIGIN: "https://example.test", MQTT_AUTOCLEAN_ENABLED: String(enabled), TEST_LEGACY: new FakeLegacy(),
    TEST_BROKER: async () => { brokerCalls++; return { retainedRecords: {} }; } };
  const control = new AdminControl({ storage, blockConcurrencyWhile: f => (ready = f()) }, env);
  await ready;
  return { control, storage, env, brokerCalls: () => brokerCalls };
}
const request = (data, method = data ? "POST" : "GET") => new Request(
  "https://admin.test/client/admin/automatic-cleanup", { method,
    headers: { "X-Admin-User": "bob", "Content-Type": "application/json" },
    ...(data ? { body: JSON.stringify(data) } : {}) });

test("cleanup setting: username header alone cannot read or change the setting", async () => {
  const f = await fixture();
  for (const r of [request(), request({ enabled: false, expectedRevision: 0 })]) {
    assert.equal((await f.control.fetch(r)).status, 401);
  }
  assert.equal((await cleanupSettings(f.control)).revision, 0);
});
test("cleanup setting: pause survives a new object and prevents even forced automatic work", async () => {
  const f = await fixture();
  await setCleanupEnabled(f.control, false, 0, "bob");
  const restarted = await fixture();
  restarted.control.storage = f.storage;
  assert.equal((await cleanupSettings(restarted.control)).enabled, false);
  assert.equal(await runAutomaticCleanup(restarted.control, Date.now(), { force: true }), null);
  assert.equal(restarted.brokerCalls(), 0);
});
test("cleanup setting: shared revision prevents stale admin overwrite; resume schedules a fresh alarm", async () => {
  const f = await fixture();
  f.control.nativeAdminIdentity = async () => "bob";
  const pause = await f.control.fetch(request({ enabled: false, expectedRevision: 0 }));
  assert.equal((await pause.json()).revision, 1);
  f.control.nativeAdminIdentity = async () => "vampire";
  assert.equal((await f.control.fetch(request({ enabled: true, expectedRevision: 0 }))).status, 409);
  const resumed = await f.control.fetch(request({ enabled: true, expectedRevision: 1 }));
  assert.equal(resumed.status, 200);
  assert.equal((await resumed.json()).enabled, true);
  assert.equal((await cleanupSettings(f.control)).updatedBy, "vampire");
  assert.ok((await f.storage.getAlarm()) > Date.now());
  assert.equal(f.brokerCalls(), 0);
});
test("cleanup setting: simultaneous changes serialize and only one matching revision commits", async () => {
  const f = await fixture();
  f.control.nativeAdminIdentity = async () => "admin";
  const results = await Promise.all([0, 1].map(() => f.control.fetch(request({ enabled: false, expectedRevision: 0 }))));
  assert.deepEqual(results.map(x => x.status).sort(), [200, 409]);
  assert.equal((await cleanupSettings(f.control)).revision, 1);
});
test("cleanup setting: invalid bodies and deployment hard-disable cannot enable cleanup", async () => {
  const f = await fixture(false);
  f.control.nativeAdminIdentity = async () => "admin";
  for (const data of [{ enabled: "false", expectedRevision: 0 }, { enabled: false },
    { enabled: false, expectedRevision: -1 }]) {
    assert.equal((await f.control.fetch(request(data))).status, 400);
  }
  assert.equal((await f.control.fetch(request({ enabled: true, expectedRevision: 0 }))).status, 409);
  assert.equal((await cleanupSettings(f.control)).enabled, false);
});

const pair = await generateKeyPair("RS256");
const env = { SERVICE_ACCOUNT_JSON: { project_id: "bingo-test" } };
const googleId = "123456789012345678901";
const audiences = new Set([await digest("google_" + googleId)]);
async function signed(overrides = {}, signingKey = pair.privateKey) {
  const now = Math.floor(Date.now() / 1000);
  return new SignJWT({ auth_time: now - 10,
    firebase: { sign_in_provider: "google.com", identities: { "google.com": [googleId] } },
    iss: "https://securetoken.google.com/bingo-test", aud: "bingo-test", sub: "firebase-user",
    iat: now - 5, exp: now + 3600, ...overrides })
    .setProtectedHeader({ alg: "RS256", kid: "test-key" }).sign(signingKey);
}
async function verify(overrides = {}, options = {}) {
  const token = await signed(overrides);
  return verifyNativeAdmin(new Request("https://admin.test", {
    headers: { Authorization: "Bearer " + token } }), env, { keys: pair.publicKey, audiences, ...options });
}
test("native admin: signed Firebase Google identity maps to account UID policy", async () => {
  assert.equal(await verify(), await digest("google_" + googleId));
  assert.equal(NATIVE_ADMIN_AUDIENCES.size, 2);
});
test("native admin: valid signature from wrong project/issuer or expired/future token is rejected", async () => {
  for (const claims of [{ aud: "other-project" }, { iss: "https://attacker.test" }, { exp: 1 },
    { iat: Math.floor(Date.now() / 1000) + 100 }, { auth_time: Math.floor(Date.now() / 1000) + 100 }]) {
    await assert.rejects(verify(claims));
  }
});
test("native admin: other Google accounts, anonymous sign-in and forged signatures are rejected", async () => {
  await assert.rejects(verify({}, { audiences: new Set() }));
  await assert.rejects(verify({ firebase: { sign_in_provider: "anonymous" } }));
  const wrong = await generateKeyPair("RS256");
  const token = await signed({}, wrong.privateKey);
  await assert.rejects(verifyNativeAdmin(new Request("https://admin.test", {
    headers: { Authorization: "Bearer " + token } }), env, { keys: pair.publicKey, audiences }));
});

test("native admin: direct Google ID tokens support existing Android sign-in without a Firebase bridge", async () => {
  const now = Math.floor(Date.now() / 1000);
  const token = await signed({ iss: "https://accounts.google.com", sub: googleId,
    aud: "957593338442-ol5p4icr1a8e15m8lb1ln5mab5n1r5lp.apps.googleusercontent.com",
    iat: now - 5, exp: now + 300 });
  const identity = await verifyNativeAdmin(new Request("https://admin.test", {
    headers: { Authorization: "Bearer " + token } }), {}, { googleKeys: pair.publicKey, audiences });
  assert.equal(identity, await digest("google_" + googleId));
});
test("native admin: direct Google flow rejects wrong audience, expiry and unauthorized Google accounts", async () => {
  const valid = { iss: "https://accounts.google.com", sub: googleId,
    aud: "957593338442-ol5p4icr1a8e15m8lb1ln5mab5n1r5lp.apps.googleusercontent.com" };
  for (const change of [{ aud: "another-client" }, { exp: 1 }, { sub: "999999999999999999999" }]) {
    const token = await signed({ ...valid, ...change });
    await assert.rejects(verifyNativeAdmin(new Request("https://admin.test", {
      headers: { Authorization: "Bearer " + token } }), {}, { googleKeys: pair.publicKey, audiences }));
  }
});
