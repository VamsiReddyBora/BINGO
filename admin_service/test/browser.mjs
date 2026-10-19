import http from "node:http";
import fs from "node:fs/promises";
import path from "node:path";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { AdminControl } from "../worker.js";
import worker from "../worker.js";
import { passwordHash } from "../model.js";
import { MemoryStorage, FakeLegacy } from "./fixtures.js";

const require = createRequire(import.meta.url);
const { chromium } = require(
  process.env.PLAYWRIGHT_MODULE ||
    "/home/bob/workspace/bingo-admin-review-20261019/browser/node_modules/playwright",
);
const root = path.resolve(import.meta.dirname, "../../admin");
const evidence =
  process.env.ADMIN_EVIDENCE ||
  "/home/bob/workspace/bingo-admin-review-20261019/new-console";
await fs.mkdir(evidence, { recursive: true });
const server = http.createServer(async (req, res) => {
  try {
    const file = path.resolve(
      root,
      "." +
        new URL(req.url, "http://localhost").pathname.replace(
          "/BINGO/admin",
          "",
        ),
    );
    if (!file.startsWith(root + "/")) throw Error();
    const data = await fs.readFile(
      file.endsWith("/") ? file + "index.html" : file,
    );
    res.setHeader(
      "Content-Type",
      file.endsWith(".js")
        ? "text/javascript"
        : file.endsWith(".css")
          ? "text/css"
          : file.endsWith(".png")
            ? "image/png"
            : "text/html",
    );
    res.end(data);
  } catch {
    res.statusCode = 404;
    res.end("Missing");
  }
});
await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
const origin = "http://127.0.0.1:" + server.address().port;
const storage = new MemoryStorage(),
  legacy = new FakeLegacy();
legacy.players.get("kevin").profile.displayName = "Kevin O'Connor";
legacy.players.get("kevin").profile.avatarUrl =
  'x" onerror="window.untrustedAvatar=true';
legacy.players.get("kevin").profile.email = "kevin@fictional.test";
let failPresence = false,
  delayKevin = false;
const read = legacy.read.bind(legacy);
legacy.read = async (n) => {
  if (delayKevin && n === "kevin") await new Promise((r) => setTimeout(r, 600));
  return read(n);
};
const password = "browser-fixture-password-123";
const env = {
  ALLOWED_ORIGIN: origin,
  PUBLIC_URL: "https://bingo-admin-control.vamsireddy2534.workers.dev",
  ADMIN_CREDENTIALS: JSON.stringify({
    operator: "fixture-owner",
    salt: "fixture-salt",
    hash: await passwordHash(password, "fixture-salt"),
  }),
  TEST_LEGACY: legacy,
  TEST_BROKER: async () => {
    if (failPresence) throw Error("offline");
    return {
      connected: true,
      registry: {},
      rooms: {
        LIVE: JSON.stringify({
          status: "PLAYING",
          players: ["kevin", "stuart"],
          lastHeartbeat: Date.now(),
        }),
      },
      presence: {
        kevin: JSON.stringify({
          status: "PLAYING",
          timestamp: Date.now(),
          activityDetail: "Online 1v1",
        }),
        stuart: JSON.stringify({ status: "IN_LOBBY", timestamp: Date.now() }),
        bob: JSON.stringify({ status: "OFFLINE", timestamp: 0 }),
      },
    };
  },
};
const control = new AdminControl({ storage }, env);
env.ADMIN = { idFromName: (x) => x, get: () => control };
const browser = await chromium.launch({ headless: true });
const context = await browser.newContext({
  viewport: { width: 1440, height: 1050 },
  acceptDownloads: true,
});
const errors = [],
  results = [];
