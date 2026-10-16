# Notification hardening report — 2026-10-16

Timezone: Asia/Kolkata. Canonical source: `/home/bob/workspace/bingo_multiplayer`, branch `fix/notifications-1.4.2`.
Baseline: `422ee84c911a1c2226a6eb3e4a17d2d5c0a740ce`, retained as `backup/pre-notification-hardening-20261016`.

This report accompanies Android **1.4.2 / 43** and notification relay **protocol 2**. See `releases/ReleaseManifest.json` and `TEST_REPORT.md` for final checksum, validation timestamp and deployment state. The original audit is preserved in `NotificationSystemReview-2026-10-16.md`; its line numbers refer to the original source.

## Repairs

| Audit | Implementation | Verification / practical limit |
| --- | --- | --- |
| N01: unauthenticated sender and public tokens | `cloudflare_worker/{worker,auth,inbox,protocol}.js`: verified Google/guest identity, private registration/inbox, derived sender UID, strict schema, recipient/room/request checks and throttling; public `/send` rejected. `NotificationIdentity.kt`, `NotificationApi.kt`, `BingoFcmManager.kt` replace public token lookup. | Relay tests cover real signatures with test keys, wrong audience/subject, unauthenticated requests, room ownership, schema and rate limits. Legacy public-data boundary remains; see below. |
| N02: stale-account delivery | `NotificationAccountStore.kt`, `NotificationWork.kt`, `NotificationRuntime.kt`: installation ID, persisted generations/sequences, scoped credentials, durable revocation and stale-work rejection. Intents carry account/generation; final posting serializes with account changes. Pending popups/actions and active toast are cleared safely. | Account-generation, delayed-session and token-race tests; SQLite smoke verifies old-session revocation. Real Google account-switch/lockscreen testing remains. |
| N03: ignored social pushes | `BingoFirebaseMessagingService.kt` and Runtime support all four typed kinds; canonical request/relationship validation precedes presentation. | Typed policy and request/acceptance relay tests. Real FCM rendering remains. |
| N04: old Accept replaces live game | `RootNavGraph.kt`: shared join guard checks active/recoverable matches, freshness, handled state and account before and after network validation; action extras consumed. | Join-policy tests and existing gameplay regressions. Tray actions during AI/nearby/online/paused/host-wait need phones. |
| N05: preference bypass | All hints enter Runtime. In-app/category/foreground eligibility is rechecked before display; OS permission/channel gates system posting. Settings refreshes permission on resume. | Source inspection and policy/ledger tests. Runtime permission/channel UI needs phones. |
| N06: stale alerts | One original expiry in server inbox/outbox, FCM TTL, local policy, popup expiry and tap handling. Online events reject newer offline presence. | Boundary/expiry tests and FCM payload assertions. Retry never renews an invitation. |
| N07: duplicate/resurrected alerts | `NotificationLedger.kt`: durable account/event presentation and handling, shared lock, bounded entries, fail-closed corrupt storage, social cross-channel suppression and online cooldown. Server idempotency/ACK tombstones. | Concurrent claims, recreation, decline replay, cooldown, corrupt storage and SQLite ACK tests. |
| N08: detached Decline cleanup | `NotificationActionReceiver.kt`: synchronous local handling commit, exact-tag cancellation and durable ACK work. | Ledger replay and repeated ACK tests. Process kill immediately after Decline needs phones. |
| N09: obsolete online/friend alerts | Authenticated recipient consent, monotonic snapshots, online-session freshness, newer-offline checks, removed-friend markers and canonical pending-request validation. | Consent/removal/revision/freshness tests. Legacy friendship reconciliation preserved. |
| N10: no-op online tap | Typed online action opens existing friend profile; request/acceptance opens Requests; old/invalid actions rejected. | Routing inspected; device navigation smoke remains. |
| N11: misleading success/failure handling | Sent/Queued/Failed feedback, HTTP-aware auth/expiry/transient handling, bounded WorkManager retries and durable alarm outbox. Actionable registration status in Settings. Private payload/token logs removed. | Transient/invalid-token/retry tests and FCM validation. Queued means retry scheduled, not delivery confirmed. |
| N12: lost concurrent invitations | Independent event records, serialized Durable Object operations, authenticated reads, per-event ACK and tombstones; no failed-read/full-list overwrite. | Concurrent invite, repeated ACK, recipient isolation, rename and actual SQLite tests. |
| C01: update/broadcast OS alerts absent | `UpdateCheckWorker.kt`, `AppUpdateManager.kt`, `BroadcastMessageManager.kt`, notification manager: background OS alerts, scoped seen state, canonical broadcast validation and tap intents. | Source/build validation. Existing polling/MQTT triggers retained; no independent FCM broadcast fanout added. |
| C02: clipped custom layout | Native BigTextStyle and Accept/Decline replace RemoteViews; four obsolete resources removed. Existing user channel choices retained. | Resource/build checks; compact/expanded phone rendering remains. |

