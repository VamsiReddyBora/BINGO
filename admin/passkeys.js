const decode = (value) =>
  Uint8Array.from(atob(value.replaceAll("-", "+").replaceAll("_", "/")), (c) =>
    c.charCodeAt(0),
  );
const encode = (value) =>
  btoa(String.fromCharCode(...new Uint8Array(value)))
    .replaceAll("+", "-")
    .replaceAll("/", "_")
    .replaceAll("=", "");
let pending = null;
export function supported() {
  return !!(
    window.isSecureContext &&
    window.PublicKeyCredential &&
    navigator.credentials
  );
}
export function cancelVerification() {
  pending?.abort();
}
export function serialize(credential) {
  const response = credential.response;
  return {
    id: credential.id,
    rawId: encode(credential.rawId),
    type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment,
    clientExtensionResults: credential.getClientExtensionResults(),
    response: response.attestationObject
      ? {
          clientDataJSON: encode(response.clientDataJSON),
          attestationObject: encode(response.attestationObject),
          transports: response.getTransports?.() || [],
        }
      : {
          clientDataJSON: encode(response.clientDataJSON),
          authenticatorData: encode(response.authenticatorData),
          signature: encode(response.signature),
          userHandle: response.userHandle ? encode(response.userHandle) : null,
        },
  };
}
async function ceremony(kind, options, label) {
  if (!supported())
    throw new Error(
      "Passkeys are unavailable here. Open this page in a supported browser on a device with a screen lock.",
    );
  if (pending) throw new Error("Finish the current device verification first.");
  const controller = new AbortController();
  pending = controller;
  const dialog = document.getElementById("device-verification");
  document.getElementById("verification-action").textContent = label;
  const publicKey = { ...options, challenge: decode(options.challenge) };
  if (options.user)
    publicKey.user = { ...options.user, id: decode(options.user.id) };
  for (const field of ["allowCredentials", "excludeCredentials"])
    if (options[field])
      publicKey[field] = options[field].map((c) => ({
        ...c,
        id: decode(c.id),
      }));
  try {
    dialog.showModal();
    const credential = await navigator.credentials[kind]({
      publicKey,
      signal: controller.signal,
    });
    if (!credential) throw new Error("Device verification was not completed.");
    return serialize(credential);
  } catch (error) {
    if (["NotAllowedError", "AbortError"].includes(error.name))
      throw new Error(
        "Verification cancelled or timed out. No change was made.",
      );
    if (error.name === "InvalidStateError")
      throw new Error(
        "A passkey for Bingo already exists on this device. Use it to sign in, or choose another device.",
      );
    throw error;
  } finally {
    pending = null;
    dialog.close();
  }
}
export async function signIn(raw) {
  const begin = await raw("auth/login/options", {
    method: "POST",
    auth: false,
    data: {},
    skipVerification: true,
  });
  const response = await ceremony(
    "get",
    begin.options,
    "Sign in to Bingo Control",
  );
  return raw("auth/login/verify", {
    method: "POST",
    auth: false,
    data: { challengeId: begin.challengeId, response },
    skipVerification: true,
  });
}
export async function register(raw, label = "My passkey") {
  const begin = await raw("auth/register/options", {
    method: "POST",
    data: { label },
  });
  const response = await ceremony(
    "create",
    begin.options,
    "Create your Bingo passkey",
  );
  return raw("auth/register/verify", {
    method: "POST",
    data: { challengeId: begin.challengeId, response },
    skipVerification: true,
  });
}
export async function approve(raw, path, method, data, operationId) {
  const bytes = new TextEncoder().encode(JSON.stringify(data));
  const bodyHash = [
    ...new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
  ]
    .map((n) => n.toString(16).padStart(2, "0"))
    .join("");
  const begin = await raw("auth/action/options", {
    method: "POST",
    data: { path: "/api/" + path, method, bodyHash, operationId },
    skipVerification: true,
  });
  const action =
    method === "DELETE"
      ? path.includes("/matches")
        ? "Delete match records"
        : path.includes("/friends")
          ? "Remove friendship"
          : path.includes("/passkeys")
            ? "Remove passkey"
            : "Delete player account"
      : "Approve this change";
  const response = await ceremony("get", begin.options, action);
  const verified = await raw("auth/action/verify", {
    method: "POST",
    data: { challengeId: begin.challengeId, response },
    skipVerification: true,
  });
  return verified.proof;
}
