// Non-delivery preflight: Google validates authorization and a token; no notification is sent.
import { readFile, writeFile } from "node:fs/promises";
import { importPKCS8, SignJWT } from "jose";
const base = new URL("../", import.meta.url);
const username = process.argv[2] || "bob";
if (!/^[a-z0-9_]{1,32}$/.test(username)) throw Error("Invalid test username");
const credential = JSON.parse(
  await readFile(new URL("service-account.json", base), "utf8"),
);
const toml = await readFile(new URL("wrangler.toml", base), "utf8");
const api = /LEGACY_API_URL\s*=\s*"([^"]+)"/.exec(toml)[1],
  appKey = /LEGACY_APP_KEY\s*=\s*"([^"]+)"/.exec(toml)[1];
const report = {
  timestamp: new Date().toISOString(),
  validateOnly: true,
  realNotificationSent: false,
};
try {
  const tokenResponse = await fetch(
    `${api}/GetValue/${appKey}/fcm_${username}`,
    {
      headers: { "User-Agent": "BingoMultiplayer-Notifications-Validation/2" },
      signal: AbortSignal.timeout(10000),
    },
  );
  if (!tokenResponse.ok) throw Error("Registration lookup unavailable");
  let deviceToken = (await tokenResponse.text()).trim();
  try {
    deviceToken = JSON.parse(deviceToken);
  } catch {}
  if (typeof deviceToken !== "string" || !deviceToken || deviceToken === "null")
    throw Error("No saved registration available for non-delivery validation");
  const now = Math.floor(Date.now() / 1000),
    key = await importPKCS8(credential.private_key, "RS256");
  const assertion = await new SignJWT({
    scope: "https://www.googleapis.com/auth/firebase.messaging",
  })
    .setProtectedHeader({ alg: "RS256", typ: "JWT" })
    .setIssuer(credential.client_email)
    .setAudience("https://oauth2.googleapis.com/token")
    .setIssuedAt(now)
    .setExpirationTime(now + 3600)
    .sign(key);
  const auth = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
    signal: AbortSignal.timeout(10000),
  });
  report.oauthHttpStatus = auth.status;
  if (!auth.ok) throw Error("Google OAuth preflight failed");
  const google = await auth.json();
  const validation = await fetch(
    `https://fcm.googleapis.com/v1/projects/${credential.project_id}/messages:send`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${google.access_token}`,
      },
      body: JSON.stringify({
        validate_only: true,
        message: {
          token: deviceToken,
          data: { version: "2", type: "VALIDATION_ONLY" },
          android: { priority: "NORMAL", ttl: "1s" },
        },
      }),
      signal: AbortSignal.timeout(10000),
    },
  );
  report.fcmValidationHttpStatus = validation.status;
  const data = await validation.json();
  report.result = validation.ok
    ? "PASS"
    : data.error?.status || "Validation failed";
  report.registrationError =
    (data.error?.details || []).find((x) => x.errorCode)?.errorCode || null;
} catch (error) {
  report.result = "NOT VERIFIED";
  report.reason = error.message;
}
const destination =
  process.env.BINGO_VALIDATION_REPORT || "/tmp/bingo-fcm-live-validation.json";
await writeFile(destination, JSON.stringify(report, null, 2) + "\n");
console.log(JSON.stringify(report, null, 2));
