# Bingo notification relay — protocol 2

This Worker stores a private notification inbox and sends FCM wake hints. Number picks, turn timers, game heartbeats and match authority continue to use the existing game implementation. This service is not a match referee.

## Identity and delivery

- Google identity uses signed ID tokens and the confirmed **Web application** audience configured in both `wrangler.toml` and Android `NetworkConfig.kt`.
- New guests prove possession of a P-256 Android Keystore key. Older guest UIDs have no cryptographic ownership proof; the app explains the sign-in requirement without deleting their data.
- Opaque sessions expire after seven days and bind account, installation and generation. Logout queues durable revocation; local checks also reject stale-account events before remote cleanup finishes.
- Tokens live in private Durable Object storage. Clients cannot look up another account's token or choose the sender UID. Unauthenticated `/send` returns 401.
- Original expiry: invitation 120 seconds, online 120 seconds on Build 66+, friend request/acceptance one day. Older APK registrations retain 15-second online payloads. Retries never extend expiry.
- Individual event records, stable IDs, acknowledgement tombstones, bounded queues, rate limits and a durable alarm outbox prevent lost updates and repeat delivery. Invalid-token responses cannot erase newer tokens.
- FCM data messages and polling trigger authenticated inbox validation before local settings, freshness and deduplication are applied. Live MQTT invitations use the same event ID, original expiry and room session, so receiving both transports does not duplicate the invitation.

Public profile ownership is read from the existing Firebase `users/{uid}` document when `FIREBASE_PROJECT_ID` is configured. The signed session UID and normalized username must match; notification tokens remain private. Existing legacy friendship/request checks are unchanged.

## Offline invitations (Android build 60)

Invite remains available when a friend is offline or has no fresh presence. The sender publishes the invitation through MQTT and queues a high-priority FCM wake-up separately after the broker acknowledgement. A notification relay outage cannot delay the live MQTT invitation. Requests to somebody already playing remain transient MQTT messages and do not create a lobby or offline reservation.

The relay does **not** require an HTTP copy of the room or require the sender to be its host. Authenticated lobby members can send a notification carrying the original room code and session. Accepting the invitation checks and joins that room through MQTT; closed, full, expired or replaced rooms remain rejected by normal MQTT admission. No gameplay, countdown or room authority was moved to this Worker.

Tapping the notification body opens the app and presents the invitation. Its Accept action uses the same MQTT join path. MQTT and FCM share handling/expiry records; accepting or declining acknowledges the relay copy. A failed join preserves the invitation for another attempt until its original expiry.

## Build and validation

Use Node 20+ and Wrangler 4. From this directory:

```bash
npm ci
npm test
npx wrangler deploy --dry-run --outdir /tmp/bingo-notification-worker-bundle
```

Tests mock outbound services and deliver no notifications. `scripts/validate-fcm.mjs` additionally checks the ignored local service account and saved registration using FCM `validate_only: true`; it prints no credentials and sends no real notification.

## Production rollout

Complete Android validation and review the release report first. This configuration updates **`bingo-notifications-v2`** at `https://bingo-notifications-v2.vamsireddy2534.workers.dev`. Preserve its name, existing `NOTIFICATIONS` storage, and migrations. Do not rename it to the older `bingo-fcm-relay` service.

Use **build 60 or newer on both Android phones** for restored offline invitation delivery and room-session preservation. The notification protocol remains version 2. Older clients may suppress invitation push even though their live MQTT invitation still works. The companion web client was not changed or certified by this Android task.

The ignored `service-account.json` must never be committed or embedded in the bundle. Retain the existing secret when updating. For a new installation only, upload it before deploying the complete module tree and SQLite migration:

```bash
npx wrangler secret put SERVICE_ACCOUNT_JSON < service-account.json
npx wrangler deploy
```

Do not paste `worker.js` alone into the dashboard: it imports other modules and requires the `NOTIFICATIONS` binding and `notifications-v2` migration. Use the same Web OAuth audience in Worker and Android; an Android OAuth client ID is not the audience.

After deployment check protocol 2 at `GET /health`, HTTP 401 for unauthenticated `GET /v2/inbox` and `POST /send`. Then test Google sign-in and delivery on two upgraded phones with the users' consent. Health/FCM validation does not prove device receipt.

## Routes

| Route | Purpose |
| --- | --- |
| `GET /health` | Non-secret service information and protocol version |
| `POST /v2/session` | Verified Google/guest enrollment; returns opaque session and revocation capability |
| `POST /v2/register` | Authenticated token/revision update |
| `POST /v2/friends` | Recipient consent snapshot with monotonic revision |
| `POST /v2/events` | Validated, rate-limited, idempotent event submission |
| `GET /v2/inbox` | Authenticated recipient's unexpired inbox |
| `POST /v2/ack` | Durable handling of one event |
| `POST /v2/revoke` | Revoke only the registration named by its secret capability |
| `POST /send` | Rejected obsolete unauthenticated protocol |

Private `/internal/*` routes cannot be invoked through the public Worker.

## Recovery and limits

Preserve the source/APK baseline, deployed Worker version and notification storage. Review backend rollback with Android compatibility; do not delete the Durable Object migration or automatically restore the vulnerable sender. Downgrading an APK may require Android downgrade tooling; do not uninstall and lose data as routine recovery.

OS permission, channels, connectivity, scheduling, battery restrictions and force-stop can delay or prevent delivery. Reopening recovers the inbox subject to original expiry. Immediate push delivery is not guaranteed.
