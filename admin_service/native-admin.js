import { createRemoteJWKSet, jwtVerify, decodeJwt } from "jose";
import { Problem, requireThat, digest } from "./model.js";

const firebaseKeys = createRemoteJWKSet(new URL(
  "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
), { timeoutDuration: 8000, cooldownDuration: 30000 });

const googleKeys = createRemoteJWKSet(new URL("https://www.googleapis.com/oauth2/v3/certs"),
  { timeoutDuration: 8000, cooldownDuration: 30000 });
// Public OAuth audience used by the existing Android Google sign-in flow.
const GOOGLE_CLIENT_ID = "957593338442-ol5p4icr1a8e15m8lb1ln5mab5n1r5lp.apps.googleusercontent.com";

// Same registered account policy as Android; usernames/headers never grant access.
export const NATIVE_ADMIN_AUDIENCES = new Set([
  "2778fa42104c3f64703c5fe9abcbd76b356a4167176fac0a22ee08f22f11b3f9",
  "492e92d03b7d4c82194ec0a5c453b9a052ec6e36cbc8ea45f8d2c408fd135b6f",
]);

export async function verifyNativeAdmin(request, env, options = {}) {
  const header = request.headers.get("Authorization") || "";
  requireThat(/^Bearer [^\s]{1,8192}$/.test(header), 401, "Sign in again to verify your admin account");
  let payload, googleId;
  const token = header.slice(7);
  let issuer;
  try { issuer = decodeJwt(token).iss; }
  catch { throw new Problem(401, "Admin sign-in could not be verified; reconnect your account"); }
  if (["https://accounts.google.com", "accounts.google.com"].includes(issuer)) {
    try {
      ({ payload } = await jwtVerify(token, options.googleKeys || googleKeys, {
        audience: GOOGLE_CLIENT_ID, issuer: ["https://accounts.google.com", "accounts.google.com"],
        algorithms: ["RS256"], requiredClaims: ["sub", "exp", "iat"],
      }));
    } catch { throw new Problem(401, "Admin Google sign-in could not be verified; reconnect your account"); }
    googleId = payload.sub;
  } else {
    const account = typeof env.SERVICE_ACCOUNT_JSON === "string"
      ? JSON.parse(env.SERVICE_ACCOUNT_JSON) : env.SERVICE_ACCOUNT_JSON;
    requireThat(typeof account?.project_id === "string", 503, "Admin identity verification unavailable");
    try {
      ({ payload } = await jwtVerify(token, options.keys || firebaseKeys, {
        audience: account.project_id, issuer: "https://securetoken.google.com/" + account.project_id,
        algorithms: ["RS256"], requiredClaims: ["sub", "exp", "iat", "auth_time"],
      }));
    } catch { throw new Problem(401, "Admin sign-in could not be verified; reconnect your account"); }
    const identities = payload.firebase?.identities?.["google.com"];
    requireThat(payload.firebase?.sign_in_provider === "google.com" && Array.isArray(identities) &&
      identities.length === 1, 403, "Registered Google administrator required");
    requireThat(typeof payload.auth_time === "number" && payload.auth_time <= Math.floor(Date.now() / 1000),
      401, "Invalid sign-in claims");
    googleId = identities[0];
  }
  requireThat(typeof payload.sub === "string" && payload.sub.length <= 128 &&
    typeof payload.iat === "number" && payload.iat <= Math.floor(Date.now() / 1000), 401, "Invalid sign-in claims");
  requireThat(typeof googleId === "string" && /^\d{5,40}$/.test(googleId), 403, "Registered Google administrator required");
  const audience = await digest("google_" + googleId);
  requireThat((options.audiences || NATIVE_ADMIN_AUDIENCES).has(audience), 403, "Administrator access required");
  return audience;
}
