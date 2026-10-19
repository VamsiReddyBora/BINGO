import {
  generateKeyPairSync,
  sign,
  createHash,
  randomBytes,
} from "node:crypto";
import { encodeCBOR } from "@levischuck/tiny-cbor";
import { digest } from "../model.js";

const b64 = (bytes) => Buffer.from(bytes).toString("base64url");
const hash = (bytes) => createHash("sha256").update(bytes).digest();
/** Protocol fixture: real P-256 keys, COSE registration and DER signatures. No verifier stubs. */
export class TestAuthenticator {
  constructor(origin) {
    this.origin = origin;
    this.rpID = new URL(origin).hostname;
    this.id = randomBytes(32);
    this.key = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
    this.counter = 0;
  }
  registration(options, { uv = true, origin = this.origin } = {}) {
    this.userHandle = options.user.id;
    const jwk = this.key.publicKey.export({ format: "jwk" }),
      cose = encodeCBOR(
        new Map([
          [1, 2],
          [3, -7],
          [-1, 1],
          [-2, Buffer.from(jwk.x, "base64url")],
          [-3, Buffer.from(jwk.y, "base64url")],
        ]),
      );
    const data = Buffer.concat([
      hash(options.rp.id),
      Buffer.from([uv ? 0x45 : 0x41]),
      Buffer.alloc(4),
      Buffer.alloc(16),
      Buffer.from([0, this.id.length]),
      this.id,
      cose,
    ]);
    const client = Buffer.from(
      JSON.stringify({
        type: "webauthn.create",
        challenge: options.challenge,
        origin,
        crossOrigin: false,
      }),
    );
    return {
      id: b64(this.id),
      rawId: b64(this.id),
      type: "public-key",
      authenticatorAttachment: "platform",
      response: {
        clientDataJSON: b64(client),
        attestationObject: b64(
          encodeCBOR(
            new Map([
              ["fmt", "none"],
              ["attStmt", new Map()],
              ["authData", data],
            ]),
          ),
        ),
        transports: ["internal"],
      },
      clientExtensionResults: { credProps: { rk: true } },
    };
  }
  assertion(
    options,
    {
      uv = true,
      origin = this.origin,
      challenge = options.challenge,
      rpID = options.rpId,
      corrupt = false,
    } = {},
  ) {
    const counter = Buffer.alloc(4);
    counter.writeUInt32BE(++this.counter);
    const authenticatorData = Buffer.concat([
      hash(rpID),
      Buffer.from([uv ? 5 : 1]),
      counter,
    ]);
    const client = Buffer.from(
      JSON.stringify({
        type: "webauthn.get",
        challenge,
        origin,
        crossOrigin: false,
      }),
    );
    const signature = sign(
      "sha256",
      Buffer.concat([authenticatorData, hash(client)]),
      this.key.privateKey,
    );
    if (corrupt) signature[signature.length - 1] ^= 255;
    return {
      id: b64(this.id),
      rawId: b64(this.id),
      type: "public-key",
      authenticatorAttachment: "platform",
      response: {
        authenticatorData: b64(authenticatorData),
        clientDataJSON: b64(client),
        signature: b64(signature),
        userHandle: this.userHandle || null,
      },
      clientExtensionResults: {},
    };
  }
}
export async function enroll(control, token, authenticator) {
  const request = (path, input) =>
    control.fetch(
      new Request("https://admin.test/api/auth/" + path, {
        method: "POST",
        headers: { Authorization: "Bearer " + token },
        body: JSON.stringify(input),
      }),
    );
  const first = await request("register/options", { label: "Fixture passkey" });
  const begin = await first.json();
  if (!first.ok) throw Error(begin.error);
  const last = await request("register/verify", {
    challengeId: begin.challengeId,
    response: authenticator.registration(begin.options),
  });
  const result = await last.json();
  if (!last.ok) throw Error(result.error);
  return result;
}
export async function authorize(
  control,
  token,
  authenticator,
  path,
  method,
  input,
  operationId,
) {
  const request = (route, data) =>
    control.fetch(
      new Request("https://admin.test/api/auth/" + route, {
        method: "POST",
        headers: { Authorization: "Bearer " + token },
        body: JSON.stringify(data),
      }),
    );
  const first = await request("action/options", {
    path,
    method,
    bodyHash: await digest(JSON.stringify(input)),
    operationId,
  });
  const begin = await first.json();
  if (!first.ok) throw Error(begin.error);
  const last = await request("action/verify", {
    challengeId: begin.challengeId,
    response: authenticator.assertion(begin.options),
  });
  const result = await last.json();
  if (!last.ok) throw Error(result.error);
  return result.proof;
}
