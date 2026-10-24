import assert from "node:assert/strict";
import { pathToFileURL } from "node:url";
import path from "node:path";
const root =
  process.env.ADMIN_SOURCE_ROOT || path.resolve(import.meta.dirname, "..");
const { AdminControl } = await import(
  pathToFileURL(path.join(root, "worker.js"))
);
const { brokerExchange, mqttPublish } = await import(
  pathToFileURL(path.join(root, "mqtt.js"))
);
const { passwordHash } = await import(
  pathToFileURL(path.join(root, "model.js"))
);
const { MemoryStorage, FakeLegacy } = await import("./fixtures.js");
const { TestAuthenticator, enroll } = await import("./authenticator.js");
let failures = 0;
async function check(name, run) {
  try {
    await run();
    console.log("PASS " + name);
  } catch (e) {
    failures++;
    console.log("FAIL " + name + ": " + e.message);
  }
}
await check(
  "current MQTT metadata topic reaches the room snapshot",
  async () => {
    const listeners = new Map();
    const emit = (value) => listeners.get("message")?.({ data: value });
    const socket = {
      addEventListener: (n, f) => listeners.set(n, f),
      accept() {},
      close() {},
      send(value) {
        const type = value[0] >> 4;
        if (type === 1) queueMicrotask(() => emit(Uint8Array.of(32, 2, 0, 0)));
        if (type === 8)
          queueMicrotask(() => {
            emit(Uint8Array.of(144, 6, 0, 1, 0, 0, 0, 0));
            emit(
              mqttPublish(
                "bingo/emqx7/metadata/ABCDE/state",
                JSON.stringify({
                  roomCode: "ABCDE",
                  mv: 7,
                  r: 9,
                  sessionId: "fixture",
                  status: "WAITING",
                }),
                true,
                20,
              ),
            );
          });
      },
    };
    const result = await brokerExchange(
      {
        MQTT_USERNAME: "fixture",
        MQTT_PASSWORD: "fixture",
        MQTT_URL: "wss://fixture.test",
      },
      { snapshot: true },
      async () => ({ webSocket: socket }),
    );
    assert.ok(
      result.rooms.ABCDE,
      "Current app room is missing from admin observations",
    );
    assert.equal(JSON.parse(result.rooms.ABCDE).metadataProtocolVersion, 7);
  },
);
await check(
  "release inspection uses the current primary feed instead of legacy metadata",
  async () => {
    const storage = new MemoryStorage(),
      legacy = new FakeLegacy();
    const env = {
      TEST_LEGACY: legacy,
      TEST_RELEASES: {
        feed: async () => ({
          release: { versionName: "1.0.3", versionCode: 70 },
          source: "GitHub Pages source",
        }),
      },
      ALLOWED_ORIGIN: "https://fixture.test",
      PUBLIC_URL: "https://fixture.admin",
      ADMIN_CREDENTIALS: JSON.stringify({
        operator: "fixture",
        salt: "fixture",
        hash: await passwordHash("fixture-password-123", "fixture"),
      }),
    };
    const control = new AdminControl({ storage }, env),
      authenticator = new TestAuthenticator(env.ALLOWED_ORIGIN);
    const login = await control.fetch(
      new Request("https://fixture.admin/api/login", {
        method: "POST",
        body: JSON.stringify({ password: "fixture-password-123" }),
      }),
    );
    const token = (
      await enroll(control, (await login.json()).token, authenticator)
    ).token;
    const response = await control.fetch(
      new Request("https://fixture.admin/api/release", {
        headers: { Authorization: "Bearer " + token },
      }),
    );
    assert.equal(response.status, 200);
    assert.equal((await response.json()).release.versionCode, 70);
  },
);
process.exitCode = failures ? 1 : 0;