## Validation

- Android: full unit tests, debug lint and signed release build. Final results are in `TEST_REPORT.md`.
- Relay: **54 tests passed**, no failures. Real signature verification with test keys; outbound Google/FCM mocked.
- workerd/Miniflare: **five smoke checks passed** with an actual SQLite-backed Durable Object: signed guest enrollment, private inbox, ACK/replay, generation transfer and rejected legacy send. No external requests.
- Google preflight: OAuth **HTTP 200**, FCM **HTTP 200** with `validate_only: true`. No real notification sent; no device receipt claim.
- Wrangler module bundle/migration dry run passed. Live deployment status is separate in the release manifest.
- Separate notification Worker final version `7c5ae124-f2b8-4476-8eb0-e94158a7f57f`, verified at 2026-10-16T08:17:22+05:30. Live health returned 200/protocol 2; unauthenticated inbox and obsolete send returned 401; public `/internal/enroll` returned 404. The old Worker's deployed version was unchanged before/after.
- Production dependency audit returned **0 known vulnerabilities** on 2026-10-16; this is the registry advisory result, not a proof of absence of vulnerabilities.
- Gameplay engines `ReliableMatchClient.kt` and `OnlineRoomSyncManager.kt` are unchanged. This adds no server round trip to number picks.

## Remaining acceptance checks and boundaries

1. Install build 43 on both phones. Complete Google sign-in using the confirmed Web audience; verify the Android OAuth package/signing-certificate linkage in the real project if sign-in reports a configuration error. A public client ID alone cannot prove that linkage.
2. Exercise invitations, requests, acceptance and online alerts in foreground, background, lockscreen, process-absent, offline/reconnected and permission-denied/restored states. Check both master/category switches. Confirm one alert per event and no expired invitation after two minutes.
3. Tap old Accept/Decline/body actions during active/paused/host-wait games; change accounts before tapping; kill immediately after Decline; refresh the token. Confirm no room replacement or old-account alert.
4. Android force-stop, OEM battery rules, scheduling and unavailable Play services can delay/block delivery; reopening recovers unexpired inbox data. Native timeout removal is platform-dependent on Android 7; explicit expiry still rejects stale taps.
5. Older unsigned guest accounts cannot securely prove their original UID. Data is preserved; secure invitations/social push require a supported Google sign-in or new guest identity. Do not clear app data or silently reassign identities. Transparent old-guest migration requires a separately trusted ownership mechanism.
6. Public profile/room/friend stores were not replaced. Legacy ownership/room/request checks depend on that baseline data. This authenticates notification callers and hides the new token directory; it does not establish cryptographic ownership of every historical public record or make game data tamper-proof.
7. Live configuration inspection found the existing `bingo-fcm-relay` still has a `MATCHES` binding to `BingoMatch`, migration `v1-match-authority`, deployed version `2df41829-59de-480d-8f6a-e65b06043a18`. To avoid changing that service, this source uses a separate **`bingo-notifications-v2`** Worker and APK endpoint. Previous APKs/web remain on the old endpoint and do not interoperate with new secure notifications. The old relay remains a separate security/retirement item; this deployment does not fix it, and its live notification-handler source was not verified. Inventory consumers before retiring it or migrating its routes. Web needs protocol-2 integration for the secured service.
8. No device was connected and no real invitation, friend request or push was sent. Device checks remain open; they are not reported as passing. Existing unrelated lint warnings and the previously unconfirmed game-screen closure are not represented as fixed.

No finite suite proves zero future bugs. These results describe measured behavior and explicitly retain device/migration limits.

## Baseline and recovery

Original signed APK, checksum, mapping and validation archives: `/home/bob/backups/bingo-notification-hardening-2026-10-16`. Changes are recorded in `NotificationHardeningLog.md`. Preserve current work before switching baseline branches. Backend rollback and Android downgrade need coordinated recovery; never delete notification storage or uninstall merely to roll back code.

## Platform references

- [Google: Android backend authentication](https://developers.google.com/identity/sign-in/android/backend-auth): Web audience and signed-token verification.
- [Firebase: Android message handling](https://firebase.google.com/docs/cloud-messaging/android/receive-messages): data callbacks and WorkManager for longer work.
- [Android notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission): OS permission is independent of app switches.
- [FCM send API](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages/send): `validate_only` validates without delivery.