await context.route(
  "https://bingo-admin-control.vamsireddy2534.workers.dev/**",
  async (route) => {
    const r = route.request();
    const response = await worker.fetch(
      new Request(r.url(), {
        method: r.method(),
        headers: r.headers(),
        body: ["GET", "HEAD"].includes(r.method()) ? undefined : r.postData(),
      }),
      env,
    );
    await route.fulfill({
      status: response.status,
      headers: Object.fromEntries(response.headers),
      body: Buffer.from(await response.arrayBuffer()),
    });
  },
);
await context.route("https://example.test/**", (route) =>
  route.fulfill({ status: 404, body: "" }),
);
const page = await context.newPage();
page.on("pageerror", (e) => errors.push(e.message));
async function check(name, run) {
  await run();
  results.push({ case: name, status: "PASS", at: new Date().toISOString() });
  console.log("PASS " + name);
}
const visible = (selector) =>
  page.locator(selector).waitFor({ state: "visible" });
async function login() {
  await page.locator("#password").fill(password);
  await page.locator("#sign-in").click();
  await visible("#console");
  await page.waitForFunction(
    () => document.querySelector("#nav-count").textContent === "3",
  );
}
async function inspect(name) {
  await page
    .getByRole("button", { name: "Inspect @" + name, exact: true })
    .click();
  await page.locator("#inspector-body input[name=displayName]").waitFor();
}
async function confirm(typed) {
  await visible("#confirmation");
  if (typed) await page.locator("#confirmation-input").fill(typed);
  await page.locator("#confirmation-submit").click();
  await page.locator("#confirmation").waitFor({ state: "hidden" });
}
async function close() {
  if (await page.locator("#inspector").isVisible())
    await page.locator("#inspector-close").click();
}
try {
  await page.goto(origin + "/BINGO/admin/index.html");
  await check(
    "Server login required despite legacy localStorage auth flag",
    async () => {
      await page.evaluate(() =>
        localStorage.setItem("bingo_admin_auth", "true"),
      );
      await page.reload();
      assert.equal(await page.locator("#console").isVisible(), false);
    },
  );
  await check("Login and live player/status counts", async () => {
    await login();
    assert.match(
      await page.locator("#statistics").innerText(),
      /Registered players\s+3/,
    );
    assert.match(await page.locator("#player-list").innerText(), /Online 1v1/);
  });
  await page.screenshot({
    path: evidence + "/01-desktop-dark.png",
    fullPage: true,
  });
  await check("Unsafe photo values are not inserted as HTML", async () => {
    assert.equal(await page.evaluate(() => window.untrustedAvatar), undefined);
    assert.equal(await page.locator('img[src^="x"]').count(), 0);
  });
  await check("Filtered CSV exports only matching players", async () => {
    await page.locator("#search").fill("stuart");
    const downloadPromise = page.waitForEvent("download");
    await page.locator("#export").click();
    const download = await downloadPromise;
    const csv = await fs.readFile(await download.path(), "utf8");
    assert.match(csv, /stuart/);
    assert.doesNotMatch(csv, /kevin|bob/);
    await page.locator("#search").fill("");
  });
  await check(
    "Name and streak changes repaint even when game totals do not change",
    async () => {
      legacy.players.get("kevin").profile.displayName = "Renamed Kevin";
      legacy.players.get("kevin").profile.currentStreak = 2;
      await page.locator("#refresh").click();
      await page.waitForFunction(() =>
        document
          .querySelector("#player-list")
          .textContent.includes("Renamed Kevin"),
      );
      assert.equal(legacy.players.get("kevin").profile.gamesPlayed, 10);
      legacy.players.get("kevin").profile.displayName = "Kevin O'Connor";
      await page.locator("#refresh").click();
      await page.waitForFunction(() =>
        document
          .querySelector("#player-list")
          .textContent.includes("Kevin O'Connor"),
      );
    },
  );
  await check(
    "Profile/statistics save executes actual service mutation",
    async () => {
      await inspect("kevin");
      await page
        .locator("#inspector-body input[name=displayName]")
        .fill("Kevin Updated");
      await page.getByRole("button", { name: "Save profile & stats" }).click();
      await confirm();
      await page.waitForFunction(
        () =>
          document.querySelector("#inspector-body input[name=displayName]")
            ?.value === "Kevin Updated",
      );
      assert.equal(
        legacy.players.get("kevin").profile.displayName,
        "Kevin Updated",
      );
    },
  );
  await check(
    "Failed writes visibly remain pending and retry successfully",
    async () => {
      legacy.failWrite = true;
      await page
        .locator("#inspector-body input[name=displayName]")
        .fill("Pending Edit");
      await page.getByRole("button", { name: "Save profile & stats" }).click();
      await confirm();
      await page.waitForFunction(() =>
        document.querySelector("#toasts").textContent.includes("pending"),
      );
      assert.notEqual(
        legacy.players.get("kevin").profile.displayName,
        "Pending Edit",
      );
      legacy.failWrite = false;
      await control.alarm();
      assert.equal(
        legacy.players.get("kevin").profile.displayName,
        "Pending Edit",
      );
      await page.locator("#inspector-refresh").click();
      await page.locator("#inspector-body input[name=displayName]").waitFor();
    },
  );
  await check(
    "Upload photo is resized, synchronized and displayed",
    async () => {
      const png = await page.evaluate(() => {
        const c = document.createElement("canvas");
        c.width = 32;
        c.height = 32;
        const ctx = c.getContext("2d");
        ctx.fillStyle = "#23bc89";
        ctx.fillRect(0, 0, 32, 32);
        return c.toDataURL("image/png").split(",")[1];
      });
      await page.locator("input[type=file]").setInputFiles({
        name: "fixture.png",
        mimeType: "image/png",
        buffer: Buffer.from(png, "base64"),
      });
      await confirm();
      await page.waitForFunction(
        () => !!document.querySelector("#inspector-body .avatar.large img"),
      );
      assert.ok(legacy.players.get("kevin").profile.avatarBase64);
    },
  );
  await check(
    "Remove photo does not fall back to the previous URL",
    async () => {
      await page
        .getByRole("button", { name: "Remove photo", exact: true })
        .click();
      await confirm();
      await page.waitForFunction(
        () =>
          !!document.querySelector("#inspector-body .avatar.large") &&
          !document.querySelector("#inspector-body .avatar.large img"),
      );
      assert.equal(legacy.players.get("kevin").profile.avatarBase64, null);
      assert.equal(legacy.players.get("kevin").profile.avatarUrl, null);
    },
  );
  await check("Adding and removing friends updates both accounts", async () => {
    await page.locator("#inspector-tabs button[data-section=friends]").click();
    await page
      .getByRole("combobox", { name: "Choose player to add as friend" })
      .selectOption("stuart");
    await page
      .getByRole("button", { name: "Add to both friends lists" })
      .click();
    await confirm();
    await page.waitForFunction(() =>
      document
        .querySelector("#inspector-body")
        ?.textContent.includes("1 friends"),
    );
    assert.equal(legacy.rosters.get("stuart")[0].username, "kevin");
    await page
      .locator(".friend-row")
      .getByRole("button", { name: "Remove", exact: true })
      .click();
    await confirm();
    await page.waitForFunction(() =>
      document
        .querySelector("#inspector-body")
        ?.textContent.includes("No active friendships"),
    );
    assert.deepEqual(legacy.rosters.get("stuart"), []);
  });
  await check(
    "Match deletion handles apostrophes and persists tombstone",
    async () => {
      await page
        .locator("#inspector-tabs button[data-section=matches]")
        .click();
      await page
        .getByRole("button", { name: "Delete match m1", exact: true })
        .click();
      await confirm();
      await page.waitForFunction(
        () => !document.querySelector('[aria-label="Delete match m1"]'),
      );
      assert.equal(
        (await control.policy("kevin")).deletedMatches.includes("m1"),
        true,
      );
    },
  );
  await check("Playing room cannot be closed as stale lobby", async () => {
    await close();
    await page.locator("#navigation button[data-tab=rooms]").click();
    assert.equal(
      await page
        .getByRole("button", { name: "Close lobby", exact: true })
        .isDisabled(),
      true,
    );
  });
  await check(
    "Inspector latest selection wins over delayed previous request",
    async () => {
      await page.locator("#navigation button[data-tab=players]").click();
      delayKevin = true;
      await page
        .getByRole("button", { name: "Inspect @kevin", exact: true })
        .click();
      await page.locator("#inspector-close").click();
      await inspect("stuart");
      await page.waitForTimeout(800);
      assert.equal(
        await page
          .locator("#inspector-body input[name=displayName]")
          .inputValue(),
        "stuart",
      );
      assert.doesNotMatch(
        await page.locator("#inspector-body").innerText(),
        /kevin@fictional/,
      );
      delayKevin = false;
      await close();
    },
  );
  await check(
    "Missing directory marks snapshot stale without rewriting database",
    async () => {
      const before = legacy.writes.length;
      legacy.failDirectory = true;
      await page.locator("#refresh").click();
      await page.waitForFunction(() =>
        document
          .querySelector("#data-warning")
          .textContent.includes("last verified snapshot"),
      );
      assert.equal(legacy.writes.length, before);
      assert.equal(
        await page.locator("#player-list .badge.unknown").count(),
        3,
      );
      legacy.failDirectory = false;
      await page.locator("#refresh").click();
    },
  );
  await check("Service worker preserves the separate game cache", async () => {
    await page.evaluate(async () => {
      await caches.open("bingo-game-fixture");
      await navigator.serviceWorker.ready;
    });
    await page.reload();
    await visible("#console");
    assert.ok(
      (await page.evaluate(() => caches.keys())).includes("bingo-game-fixture"),
    );
  });
  await check("Mobile layout has no horizontal document overflow", async () => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(150);
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: evidence + "/02-mobile-dark.png",
      fullPage: true,
    });
  });
  await check("Light theme and mobile inspector render", async () => {
    await page.locator("#theme").click();
    await inspect("bob");
    assert.equal(
      await page.evaluate(() => document.documentElement.dataset.theme),
      "light",
    );
    assert.equal(
      await page.evaluate(
        () =>
          document.querySelector("#inspector").getBoundingClientRect().width <=
          innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: evidence + "/03-mobile-inspector-light.png",
      fullPage: true,
    });
    await close();
  });
  await check(
    "Typed deletion removes player from live directory and detail",
    async () => {
      await inspect("bob");
      await page.locator("#inspector-tabs button[data-section=danger]").click();
      await page
        .getByRole("button", { name: "Remove @bob", exact: true })
        .click();
      await confirm("bob");
      await page.waitForFunction(
        () => document.querySelector("#nav-count").textContent === "2",
      );
      assert.equal(legacy.players.has("bob"), false);
      assert.equal((await control.policy("bob")).deleted, true);
    },
  );
  await check(
    "Lock clears player data and invalidates server session",
    async () => {
      const saved = await page.evaluate(() =>
        JSON.parse(sessionStorage.getItem("bingo_admin_session_v3")),
      );
      await page.locator("#lock").click();
      await visible("#login");
      assert.equal(await page.locator("#player-list").innerText(), "");
      const response = await control.fetch(
        new Request("https://admin.test/api/players", {
          headers: { Authorization: "Bearer " + saved.token },
        }),
      );
      assert.equal(response.status, 401);
    },
  );
  assert.deepEqual(errors, []);
  results.push({
    case: "No uncaught browser JavaScript errors",
    status: "PASS",
  });
} catch (error) {
  results.push({ case: "Browser run", status: "FAIL", error: error.stack });
  await page.screenshot({ path: evidence + "/failure.png", fullPage: true });
  throw error;
} finally {
  await fs.writeFile(
    evidence + "/results.json",
    JSON.stringify(
      {
        timestamp: new Date().toISOString(),
        results,
        errors,
        productionDataMutated: false,
      },
      null,
      2,
    ),
  );
  await browser.close();
  server.close();
}
