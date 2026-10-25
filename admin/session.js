// Keep only a bounded bearer session in this tab. The server must validate it
// before revealing the console; every mutation still needs a fresh passkey proof.
export const SESSION_KEY = "bingo_admin_session_v4";
export function tabStorage() {
  try {
    return globalThis.sessionStorage;
  } catch {
    return null;
  }
}
export function forgetSession(storage = tabStorage()) {
  try {
    storage?.removeItem(SESSION_KEY);
    storage?.removeItem("bingo_admin_session_v3");
  } catch {
    /* Storage may be disabled. In-memory login still works. */
  }
}
export function readSession(storage = tabStorage(), now = Date.now()) {
  try {
    const value = JSON.parse(storage?.getItem(SESSION_KEY) || "null");
    if (
      value &&
      /^[a-f0-9]{64}$/.test(value.token) &&
      Number.isFinite(value.expiresAt) &&
      value.expiresAt > now &&
      value.setupRequired === false &&
      value.authMethod === "passkey"
    )
      return value;
  } catch {
    /* Invalid or inaccessible data must not authorize anything. */
  }
  forgetSession(storage);
  return null;
}
export function rememberSession(value, storage = tabStorage()) {
  if (value?.setupRequired || value?.authMethod !== "passkey") {
    forgetSession(storage);
    return false;
  }
  try {
    storage?.setItem(
      SESSION_KEY,
      JSON.stringify({
        token: value.token,
        expiresAt: value.expiresAt,
        operator: value.operator,
        setupRequired: false,
        authMethod: "passkey",
      }),
    );
    return !!storage;
  } catch {
    return false;
  }
}
