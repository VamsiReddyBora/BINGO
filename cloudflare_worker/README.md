# Bingo notification relay — protocol 2

This Worker stores a private notification inbox and sends FCM wake hints. Number picks, turn timers, game heartbeats and match authority continue to use the existing game implementation. This service is not a match referee.

## Identity and delivery

- Google identity uses signed ID tokens and the confirmed **Web application** audience configured in both `wrangler.toml` and Android `NetworkConfig.kt`.
- New guests prove possession of a P-256 Android Keystore key. Older guest UIDs have no cryptographic ownership proof; the app explains the sign-in requirement without deleting their data.
- Opaque sessions expire after seven days and bind account, installation and generation. Logout queues durable revocation; local checks also reject stale-account events before remote cleanup finishes.
- Tokens live in private Durable Object storage. Clients cannot look up another account's token or choose the sender UID. Unauthenticated `/send` returns 401.
- Original expiry: invitation 120 seconds, online 15 seconds, friend request/acceptance one day. Retries never extend it.
- Individual event records, stable IDs, acknowledgement tombstones, bounded queues, rate limits and a durable alarm outbox prevent lost updates and repeat delivery. Invalid-token responses cannot erase newer tokens.
- FCM data messages, MQTT and polling trigger authenticated inbox validation before local settings, freshness and deduplication are applied.

Existing public profile, room and friendship storage is a separate boundary. Legacy ownership/room/request checks read those records. This change does not secure all historical application data.

## Build and validation

Use Node 20+ and Wrangler 4. From this directory:

```bash
npm ci
npm test
npx wrangler deploy --dry-run --outdir /tmp/bingo-notification-worker-bundle
```

Tests mock outbound services and deliver no notifications. `scripts/validate-fcm.mjs` additionally checks the ignored local service account and saved registration using FCM `validate_only: true`; it prints no credentials and sends no real notification.

## Production rollout

Complete Android validation and review the release report first. This configuration creates **`bingo-notifications-v2`**, a separate service at `https://bingo-notifications-v2.vamsireddy2534.workers.dev`. Do not change its name to `bingo-fcm-relay`: the existing live Worker has an older `MATCHES` / `BingoMatch` binding and migration not represented by this checkout.

Both Android players need **1.4.2 / 43** for the new notification protocol. Previous APKs and the unchanged companion web sender continue using the old service; they cannot send through the secured service. Room-code gameplay remains separate. The old relay needs a separate security/retirement review after identifying its remaining consumers; it is not secured by deploying this new Worker, and its live notification-handler source was not verified in this task.

The ignored `service-account.json` must never be committed or embedded in the bundle. Upload it as a Worker secret, then deploy the complete module tree and SQLite migration:

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

Preserve the baseline branch, original APK, deployed Worker version and notification storage. Review backend rollback with Android compatibility; do not delete the Durable Object migration or automatically restore the vulnerable sender. Downgrading build 43 to 42 may require Android downgrade tooling; do not uninstall and lose data as routine recovery.

OS permission, channels, connectivity, scheduling, battery restrictions and force-stop can delay or prevent delivery. Reopening recovers the inbox subject to original expiry. Immediate push delivery is not guaranteed.
