import http from "node:http";
import fs from "node:fs/promises";
import path from "node:path";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { AdminControl } from "../worker.js";
import worker from "../worker.js";
import { releaseFixture } from "./publication-fixture.js";
import { contentFixture } from "./content-fixture.js";
import { targetedReleaseFixture } from "./targeted-release-fixture.js";
import { passwordHash, digest } from "../model.js";
import { MemoryStorage, FakeLegacy } from "./fixtures.js";

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || "playwright");
const root = path.resolve(import.meta.dirname, "../../admin");
const evidence =
  process.env.ADMIN_EVIDENCE ||
  path.resolve(import.meta.dirname, "../../browser-evidence");
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
const origin = "http://localhost:" + server.address().port;
const storage = new MemoryStorage(),
  legacy = new FakeLegacy();
legacy.players.get("kevin").profile.displayName = "Kevin O'Connor";
legacy.players.get("kevin").profile.avatarUrl =
  'x" onerror="window.untrustedAvatar=true';
legacy.players.get("kevin").profile.email = "kevin@fictional.test";
let failPresence = false,
  delayKevin = false,
  versionEvidence = false;
const read = legacy.read.bind(legacy);
legacy.read = async (n) => {
  if (delayKevin && n === "kevin") await new Promise((r) => setTimeout(r, 600));
  return read(n);
};
const password = "browser-fixture-password-123";
const publication = releaseFixture();
const contentPublication = contentFixture();
const selectedPublication = targetedReleaseFixture();
const cleanupArchive=new Map(), cleanupRecords={}, cleanupWrites=[];
legacy.firestoreGet=async path=>cleanupArchive.get(path)||null;
legacy.firestoreSet=async(path,value)=>{assert.ok(path.startsWith('mqttMaintenanceArchives/'));cleanupArchive.set(path,value);};
legacy.recentMatches = async () => [
  {
    id: "fixture-match",
    ownerUid: "uid_kevin",
    opponentName: "Stuart",
    timestamp: Date.now(),
    didWin: true,
    mode: "ONLINE",
  },
];
const env = {
  ALLOWED_ORIGIN: origin,
  PUBLIC_URL: "https://bingo-admin-control.vamsireddy2534.workers.dev",
  ADMIN_CREDENTIALS: JSON.stringify({
    operator: "fixture-owner",
    salt: "fixture-salt",
    hash: await passwordHash(password, "fixture-salt"),
  }),
  TEST_LEGACY: legacy,
  TEST_RELEASES: publication.publisher,
  TEST_CONTENT: contentPublication.publisher,
  TEST_TARGETED_RELEASES: selectedPublication.publisher,
  TEST_BROKER: async (args) => {
    if (failPresence) throw Error("offline");
    if(args?.rawFilters) {
      if(args.guardedGroups){for(const g of args.guardedGroups)for(const t of g.topics){cleanupWrites.push(t);delete cleanupRecords[t];}return{cleanup:{acknowledged:[...cleanupWrites],skipped:0}};}
      return{retainedRecords:structuredClone(cleanupRecords)};
    }
    return {
      connected: true,
      registry: {},
      rooms: {
        CURRENT: JSON.stringify({
          mv: 7,
          r: 9,
          roomCode: "CURRENT",
          sessionId: "fixture-session",
          hostUsername: "kevin",
          hostDisplayName: "Kevin",
          status: "WAITING",
          boardSize: 5,
          players: [{ username: "kevin", name: "Kevin", isHost: true }],
          lastHeartbeat: Date.now(),
        }),
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
          ...(versionEvidence
            ? { appVersion: "1.0.4", appVersionCode: 74 }
            : {}),
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
let lastToken,
  approvedMutations = 0,
  sessionReads = 0,
  passkeyLogins = 0,
  failSession = false,
  playerReads = 0;
const errors = [],
  results = [];
await context.route(
  "https://bingo-admin-control.vamsireddy2534.workers.dev/**",
  async (route) => {
    const r = route.request();
    if (new URL(r.url()).pathname === "/api/players") playerReads++;
    if (new URL(r.url()).pathname === "/api/session") {
      sessionReads++;
      if (failSession) {
        await route.fulfill({
          status: 503,
          headers: { "Access-Control-Allow-Origin": origin },
          contentType: "application/json",
          body: JSON.stringify({ error: "Temporary test outage" }),
        });
        return;
      }
    }
    if (new URL(r.url()).pathname === "/api/auth/login/options")
      passkeyLogins++;
    if (r.headers().authorization)
      lastToken = r.headers().authorization.slice(7);
    if (r.headers()["x-action-proof"]) approvedMutations++;
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
await context.addInitScript(() =>
  localStorage.setItem("bingo_admin_theme_v4", "light"),
);
const page = await context.newPage();
const cdp = await context.newCDPSession(page);
await cdp.send("WebAuthn.enable");
const { authenticatorId } = await cdp.send("WebAuthn.addVirtualAuthenticator", {
  options: {
    protocol: "ctap2",
    transport: "internal",
    hasResidentKey: true,
    hasUserVerification: true,
    isUserVerified: true,
    automaticPresenceSimulation: true,
  },
});
page.on("pageerror", (e) => errors.push(e.message));
async function check(name, run) {
  await run();
  results.push({ case: name, status: "PASS", at: new Date().toISOString() });
  console.log("PASS " + name);
}
const visible = (selector) =>
  page.locator(selector).waitFor({ state: "visible" });
async function login() {
  if (!(await control.security.keys()).length) {
    await page.locator("#password").fill(password);
    await page.locator("#sign-in").click();
    await visible("#enrollment-panel");
    assert.equal(await page.locator("#console").isVisible(), false);
    await page.locator("#enroll-passkey").click();
    await visible("#recovery-codes-dialog");
    assert.equal(await page.locator("#recovery-code-list code").count(), 8);
    const downloadPromise = page.waitForEvent("download");
    await page.locator("#download-recovery-codes").click();
    const download = await downloadPromise;
    assert.match(
      await fs.readFile(await download.path(), "utf8"),
      /[a-f0-9]{8}(?:-[a-f0-9]{8}){3}/,
    );
    await page.locator("#recovery-codes-done").click();
  } else await page.locator("#passkey-sign-in").click();
  await visible("#console");
  await page.waitForFunction(
    () => document.querySelector("#nav-count").textContent === "3",
  );
}
async function refreshDirectory() {
  await page.locator("#refresh").click();
  await page.waitForFunction(()=>!document.querySelector("#refresh").disabled);
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
  const mutation = page.waitForResponse(
    (r) =>
      r.url().includes("/api/") &&
      !r.url().includes("/api/auth/") &&
      !["GET", "OPTIONS"].includes(r.request().method()),
  );
  await page.locator("#confirmation-submit").click();
  await mutation;
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
  await page.screenshot({ path: evidence + "/00-login.png", fullPage: true });
  await check(
    "First enrollment, recovery codes, login and live counts",
    async () => {
      await login();
      assert.match(
        await page.locator("#statistics").innerText(),
        /Registered players\s+3/,
      );
      assert.match(
        await page.locator("#player-list").innerText(),
        /Online 1v1/,
      );
    },
  );
  const usabilityCases = [
    [
      "Version name and build stay paired from one report",
      async () => {
        const profile = legacy.players.get("kevin").profile;
        profile.appVersion = "1.0.3";
        profile.appVersionCode = 71;
        versionEvidence = true;
        try {
          await refreshDirectory();
          const row = page.locator("#player-list tr").filter({
            has: page.getByRole("button", {
              name: "Inspect @kevin",
              exact: true,
            }),
          });
          await row
            .getByText("1.0.4 · Build 74", { exact: true })
            .waitFor({ timeout: 3000 });
          assert.doesNotMatch(await row.innerText(), /1\.0\.4 · (?:Build )?71/);
        } finally {
          versionEvidence = false;
          delete profile.appVersion;
          delete profile.appVersionCode;
          await refreshDirectory();
        }
      },
    ],
    [
      "Missing presence time uses a clear activity label",
      async () => {
        await page.locator("#card-view").click();
        assert.doesNotMatch(
          await page.locator("#player-list").innerText(),
          /Status observed No observation/,
        );
        await page.locator("#table-view").click();
      },
    ],
    [
      "Dark page and navigation use pure black",
      async () => {
        await page.locator("#theme").click();
        try {
          const colors = await page.evaluate(() => [
            getComputedStyle(document.body).backgroundColor,
            getComputedStyle(document.querySelector(".sidebar"))
              .backgroundColor,
          ]);
          assert.deepEqual(colors, ["rgb(0, 0, 0)", "rgb(0, 0, 0)"]);
          await page.screenshot({
            path: evidence + "/11-pure-black-desktop.png",
            fullPage: true,
            animations: "disabled",
          });
        } finally {
          await page.locator("#theme").click();
        }
      },
    ],
    [
      "Refresh restores the server-validated session without another passkey",
      async () => {
        const reads = sessionReads,
          logins = passkeyLogins;
        await page.reload();
        try {
          await page
            .locator("#console")
            .waitFor({ state: "visible", timeout: 3000 });
          assert.ok(sessionReads > reads);
          assert.equal(passkeyLogins, logins);
          const remembered = await page.evaluate(() =>
            JSON.parse(sessionStorage.getItem("bingo_admin_session_v4")),
          );
          assert.equal(remembered.token, lastToken);
          await page.waitForFunction(
            () => document.querySelector("#nav-count").textContent === "3",
          );
        } finally {
          if (!(await page.locator("#console").isVisible())) await login();
        }
      },
    ],
  ];
  for (const [name, run] of usabilityCases) {
    try {
      await check(name, run);
    } catch (error) {
      if (!process.env.ADMIN_USABILITY_BEFORE) throw error;
      results.push({ case: name, status: "FAIL", error: error.message });
      console.log("CONFIRMED BEFORE FAILURE " + name + ": " + error.message);
      await page.locator("#table-view").click();
    }
  }
  if (process.env.ADMIN_USABILITY_BEFORE)
    throw Object.assign(Error("Before-fix reproduction completed"), {
      baselineComplete: true,
    });
  await check(
    "Build filters, sync filters, status counts and reset operate on the directory",
    async () => {
      const originals = new Map(
        [...legacy.players].map(([n, r]) => [n, { ...r.profile }]),
      );
      for (const [n, code] of [
        ["kevin", 74],
        ["stuart", 73],
        ["bob", 71],
      ])
        Object.assign(legacy.players.get(n).profile, {
          appVersion: "1.0.3",
          appVersionCode: code,
        });
      try {
        await refreshDirectory();
        await page.locator("#search").fill("@KEVIN");
        assert.equal(await page.locator("#player-list tbody tr").count(), 1);
        await page.locator("#search").fill("");
        await page.locator("#build-filter").selectOption("74");
        assert.equal(await page.locator("#player-list tbody tr").count(), 1);
        assert.match(
          await page.locator("#player-list").innerText(),
          /Build 74/,
        );
        legacy.players.get("kevin").profile.appVersionCode = 75;
        await refreshDirectory();
        assert.equal(await page.locator("#build-filter").inputValue(), "74");
        assert.equal(await page.locator("#player-list tbody tr").count(), 0);
        legacy.players.get("kevin").profile.appVersionCode = 74;
        await refreshDirectory();
        await page.locator("#sync-filter").selectOption("NONE");
        assert.equal(await page.locator("#player-list tbody tr").count(), 1);
        await page.locator("#reset-filters").click();
        await page
          .locator("#statistics button")
          .filter({ hasText: "Playing" })
          .click();
        assert.equal(
          await page.locator("#status-filter").inputValue(),
          "PLAYING",
        );
        assert.equal(await page.locator("#player-list tbody tr").count(), 1);
        await page.locator("#reset-filters").click();
        assert.equal(await page.locator("#player-list tbody tr").count(), 3);
      } finally {
        for (const [n, p] of originals) legacy.players.get(n).profile = p;
        await refreshDirectory();
      }
    },
  );
  await check(
    "Temporary session-check outage keeps data hidden and permits retry without a passkey",
    async () => {
      failSession = true;
      const logins = passkeyLogins;
      await page.reload();
      await page.locator("#restore-session").waitFor({ state: "visible" });
      assert.equal(await page.locator("#console").isVisible(), false);
      assert.ok(
        await page.evaluate(() =>
          sessionStorage.getItem("bingo_admin_session_v4"),
        ),
      );
      assert.equal(passkeyLogins, logins);
      failSession = false;
      await page.locator("#restore-session").click();
      await page.locator("#console").waitFor({ state: "visible" });
      await page.waitForFunction(
        () => document.querySelector("#nav-count").textContent === "3",
      );
      assert.equal(passkeyLogins, logins);
    },
  );
  await check(
    "Expired server session locks on refresh without automatic verification prompts",
    async () => {
      const key = "session:" + (await digest(lastToken)),
        saved = await storage.get(key),
        logins = passkeyLogins;
      await storage.put(key, { ...saved, expiresAt: Date.now() - 1 });
      await page.reload();
      await page.locator("#passkey-sign-in").waitFor({ state: "visible" });
      assert.equal(await page.locator("#console").isVisible(), false);
      assert.equal(
        await page.evaluate(() =>
          sessionStorage.getItem("bingo_admin_session_v4"),
        ),
        null,
      );
      assert.equal(passkeyLogins, logins);
      await login();
    },
  );
  await check(
    "A forged stored token cannot expose player data before server validation",
    async () => {
      await page.evaluate(() =>
        sessionStorage.setItem(
          "bingo_admin_session_v4",
          JSON.stringify({
            token: "f".repeat(64),
            expiresAt: Date.now() + 60000,
            setupRequired: false,
            authMethod: "passkey",
          }),
        ),
      );
      const reads = playerReads;
      await page.reload();
      await page.locator("#passkey-sign-in").waitFor({ state: "visible" });
      assert.equal(await page.locator("#console").isVisible(), false);
      assert.equal(playerReads, reads);
      assert.equal(
        await page.evaluate(() =>
          sessionStorage.getItem("bingo_admin_session_v4"),
        ),
        null,
      );
      await login();
    },
  );
  await check(
    "Disabled browser storage retains a working in-memory authenticated console",
    async () => {
      await page.addInitScript(() => {
        if (new URL(location.href).searchParams.has("storageBlocked"))
          for (const key of ["sessionStorage", "localStorage"])
            Object.defineProperty(window, key, {
              get() {
                throw new DOMException("Storage disabled", "SecurityError");
              },
            });
      });
      await page.goto(origin + "/BINGO/admin/index.html?storageBlocked=1");
      await page.locator("#passkey-sign-in").waitFor({ state: "visible" });
      await login();
      assert.equal(await page.locator("#console").isVisible(), true);
      await page.goto(origin + "/BINGO/admin/index.html");
      await page.locator("#console").waitFor({ state: "visible" });
      await page.waitForFunction(
        () => document.querySelector("#nav-count").textContent === "3",
      );
    },
  );
  await check(
    "Player context shows status, paired version and activity without null placeholders",
    async () => {
      await inspect("bob");
      const text = await page.locator("#player-context").innerText();
      assert.match(text, /Offline/);
      assert.doesNotMatch(text, /\bnull\b|No observation/);
      await close();
    },
  );

  await check(
    "Manual refresh ages activity and expires live status without network polling",
    async () => {
      await page.locator("#navigation button[data-tab=settings]").click();
      await page.locator("#refresh-interval").selectOption("0");
      await page.locator("#navigation button[data-tab=players]").click();
      await refreshDirectory();
      const reads = playerReads;
      await page.waitForTimeout(21000);
      assert.equal(playerReads, reads);
      assert.equal(
        await page.locator("#player-list .badge.unknown").count(),
        2,
      );
      assert.match(
        await page.locator("#statistics").innerText(),
        /Active in app\s+0/,
      );
      assert.match(
        await page.locator("#player-list").innerText(),
        /Last seen (?:1[5-9]|2[0-9])s ago/,
      );
      await page.locator("#navigation button[data-tab=settings]").click();
      await page.locator("#refresh-interval").selectOption("5000");
      await page.locator("#navigation button[data-tab=players]").click();
      await refreshDirectory();
    },
  );
  await page.screenshot({
    path: evidence + "/01-desktop-light.png",
    fullPage: true,
  });
  await check(
    "Overview shows real snapshot counts and desktop dark layout",
    async () => {
      await page.locator("#navigation button[data-tab=overview]").click();
      assert.match(
        await page.locator("#overview-metrics").innerText(),
        /Registered players\s+3/,
      );
      assert.match(
        await page.locator("#overview-health").innerText(),
        /Firebase verified/,
      );
      await page.locator("#theme").click();
      await page.screenshot({
        path: evidence + "/04-overview-dark.png",
        fullPage: true,
      });
      await page.locator("#theme").click();
      await page.locator("#navigation button[data-tab=players]").click();
    },
  );
  await check(
    "Current MQTT room aliases appear with safe inspection and filtering",
    async () => {
      await page.locator("#navigation button[data-tab=rooms]").click();
      await page.locator("#room-search").fill("CURRENT");
      assert.equal(await page.locator("#rooms article").count(), 1);
      await page.getByRole("button", { name: "Inspect room ↗" }).click();
      assert.match(
        await page.locator("#room-inspector-body").innerText(),
        /Protocol\s+7/,
      );
      assert.equal(
        await page.locator("#room-inspector-body button.danger").count(),
        0,
      );
      await page.screenshot({
        path: evidence + "/05-room-inspection.png",
        fullPage: true,
      });
      await page.locator("#room-inspector-close").click();
      await page.locator("#room-search").fill("");
      await page.locator("#navigation button[data-tab=players]").click();
    },
  );
  await check(
    "Recent match filters render the actual service results",
    async () => {
      await page.locator("#navigation button[data-tab=matches]").click();
      await page.getByText("fixture-match", { exact: true }).waitFor();
      await page.locator("#match-outcome").selectOption("LOSS");
      assert.equal(await page.locator(".match-record").count(), 0);
      await page.locator("#match-outcome").selectOption("WIN");
      assert.equal(await page.locator(".match-record").count(), 1);
      await page.screenshot({
        path: evidence + "/06-match-records.png",
        fullPage: true,
      });
      await page.locator("#navigation button[data-tab=players]").click();
    },
  );
  await check(
    "Broadcast requires approval and stores a single live notice",
    async () => {
      await page.locator("#navigation button[data-tab=announcements]").click();
      await page
        .locator("#announcement-content input[name=title]")
        .fill("Fixture broadcast");
      await page
        .locator("#announcement-content textarea[name=message]")
        .fill("Fictional browser verification only.");
      await page
        .getByRole("button", { name: "Review & publish", exact: true })
        .click();
      await confirm();
      await page.getByRole("heading", { name: "Fixture broadcast" }).waitFor();
      assert.equal(
        (await storage.get("announcement:ALL")).title,
        "Fixture broadcast",
      );
      await page.screenshot({
        path: evidence + "/07-broadcasts.png",
        fullPage: true,
      });
      await page.locator("#navigation button[data-tab=players]").click();
    },
  );
  await check(
    "Signed APK update form uses the protected production publication path",
    async () => {
      await page.locator("#navigation button[data-tab=release]").click();
      await page.locator("input[name=versionName]").fill("1.0.4");
      await page.locator("input[name=apk]").setInputFiles({
        name: "Bingo-fixture.apk",
        mimeType: "application/vnd.android.package-archive",
        buffer: Buffer.from(publication.input.apkBase64, "base64"),
      });
      await page.screenshot({
        path: evidence + "/08-updates.png",
        fullPage: true,
      });
      await page
        .getByRole("button", { name: "Review & publish update", exact: true })
        .click();
      await confirm();
      await page
        .getByRole("heading", { name: "v1.0.4", exact: true })
        .waitFor();
      assert.equal(publication.feed.versionCode, 71);
      await page.locator("#navigation button[data-tab=players]").click();
    },
  );
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
      await refreshDirectory();
      await page.waitForFunction(() =>
        document
          .querySelector("#player-list")
          .textContent.includes("Renamed Kevin"),
      );
      assert.equal(legacy.players.get("kevin").profile.gamesPlayed, 10);
      legacy.players.get("kevin").profile.displayName = "Kevin O'Connor";
      await refreshDirectory();
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
    "Cloud save waits for actual app receipt while compact inspector preserves all controls",
    async () => {
      await page.waitForFunction(() =>
        document
          .querySelector("#profile-sync")
          .textContent.includes("Waiting for app"),
      );
      assert.doesNotMatch(
        await page.locator("#profile-sync").textContent(),
        /Applied by app/,
      );
      assert.ok((await page.locator("#inspector").boundingBox()).width <= 510);
      assert.equal(
        await page.locator("#inspector-body .profile-form input").count(),
        7,
      );
      await page.locator("#inspector-body details summary").click();
      await page.getByText("uid_kevin", { exact: true }).waitFor();
      await page.screenshot({
        path: evidence + "/09-compact-inspector-waiting.png",
        fullPage: true,
      });
      await page
        .locator("#inspector-body input[name=displayName]")
        .fill("Unsaved local draft");
      const policy = await control.policy("kevin");
      Object.assign(legacy.players.get("kevin").profile, {
        adminAppliedRevision: policy.revision,
        adminAppliedAt: Date.now(),
        adminAppliedBuild: 72,
        appVersionCode: 72,
      });
      // The normal five-second directory refresh also runs with the inspector open.
      await page.waitForFunction(() =>
        document
          .querySelector("#profile-sync")
          .textContent.includes("Applied by app"),
      );
      assert.equal(
        await page
          .locator("#inspector-body input[name=displayName]")
          .inputValue(),
        "Unsaved local draft",
      );
      assert.match(
        await page.locator("#profile-sync").textContent(),
        /Build 72/,
      );
      await page.screenshot({
        path: evidence + "/10-app-receipt-confirmed.png",
        fullPage: true,
      });
      await page
        .locator("#inspector-body input[name=displayName]")
        .fill("Kevin Updated");
    },
  );
  await check(
    "Desktop navigation collapses without removing available sections",
    async () => {
      await page.locator("#inspector-close").click();
      await page.locator("#menu-button").click();
      assert.equal(
        await page.locator("body").getAttribute("data-nav"),
        "expanded",
      );
      assert.ok((await page.locator(".sidebar").boundingBox()).width <= 208);
      assert.equal(
        await page.locator("#navigation button[data-tab]").count(),
        8,
      );
      await page.locator("#menu-button").click();
      assert.equal(
        await page.locator("body").getAttribute("data-nav"),
        "compact",
      );
      assert.ok((await page.locator(".sidebar").boundingBox()).width <= 68);
      await inspect("kevin");
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
      await page.locator("#inspector input[type=file]").setInputFiles({
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
      await refreshDirectory();
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
      await refreshDirectory();
    },
  );
  await check("Service worker preserves the separate game cache", async () => {
    await page.evaluate(async () => {
      await caches.open("bingo-game-fixture");
      await navigator.serviceWorker.ready;
    });
    await page.reload();
    await page.locator("#console").waitFor({ state: "visible" });
    assert.ok(
      await page.evaluate(() =>
        sessionStorage.getItem("bingo_admin_session_v4"),
      ),
    );
    assert.equal(
      await page.evaluate(() =>
        sessionStorage.getItem("bingo_admin_session_v3"),
      ),
      null,
    );
    assert.ok(
      (await page.evaluate(() => caches.keys())).includes("bingo-game-fixture"),
    );
  });
  await check("Mobile layout has no horizontal document overflow", async () => {
    await page.setViewportSize({ width: 320, height: 740 });
    await page.waitForTimeout(100);
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(150);
    assert.equal(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
      true,
    );
    await page.screenshot({
      path: evidence + "/02-mobile-light.png",
      fullPage: true,
    });
  });
  await check("Dark theme and mobile inspector render", async () => {
    await page.locator("#theme").click();
    await inspect("bob");
    assert.equal(
      await page.evaluate(() => document.documentElement.dataset.theme),
      "dark",
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
      path: evidence + "/03-mobile-inspector-dark.png",
      fullPage: true,
    });
    await close();
  });
  await check("Cancelled device approval makes no profile change", async () => {
    await inspect("bob");
    const before = legacy.writes.length;
    await page
      .locator("#inspector-body input[name=displayName]")
      .fill("CANCELLED EDIT");
    await cdp.send("WebAuthn.setAutomaticPresenceSimulation", {
      authenticatorId,
      enabled: false,
    });
    await page.getByRole("button", { name: "Save profile & stats" }).click();
    await page.locator("#confirmation-submit").click();
    await visible("#device-verification");
    await page.locator("#verification-cancel").click();
    await page.waitForFunction(() =>
      document.querySelector("#toasts").textContent.includes("cancelled"),
    );
    assert.equal(legacy.writes.length, before);
    assert.equal(legacy.players.get("bob").profile.displayName, "bob");
    await cdp.send("WebAuthn.setAutomaticPresenceSimulation", {
      authenticatorId,
      enabled: true,
    });
    await close();
  });
  await check(
    "Mobile navigation and registered passkeys settings work",
    async () => {
      await page.locator("#menu-button").click();
      await page.locator("#navigation button[data-tab=settings]").click();
      await page
        .getByRole("button", { name: "Add a backup passkey" })
        .waitFor();
      assert.equal(
        await page.locator("#security-content .friend-row").count(),
        1,
      );
      assert.equal(
        await page.locator("#security-content .friend-row button").isDisabled(),
        true,
      );
      assert.equal(
        await page.locator("#menu-button").getAttribute("aria-expanded"),
        "false",
      );
      await page.locator("#menu-button").click();
      await page.locator("#navigation button[data-tab=players]").click();
    },
  );
  await check(
    "Storage audit removes only an explicitly approved duplicate UID",
    async () => {
      legacy.identityConflicts = [
        {
          username: "bob",
          canonicalUid: "uid_bob",
          profiles: [
            { uid: "uid_bob", username: "bob", displayName: "Bob" },
            { uid: "uid_old", username: "bob", displayName: "Old Bob" },
          ],
        },
      ];
      legacy.storageAccountCount = 4;
      legacy.firestoreGet = async () => ({ uid: "uid_bob" });
      legacy.removeDuplicate = async (uid, name) => {
        assert.equal(uid, "uid_old");
        assert.equal(name, "bob");
        legacy.identityConflicts = [];
        legacy.storageAccountCount = 3;
      };
      await refreshDirectory();
      await page
        .getByText(/username.*multiple or unmatched profile identities/)
        .waitFor();
      await page.locator("#menu-button").click();
      await page.locator("#navigation button[data-tab=settings]").click();
      await page.locator("#storage-audit details summary").click();
      await page
        .getByRole("button", { name: "Remove duplicate", exact: true })
        .click();
      await confirm("uid_old");
      await page
        .getByText("No duplicate or unmatched identities in this snapshot.")
        .waitFor();
      assert.equal(legacy.players.has("bob"), true);
      await page.locator("#menu-button").click();
      await page.locator("#navigation button[data-tab=players]").click();
    },
  );
  await check("Silent content publishes only to the selected UID through passkey approval", async () => {
    const nativeFeed = structuredClone(publication.feed);
    const nav = page.locator("#navigation button[data-tab=release]");
    if (await page.locator("#menu-button").getAttribute("aria-expanded") === "false") await page.locator("#menu-button").click();
    await nav.click();
    await page.locator(".content-panel > summary").click();
    await page.locator("[data-content-target]").selectOption("bob");
    await page.waitForFunction(()=>document.querySelector("[data-content-status]")?.textContent.includes("revision 0"));
    await page.locator("[data-content-wording] > summary").click();
    await page.locator('[data-content-field="home.online.title"]').fill("Bob preview");
    await page.locator("[data-content-publish]").click();
    await confirm();
    await page.waitForFunction(()=>document.querySelector("[data-content-status]")?.textContent.includes("revision 1"));
    const audience = await contentPublication.publisher.audience("uid_bob");
    assert.equal(contentPublication.manifest(`content/users/${audience}.json`).strings["home.online.title"],"Bob preview");
    assert.equal(contentPublication.manifest(),null);
    assert.deepEqual(publication.feed,nativeFeed);
    await page.screenshot({path:evidence+"/42-ota-content.png",fullPage:true});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
  });
  await check("A selected user's test content can return to the public channel", async () => {
    await page.locator("[data-content-inherit]").check();
    assert.equal(await page.locator('[data-content-field="home.online.title"]').isDisabled(),true);
    await page.locator("[data-content-publish]").click();await confirm();
    await page.waitForFunction(()=>document.querySelector("[data-content-status]")?.textContent.includes("revision 2"));
    const audience = await contentPublication.publisher.audience("uid_bob");
    assert.equal(contentPublication.manifest(`content/users/${audience}.json`).inheritGlobal,true);
    assert.deepEqual(contentPublication.manifest(`content/users/${audience}.json`).strings,{});
    const playersNav = page.locator("#navigation button[data-tab=players]");
    if (await page.locator("#menu-button").getAttribute("aria-expanded") === "false") await page.locator("#menu-button").click();
    await playersNav.click();
  });
  await check("APK publishing targets only the selected account without changing the public update", async () => {
    const publicBefore = structuredClone(publication.feed);
    if (await page.locator("#menu-button").getAttribute("aria-expanded") === "false") await page.locator("#menu-button").click();
    await page.locator("#navigation button[data-tab=release]").click();
    await page.locator("[data-apk-target]").selectOption("bob");
    await page.getByRole("heading", { name: "No selected-account offer", exact: true }).waitFor();
    await page.locator('input[name=versionName]').fill('1.0.3');
    await page.locator('input[name=versionCode]').fill('76');
    await page.locator('input[name=apk]').setInputFiles({name:'Bingo-fixture.apk',mimeType:'application/vnd.android.package-archive',buffer:Buffer.from(selectedPublication.input.apkBase64,'base64')});
    await page.getByRole('button',{name:'Review & publish update',exact:true}).click();
    await confirm();
    await page.getByRole('heading',{name:'v1.0.3',exact:true}).waitFor();
    const audience = await selectedPublication.publisher.audience('uid_bob');
    assert.equal((await selectedPublication.publisher.state(audience)).release.versionCode,76);
    assert.deepEqual(publication.feed,publicBefore);
    assert.equal(selectedPublication.store.files.get('version.json'),'original-version');
    assert.equal(await page.locator('[data-apk-target]').inputValue(),'bob');
    await page.screenshot({path:evidence+'/44-targeted-apk.png',fullPage:true});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1),false);
  });
  await check("Withdrawing a selected APK offer preserves the public release and returns the account to it", async () => {
    await page.getByRole('button',{name:'Withdraw selected offer',exact:true}).click();await confirm();
    await page.getByRole('heading',{name:'No selected-account offer',exact:true}).waitFor();
    const audience = await selectedPublication.publisher.audience('uid_bob');
    assert.equal((await selectedPublication.publisher.state(audience)).channel.enabled,false);
    assert.equal(publication.feed.versionCode,71);
    if (await page.locator("#menu-button").getAttribute("aria-expanded") === "false") await page.locator("#menu-button").click();
    await page.locator("#navigation button[data-tab=players]").click();
  });
  await check("Manual MQTT cleanup previews, requests device verification and reports verified deletion",async()=>{
    // The duplicate-identity scenario above installs its own Firestore lookup.
    // Restore this scenario's actual archive fixture before checking readback.
    legacy.firestoreGet=async path=>cleanupArchive.get(path)||null;
    if(await page.locator('#menu-button').getAttribute('aria-expanded')==='false')await page.locator('#menu-button').click();
    await page.locator('#navigation button[data-tab=settings]').click();
    const old=Date.now()-172800000;
    cleanupRecords['bingo/emqx7/social/invite/fixture/expired']=JSON.stringify({eventId:'expired',recipientUsername:'fixture',timestamp:old,expiresAt:old+120000});
    assert.equal(await page.locator('#run-mqtt-cleanup').isDisabled(),true);
    await page.locator('#preview-mqtt-cleanup').click();
    await page.waitForFunction(()=>document.querySelector('#mqtt-cleanup-status').textContent.includes('1 eligible records'));
    assert.equal(cleanupWrites.length,0);
    await page.screenshot({path:evidence+'/45-cleanup-preview.png',fullPage:true});
    page.once('dialog',dialog=>dialog.accept());
    await page.locator('#run-mqtt-cleanup').click();
    await page.waitForFunction(()=>document.querySelector('#mqtt-cleanup-status').textContent.includes('1 records removed and verified'));
    assert.equal(cleanupWrites.length,1);assert.equal(cleanupArchive.size,1);
    await page.screenshot({path:evidence+'/46-cleanup-finished.png',fullPage:true});
    if(await page.locator('#menu-button').getAttribute('aria-expanded')==='false')await page.locator('#menu-button').click();
    await page.locator('#navigation button[data-tab=players]').click();
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
      const saved = { token: lastToken };
      await page.locator("#lock").click();
      await visible("#login");
      assert.equal(await page.locator("#player-list").innerText(), "");
      assert.equal(await page.locator("#player-context").innerText(), "");
      assert.equal(
        await page.evaluate(() =>
          sessionStorage.getItem("bingo_admin_session_v4"),
        ),
        null,
      );
      const response = await control.fetch(
        new Request("https://admin.test/api/players", {
          headers: { Authorization: "Bearer " + saved.token },
        }),
      );
      assert.equal(response.status, 401);
    },
  );
  assert.ok(
    approvedMutations >= 8,
    "Every write uses a fresh server-issued approval",
  );
  assert.deepEqual(errors, []);
  results.push({
    case: "No uncaught browser JavaScript errors",
    status: "PASS",
  });
} catch (error) {
  if (error.baselineComplete) {
    process.exitCode = 1;
  } else {
    results.push({ case: "Browser run", status: "FAIL", error: error.stack });
    await page.screenshot({ path: evidence + "/failure.png", fullPage: true });
    throw error;
  }
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
