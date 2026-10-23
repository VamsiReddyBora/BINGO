import { createRemoteJWKSet, jwtVerify } from "jose";
import {
  HttpError,
  requireThat,
  username,
  validName,
  validId,
} from "./protocol.js";
const googleKeys = createRemoteJWKSet(
  new URL("https://www.googleapis.com/oauth2/v3/certs"),
  { timeoutDuration: 8000, cooldownDuration: 30000 },
);
function fromB64(value) {
  return Uint8Array.from(
    atob(value.replace(/-/g, "+").replace(/_/g, "/")),
    (c) => c.charCodeAt(0),
  );
}
// Android Signature produces ASN.1 DER; WebCrypto ECDSA verification uses fixed-width r||s.
export function derToRaw(bytes) {
  requireThat(
    bytes[0] === 0x30 && bytes[1] === bytes.length - 2 && bytes[2] === 2,
    400,
    "Invalid signature",
  );
  const rLen = bytes[3],
    sPos = 4 + rLen;
  requireThat(bytes[sPos] === 2, 400, "Invalid signature");
  const sLen = bytes[sPos + 1];
  requireThat(sPos + 2 + sLen === bytes.length, 400, "Invalid signature");
  const values = [bytes.slice(4, sPos), bytes.slice(sPos + 2)];
  const raw = new Uint8Array(64);
  values.forEach((v, i) => {
    if (v.length === 33 && v[0] === 0) v = v.slice(1);
    requireThat(v.length > 0 && v.length <= 32, 400, "Invalid signature");
    raw.set(v, i * 32 + 32 - v.length);
  });
  return raw;
}
export async function verifyIdentity(body, env, options = {}) {
  const name = username(body.username);
  requireThat(
    validName(name) && typeof body.uid === "string" && body.uid.length <= 100,
    400,
    "Invalid account",
  );
  requireThat(
    validId(body.installationId) &&
      validId(body.generation) &&
      Number.isSafeInteger(body.sequence) &&
      body.sequence > 0,
    400,
    "Invalid installation",
  );
  requireThat(
    typeof body.displayName === "string" &&
      body.displayName.trim().length > 0 &&
      body.displayName.length <= 80,
    400,
    "Invalid display name",
  );
  let uid;
  if (body.provider === "google") {
    requireThat(
      typeof env.GOOGLE_CLIENT_ID === "string" &&
        env.GOOGLE_CLIENT_ID.endsWith(".apps.googleusercontent.com"),
      503,
      "Google notification authentication is not configured",
    );
    requireThat(
      typeof body.idToken === "string" && body.idToken.length <= 4096,
      401,
      "Google sign-in required",
    );
    try {
      const { payload } = await jwtVerify(
        body.idToken,
        options.googleKeys || googleKeys,
        {
          audience: env.GOOGLE_CLIENT_ID,
          issuer: ["https://accounts.google.com", "accounts.google.com"],
          algorithms: ["RS256"],
          clockTolerance: 5,
        },
      );
      requireThat(
        typeof payload.sub === "string" && /^\d{5,40}$/.test(payload.sub),
        401,
        "Invalid Google identity",
      );
      uid = "google_" + payload.sub;
    } catch (e) {
      if (e instanceof HttpError) throw e;
      throw new HttpError(401, "Google sign-in could not be verified");
    }
  } else if (body.provider === "guest") {
    requireThat(
      typeof body.publicKey === "string" &&
        body.publicKey.length <= 256 &&
        typeof body.signature === "string" &&
        body.signature.length <= 160,
      401,
      "Guest identity required",
    );
    requireThat(
      Number.isSafeInteger(body.timestamp) &&
        Math.abs(Date.now() - body.timestamp) <= 60000 &&
        validId(body.nonce),
      401,
      "Expired guest proof",
    );
    const bytes = fromB64(body.publicKey);
    const rawDigest = "guest_" + (await digestBytes(bytes));
    const stringDigest = "guest_" + (await digestBytes(new TextEncoder().encode(body.publicKey)));
    if (body.uid === stringDigest) {
      uid = stringDigest;
    } else {
      uid = rawDigest;
    }
    const message = `bingo-notifications-v2\n${uid}\n${name}\n${body.installationId}\n${body.generation}\n${body.sequence}\n${body.timestamp}\n${body.nonce}`;
    try {
      const key = await crypto.subtle.importKey(
        "spki",
        bytes,
        { name: "ECDSA", namedCurve: "P-256" },
        false,
        ["verify"],
      );
      requireThat(
        await crypto.subtle.verify(
          { name: "ECDSA", hash: "SHA-256" },
          key,
          derToRaw(fromB64(body.signature)),
          new TextEncoder().encode(message),
        ),
        401,
        "Invalid guest proof",
      );
    } catch (e) {
      if (e instanceof HttpError) throw e;
      throw new HttpError(401, "Invalid guest proof");
    }
  } else throw new HttpError(401, "Unsupported identity provider");
  requireThat(body.uid === uid, 403, "Account identity mismatch");
  return {
    uid,
    username: name,
    displayName:
      body.displayName.replace(/[\u0000-\u001f\u007f]/g, " ").trim() || name,
    installationId: body.installationId,
    generation: body.generation,
    sequence: body.sequence,
    token:
      typeof body.token === "string" && body.token.length <= 4096
        ? body.token
        : "",
    tokenRevision: Number.isSafeInteger(body.tokenRevision)
      ? body.tokenRevision
      : 0,
    nonce: body.provider === "guest" ? body.nonce : "",
  };
}

export async function verifyGuestBinding(body, env) {
  requireThat(
    typeof body.guestUid === "string" &&
      typeof body.firebaseUid === "string" &&
      typeof body.publicKey === "string" &&
      typeof body.signature === "string" &&
      Number.isSafeInteger(body.timestamp) &&
      Math.abs(Date.now() - body.timestamp) <= 60000 &&
      typeof body.nonce === "string" &&
      body.nonce.length >= 8,
    401,
    "Invalid guest binding proof parameters",
  );
  const bytes = fromB64(body.publicKey);
  const rawDigest = "guest_" + (await digestBytes(bytes));
  const stringDigest =
    "guest_" + (await digestBytes(new TextEncoder().encode(body.publicKey)));
  requireThat(
    body.guestUid === rawDigest || body.guestUid === stringDigest,
    403,
    "Guest UID does not match public key",
  );
  const message = `bingo-firebase-guest-binding\n${body.guestUid}\n${body.firebaseUid}\n${body.publicKey}\n${body.timestamp}\n${body.nonce}`;
  try {
    const key = await crypto.subtle.importKey(
      "spki",
      bytes,
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
    const valid = await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      key,
      derToRaw(fromB64(body.signature)),
      new TextEncoder().encode(message),
    );
    requireThat(valid, 401, "Invalid cryptographic binding proof");
  } catch (e) {
    if (e instanceof HttpError) throw e;
    throw new HttpError(401, "Invalid cryptographic binding proof");
  }
  return {
    guestUid: body.guestUid,
    firebaseUid: body.firebaseUid,
    publicKey: body.publicKey,
    nonce: body.nonce,
  };
}

async function digestBytes(bytes) {
  return [...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))]
    .map((n) => n.toString(16).padStart(2, "0"))
    .join("");
}
