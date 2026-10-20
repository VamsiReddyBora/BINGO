import {
  HttpError,
  readBody,
  response,
  requireThat,
  digest,
} from "./protocol.js";
import { verifyIdentity } from "./auth.js";
export { NotificationInbox } from "./inbox.js";

export default {
  async fetch(request, env) {
    try {
      const url = new URL(request.url);
      if (
        request.method === "GET" &&
        (url.pathname === "/" || url.pathname === "/health")
      )
        return response({
          status: "ok",
          service: "Bingo notifications",
          protocol: 2,
          roomMetadataProtocol: 3,
          identityConfigured: !!env.GOOGLE_CLIENT_ID,
        });
      if (url.pathname === "/send")
        throw new HttpError(
          401,
          "Authenticated notification protocol v2 is required",
        );
      requireThat(
        url.pathname.startsWith("/v2/") &&
          ["GET", "POST"].includes(request.method),
        404,
        "Not found",
      );
      requireThat(
        env.NOTIFICATIONS,
        503,
        "Notification storage is not configured",
      );
      const store = env.NOTIFICATIONS.get(
        env.NOTIFICATIONS.idFromName("notification-directory-v2"),
      );
      const roomMatch = url.pathname.match(/^\/v2\/rooms\/([A-Z0-9]{6})$/);
      if (roomMatch) {
        const roomStore = env.NOTIFICATIONS.get(
          env.NOTIFICATIONS.idFromName("room-metadata-v3:" + roomMatch[1]),
        );
        let body = { action: "read", code: roomMatch[1] };
        if (request.method === "POST") {
          const authorized = await store.fetch(
            new Request("https://internal/internal/room-auth", {
              headers: {
                Authorization: request.headers.get("Authorization") || "",
              },
            }),
          );
          if (!authorized.ok) return authorized;
          const identity = await authorized.json();
          body = {
            ...(await readBody(request, 131072)),
            action: "commit",
            code: roomMatch[1],
            actorUid: identity.uid,
          };
        }
        return roomStore.fetch(
          new Request("https://internal/internal/room-record", {
            method: "POST",
            body: JSON.stringify(body),
          }),
        );
      }
      if (url.pathname === "/v2/session" && request.method === "POST") {
        const ip = await digest(
          request.headers.get("CF-Connecting-IP") || "local",
        );
        const limited = await store.fetch(
          new Request("https://internal/internal/auth-rate", {
            method: "POST",
            body: JSON.stringify({ key: ip }),
          }),
        );
        if (!limited.ok) return limited;
        const identity = await verifyIdentity(await readBody(request), env);
        return store.fetch(
          new Request("https://internal/internal/enroll", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(identity),
          }),
        );
      }
      return store.fetch(request);
    } catch (e) {
      return response(
        {
          error:
            e instanceof HttpError
              ? e.message
              : "Notification service temporarily unavailable",
        },
        e.status || 503,
      );
    }
  },
};
