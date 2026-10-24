import fs from "node:fs/promises";
import { SignJWT, importPKCS8 } from "jose";
const service = JSON.parse(
  await fs.readFile(
    process.env.SERVICE_ACCOUNT_FILE ||
      new URL("../../cloudflare_worker/service-account.json", import.meta.url),
    "utf8",
  ),
);
const key = await importPKCS8(service.private_key, "RS256"),
  now = Math.floor(Date.now() / 1000);
const assertion = await new SignJWT({
  scope:
    "https://www.googleapis.com/auth/cloud-platform https://www.googleapis.com/auth/firebase",
})
  .setProtectedHeader({ alg: "RS256" })
  .setIssuer(service.client_email)
  .setAudience("https://oauth2.googleapis.com/token")
  .setIssuedAt(now)
  .setExpirationTime(now + 3600)
  .sign(key);
const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
  method: "POST",
  body: new URLSearchParams({
    grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
    assertion,
  }),
});
const token = await tokenResponse.json();
if (!tokenResponse.ok) throw Error("Service-account authentication failed");
const base =
  "https://firebaserules.googleapis.com/v1/projects/" + service.project_id;
async function call(path, method = "GET", data) {
  const r = await fetch(base + path, {
    method,
    headers: {
      Authorization: "Bearer " + token.access_token,
      "Content-Type": "application/json",
    },
    body: data ? JSON.stringify(data) : undefined,
    signal: AbortSignal.timeout(15000),
  });
  const json = await r.json();
  if (!r.ok) throw Error("Rules API " + r.status + ": " + json.error?.message);
  return json;
}
const original = await call("/releases/cloud.firestore");
const folder =
  process.env.ADMIN_RULES_EVIDENCE ||
  new URL("../local-rule-backups/", import.meta.url).pathname;
await fs
  .writeFile(
    folder + "firestore-release-before.json",
    JSON.stringify(original, null, 2),
    { mode: 0o600, flag: "wx" },
  )
  .catch((error) => {
    if (error.code !== "EEXIST") throw error;
  });
const current = await call(
  "/rulesets/" + original.rulesetName.split("/").pop(),
);
await fs
  .writeFile(
    folder + "firestore-rules-before.json",
    JSON.stringify(current, null, 2),
    { mode: 0o600, flag: "wx" },
  )
  .catch((error) => {
    if (error.code !== "EEXIST") throw error;
  });
console.log("Current Firestore rules backed up.");
if (!process.argv.includes("--deploy")) process.exit(0);
const source = await fs.readFile(
  process.env.FIRESTORE_RULES_FILE ||
    new URL("../../firestore.rules", import.meta.url),
  "utf8",
);
const compiled = await call("/rulesets", "POST", {
  source: { files: [{ name: "firestore.rules", content: source }] },
});
console.log("Rules compiler accepted the deletion guards.");
const release = await call("/releases/cloud.firestore", "PATCH", {
  release: { name: original.name, rulesetName: compiled.name },
  updateMask: "ruleset_name",
});
console.log(
  JSON.stringify({
    deployed: true,
    name: release.name,
    rulesetName: release.rulesetName,
    at: new Date().toISOString(),
  }),
);
