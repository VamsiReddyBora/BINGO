# Notification system review — Bingo 1.4.2

Review completed: 2026-10-16T06:32:25+05:30, Asia/Kolkata (UTC+05:30).
Source: `/home/bob/workspace/bingo_multiplayer`, branch `release/1.4.2`, commit `422ee84c911a1c2226a6eb3e4a17d2d5c0a740ce`.
APK reviewed against this source: `releases/Bingo-1.4.2.apk`, version code 42.

## Release assessment

**The notification system is not ready to describe as fully working.** This review found **12 actionable issues: 4 high priority (P1) and 8 medium priority (P2)**. They concern notification delivery, account isolation, notification settings and actions. There are also two coverage/UI observations below.

This was a review, not a repair. Application source, Worker source, credentials, backend data and APK were not changed. No real push messages, friend requests, invitations or gameplay packets were sent. No service was deployed.

The existing gameplay heartbeat and host-disconnection behavior were outside the modification scope. FCM in this code carries invitations and social alerts; it is not the transport used to authorize individual number picks.

## What was verified

| Check | Result | Meaning |
| --- | --- | --- |
| Targeted unit tests | **57 passed**, 5 suites, 0 failures/errors/skips | Existing friends/request reconciliation, presence formatting, invitation models and cooldown tests pass. |
| Actual Worker source with mocked OAuth and FCM | Ran locally | Reproduced unauthenticated forwarding, absent TTL, forwarding an expired invite, repeated forwarding and caller-controlled event type. OAuth cache reuse worked. |
| Live relay health | **HTTP 200**, `status: ok` | A GET with an explicit review User-Agent reached the health endpoint. Its project matched the local Firebase project. The initial request with Python's default User-Agent returned 403; retry succeeded. |
| Firebase configuration | Match | Android package and local service-account project agree. No credentials are included in the evidence. |
| Real FCM send/delivery | **Not tested** | Health does not exercise Google OAuth, FCM send authorization, token registration, delivery or on-device rendering. |
| Android device UI / lockscreen / Doze | **Not tested** | No ADB device was connected. No physical-device or emulator notification test was performed. |

The unit tests do **not** validate the actual Android notification manager, Firebase messaging service, notification actions or OEM delivery behavior. For example, the existing invitation TTL unit test evaluates a list-filter expression; it does not feed an old message through `BingoFirebaseMessagingService`.

Evidence: [Bingo-1.4.2-notification-review-evidence.zip](../../releases/Bingo-1.4.2-notification-review-evidence.zip). It contains the Worker review harness and results, test command/log, five JUnit XML reports and review context. The harness uses synthetic credentials and mocked network functions only.

## Current notification flow

### Game invitation

1. `GameInviteManager.sendInvite` starts a retained MQTT publish, starts an asynchronous FCM send, and saves an invitation list in cloud KeyValue storage.
2. `BingoFcmManager` looks up the recipient's device token and POSTs a data-only payload to the Cloudflare relay.
3. The Worker obtains/caches a Google OAuth access token and calls FCM HTTP v1 with Android priority `HIGH`.
4. `BingoFirebaseMessagingService` handles `GAME_INVITE`. Foreground delivery enters `GameInviteManager.foregroundInviteFlow`; background delivery posts a tray notification and may stage an in-app invite.
5. Separately, `RootNavGraph` listens to the MQTT invite topic and polls cloud invitations every two seconds. Both paths can set the invite popup directly.
6. The tray has Accept, Decline and body-tap actions. Accept queues a room join; body-tap stages an invite; Decline starts cloud/MQTT cleanup.

### Friend request / accepted request

The sender persists the event, publishes a non-retained MQTT packet and sends FCM. MQTT callbacks go through `FriendsRepository` verification and persistent deduplication before displaying Android toasts. **The FCM receiver has no handlers for these two push types.** Cloud polling updates the lists but does not itself issue those toast callbacks.

### Friend online

Presence startup may send friends a `FRIEND_ONLINE` FCM push, with a sender-side two-minute cooldown. MQTT presence transitions independently post a system notification in the background or emit an in-app toast event in the foreground. There is no shared receiving-side event identity between those paths.

### Updates and announcements

Startup checks and MQTT broadcasts set in-app dialog state. A six-hour WorkManager update check queries update information and logs the result. The update and announcement system channels are registered, but neither path posts a system notification in the current source.

## Findings and fixes

### N01 — P1: The relay source accepts unauthenticated push requests

Locations: [worker.js:122](../../cloudflare_worker/worker.js#L122), [worker.js:140](../../cloudflare_worker/worker.js#L140), [BingoFcmManager.kt:171](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoFcmManager.kt#L171), [BingoFcmManager.kt:118](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoFcmManager.kt#L118).

The `/send` handler checks only that a target token exists. It does not authenticate the caller, authorize the recipient, verify the alleged sender, validate an actual invitation/request, or rate-limit sends. The client supplies all event fields and can override `type` through `body.data`. Token lookup also uses a directory accessible through configuration shipped in the app.

Scenario: somebody who obtains a recipient token can ask this source's relay to send an apparent invitation or online alert with a fabricated sender. They do not need the Google service-account key.

Evidence: the actual Worker, run with mocked Google endpoints, forwarded a request with no Authorization header and forwarded a caller-overridden event type. **This was not exercised against the live `/send` endpoint**; live edge protections and deployed-source parity remain unverified. CORS is not caller authentication.

Fix: authenticate requests with a verifiable user/session identity; derive sender identity from it; look up recipient registrations server-side; validate invitation/request membership and allowed fields; rate-limit by sender, recipient and event. Use idempotency keys. Protect token registration/lookup as part of this boundary. A static secret embedded in the APK does not solve this. Keep these operations confined to notification/invitation flows; no change to MQTT number picks is required.

Verify: unauthenticated requests fail before OAuth/FCM; forged identities and unrelated-room sends are rejected; legitimate authenticated notifications still send.

### N02 — P1: Device tokens and incoming events are not isolated when accounts change

Locations: [BingoFcmManager.kt:68](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoFcmManager.kt#L68), [AuthRepository.kt:828](../../app/src/main/java/com/bingo/multiplayer/domain/repository/AuthRepository.kt#L828), [BingoFirebaseMessagingService.kt:29](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoFirebaseMessagingService.kt#L29), [MainActivity.kt:172](../../app/src/main/java/com/bingo/multiplayer/MainActivity.kt#L172), [RootNavGraph.kt:332](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L332).

Tokens are saved under `fcm_<username>`. Sign-out does not remove the old association or cancel account-specific tray/staged/pending invite state. Signing in as another user saves the same installation token under another username. Invitation payloads contain no recipient account identity, and the receiver/action handler does not require a matching authenticated account.

Scenario: Bob signs out and Stuart signs in on the same phone. An invitation for Bob can still reach that phone, appear under Stuart, and its Accept button can join using Stuart's current account. The same problem exists for an already-posted notification or a pending event across logout.

Fix: bind registrations to an installation ID plus authenticated account and generation. Revoke the old association on logout/account switch, without deleting another device's registration; retry offline revocation through durable work. Add recipient account/generation to events and action intents, and reject mismatches locally even if remote cleanup has not completed. Clear account-specific notifications, staged flows, pending actions, incoming popup state and handled-event state on transition. Delay action execution until authentication has settled.

Verify: Bob → logout → Stuart, including offline logout, process death, token refresh during switch, and tapping a pre-switch notification. No Bob event may be displayed or acted on as Stuart.

### N03 — P1: Friend-request and acceptance pushes are silently ignored

Locations: [BingoFcmManager.kt:225](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoFcmManager.kt#L225), [BingoFcmManager.kt:251](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoFcmManager.kt#L251), [BingoFirebaseMessagingService.kt:29](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoFirebaseMessagingService.kt#L29).

The sender emits `FRIEND_REQUEST` and `FRIEND_ACCEPTED`; the receiver handles only `GAME_INVITE` and `FRIEND_ONLINE`. Since the Worker sends data-only messages, FCM will not create a tray notification on the application's behalf for these unhandled messages. See [Firebase's Android receive-message behavior](https://firebase.google.com/docs/cloud-messaging/android/receive-messages).

Scenario: Kevin sends a friend request while Bob's app process is absent and the MQTT listener is not running. Even if FCM delivers successfully, the handler logs an unhandled type and presents nothing. The request list may catch up after launch, but that is not a background push alert.

Fix: implement both handlers and their system-notification/action paths. Include request/event ID, recipient identity, created time and expiry in the sender payload. Route foreground events through the same verified/deduplicated request processing as MQTT; do not bypass `FriendsRepository`'s stale-request protections. Respect notification settings and reject already-handled requests. Use durable background work if validation exceeds the short FCM callback window.

Verify: pending, accepted, declined and already-friends events in foreground, background and process-absent states, including delivery through MQTT and FCM in either order.

### N04 — P1: Accept from an old tray notification can interrupt a live match

Locations: [MainActivity.kt:173](../../app/src/main/java/com/bingo/multiplayer/MainActivity.kt#L173), [RootNavGraph.kt:671](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L671), [RootNavGraph.kt:586](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L586), [RootNavGraph.kt:633](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L633), [OnlineRoomSyncManager.kt:458](../../app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt#L458).

The normal incoming-popup paths check whether a game is active. The tray Accept path goes directly through `pendingJoinRoomCode` to `acceptAndJoinRoom`, which checks only `isJoiningRoom`. On successful validation it overwrites room/mode/host state, reconnects the room transport and navigates to the new lobby. `connectToRoom` disconnects the existing room first.

Scenario: a still-valid invite to room A remains in the tray while Bob starts a match in room B. Tapping Accept for A can switch his connection and navigation away from B without the ordinary leave-match decision.

Fix: enforce the active-match rule in the shared join/action entry point, before any room mutation. While a match is active, reject/defer unrelated invite actions with a clear message. Joining the current match should use the established rejoin path rather than resetting lobby state. Clear consumed action extras or persist consumption so activity recreation cannot repeat them.

Verify: Accept/body-tap during online, nearby and AI games; active host-wait and paused games; ended matches; activity recreation. An unrelated invitation must never mutate an ongoing game.

### N05 — P2: Some invite popups and friend toasts bypass notification preferences

Locations: [RootNavGraph.kt:452](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L452), [RootNavGraph.kt:471](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L471), [RootNavGraph.kt:559](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L559), [NotificationPreferences.kt:186](../../app/src/main/java/com/bingo/multiplayer/core/designsystem/NotificationPreferences.kt#L186).

The FCM/foreground-flow invite path checks `canShowInAppInvite`. The direct MQTT callback and cloud poll set `incomingInvite` without this check. Friend-request and acceptance callbacks unconditionally schedule toasts, including when the activity remains composed in the background. They do not check the in-app master setting or current foreground state.

Scenario: turn off All In-App Notifications or Player Invites; a MQTT/cloud invitation still opens its popup. Disable all in-app alerts; a friend-request callback can still show a toast.

Fix: centralize presentation eligibility across every ingress. Apply the relevant master/category setting and foreground state before showing a popup/toast, and recheck eligibility at presentation time. Preserve underlying request/friend data updates when alerts are disabled. Route background social events to their appropriate system path when allowed.

Verify: system and in-app switches independently and in combination, for MQTT, FCM and polling, including toggling settings while an event is queued.

### N06 — P2: Expired FCM invitations and online events can still notify

Locations: [worker.js:146](../../cloudflare_worker/worker.js#L146), [BingoFirebaseMessagingService.kt:35](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoFirebaseMessagingService.kt#L35), [BingoNotificationManager.kt:217](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoNotificationManager.kt#L217), [BingoNotificationManager.kt:317](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoNotificationManager.kt#L317), [MainActivity.kt:170](../../app/src/main/java/com/bingo/multiplayer/MainActivity.kt#L170).

The relay sets priority without an Android TTL. FCM's default message lifetime is up to four weeks, rather than the app's two-minute invite validity. The FCM receiver posts an invite before checking its age, and the tray Accept action also has no age check. `setTimeoutAfter(60_000)` starts when the notification is posted; it does not validate the original event's age. Online pushes include a timestamp at send time but ignore it on receipt. Missing invitation timestamps become the current time, and the other invite paths accept arbitrarily future timestamps because they check only an upper age bound.

Scenario: internet returns twenty minutes later. The phone can vibrate for an already-expired invitation, or announce that someone is online after they have left. The in-app popup filter may subsequently reject the same old invite, producing contradictory behavior.

Fix: use one created-time/expiry policy across relay, receiver, tray rendering, action execution, MQTT and polling. Set FCM TTL to the remaining event validity; reject expired, malformed and implausibly future events. Set tray timeout to remaining validity. Give online-status events a short freshness window and reconcile with newer known presence. Do not fabricate fresh timestamps for malformed events.

Verify: events just before/at/after expiry, delayed delivery, missing/malformed time, future clock skew, and stale tray taps. Reference: [FCM lifespan and acceptance versus delivery](https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan).

### N07 — P2: A delayed transport can resurrect a handled invite or alert twice

Locations: [RootNavGraph.kt:334](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L334), [RootNavGraph.kt:454](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L454), [RootNavGraph.kt:568](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L568), [RootNavGraph.kt:5207](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L5207), [BingoFirebaseMessagingService.kt:45](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoFirebaseMessagingService.kt#L45), [PresenceManager.kt:724](../../app/src/main/java/com/bingo/multiplayer/domain/network/PresenceManager.kt#L724).

Handled invitations are remembered only by room code in Compose memory for sixty seconds, while invitations remain valid for two minutes. The FCM system-notification path cannot consult that memory. Popup dismissal clears local presentation but does not remove the cloud invitation. Friend-online MQTT and FCM events also post independently, with no common receiver-side deduplication or `setOnlyAlertOnce` policy.

Scenarios: decline an invite shown through MQTT, then background the app before its delayed FCM copy arrives: FCM can post it again. Dismiss a cloud invite and wait sixty seconds: polling can reopen it while still within two minutes. Process recreation loses the handled map. One friend-online event can alert twice through MQTT and FCM.

Fix: give events stable IDs; persist bounded handled/presented records per recipient account and event, retained through event expiry. Route every ingress and notification action through that store. Make a dismissal's cloud semantics explicit while always suppressing the same local event for its remaining validity. For online alerts use a shared presence-session/event key. Deduplicate before sound/vibration, not just after displaying a popup. Keep new invites to a reused room distinct from previously handled events.

Verify: MQTT→FCM and FCM→MQTT; decline before delayed delivery; repeated QoS delivery; process recreation; dismiss at 0 seconds and poll at 61 seconds; fresh re-invite to the same room.

### N08 — P2: Background Decline cleanup can be killed or lost

Locations: [NotificationActionReceiver.kt:23](../../app/src/main/java/com/bingo/multiplayer/domain/network/NotificationActionReceiver.kt#L23), [GameInviteManager.kt:185](../../app/src/main/java/com/bingo/multiplayer/domain/network/GameInviteManager.kt#L185), [GameInviteManager.kt:214](../../app/src/main/java/com/bingo/multiplayer/domain/network/GameInviteManager.kt#L214).

The receiver cancels the tray notification and starts an unowned coroutine before returning. It uses neither `goAsync()` nor WorkManager. MQTT cleanup launches another detached coroutine, and cloud removal has no durable retry. Android may stop the receiver's process after `onReceive` returns; offline deletion also fails silently. There is no persistent local declined-event marker.

Scenario: Bob taps Decline while the app is closed, or while offline. The tray disappears, but the cloud/retained invite survives and can return on launch.

Fix: persist the event's local declined/handled state synchronously, then enqueue unique, idempotent cleanup in WorkManager with network constraints and retries. If using `goAsync`, bound its work and always finish its pending result; defer longer cleanup to durable work. Await MQTT cleanup within the owned job. Ensure cleanup addresses only the intended invite/event.

Verify: app process absent, airplane mode, process termination immediately after Decline, then reconnect/relaunch. The same event must stay dismissed and remote cleanup must eventually complete. Reference: [Android receiver lifetime and asynchronous work](https://developer.android.com/develop/background-work/background-tasks/broadcasts).

### N09 — P2: Removed/non-friends and stale presence can generate online alerts

Locations: [BingoFirebaseMessagingService.kt:62](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoFirebaseMessagingService.kt#L62), [BingoNotificationManager.kt:52](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoNotificationManager.kt#L52), [BingoNotificationManager.kt:158](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoNotificationManager.kt#L158), [FriendsRepository.kt:125](../../app/src/main/java/com/bingo/multiplayer/domain/repository/FriendsRepository.kt#L125), [PresenceManager.kt:685](../../app/src/main/java/com/bingo/multiplayer/domain/network/PresenceManager.kt#L685).

The FCM online handler does not validate current friendship at all. The MQTT path checks a helper that falls back to the old device-wide `friends_list` even when the active account repository says a person is not a friend. Current friends are persisted under account-prefixed keys. That legacy fallback can retain a removed friend's membership. Additionally, `onPresenceReceived` overwrites its map and derives an online transition from status strings without rejecting an older timestamp first.

Scenario: Bob removes Kevin. A queued online push from Kevin still posts; an upgraded installation's old legacy cache can also allow MQTT online alerts after removal. An out-of-order ONLINE after a newer OFFLINE can create a false transition.

Fix: use an account-scoped current friend snapshot and removal markers for both FCM and MQTT, including service-only cold starts; do not fall back to an unowned legacy list after a definitive repository result. Reject stale/out-of-order presence before updating state or alerting. Preserve the legacy migration in the repository rather than re-reading its original cache in notification code.

Verify: newly added friend, removed friend, cold process, legacy upgrade, account switch and reordered presence. Never alert for self, a non-friend, or presence superseded by a newer offline event.

### N10 — P2: Tapping a friend-online notification does not run its intended action

Locations: [BingoNotificationManager.kt:177](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoNotificationManager.kt#L177), [MainActivity.kt:172](../../app/src/main/java/com/bingo/multiplayer/MainActivity.kt#L172), [RootNavGraph.kt:681](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L681).

The notification sets `ACTION_INVITE_FRIEND` and `EXTRA_TARGET_USER`. `MainActivity` has no branch for that action. `pendingInviteFriendUsername` has a consumer but no producer in the current source. Even that consumer only opens the Dashboard and discards the username.

Scenario: the alert says to tap to challenge Kevin. Tapping launches/foregrounds the app, but does not open Kevin's intended friend/challenge context.

Fix: handle the action, validate recipient/current friendship, preserve the target through authentication, and route to the intended friend profile or challenge UI. Do not automatically send an invitation merely because the notification was tapped. Protect ongoing games as in N04.

Verify: cold and warm launch, authenticated/unauthenticated states, removed target and active match.

### N11 — P2: Failure can be presented or logged as a successful send

Locations: [RootNavGraph.kt:3351](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L3351), [RootNavGraph.kt:3440](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L3440), [RootNavGraph.kt:3526](../../app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt#L3526), [GameInviteManager.kt:94](../../app/src/main/java/com/bingo/multiplayer/domain/network/GameInviteManager.kt#L94), [BingoFcmManager.kt:82](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoFcmManager.kt#L82), [BingoFcmManager.kt:176](../../app/src/main/java/com/bingo/multiplayer/domain/network/BingoFcmManager.kt#L176).

All three invite UI call sites say `Invite dispatched ...!` when `sendInvite` returns false. The Boolean itself describes the cloud backup write, while MQTT and FCM run independently and their outcomes are not propagated to those callers. Token registration ignores HTTP status and logs successful sync even for a failed HTTP response. FCM errors are logged; callers do not supply completion handlers, retry transient failures or remove invalid registrations.

Scenario: a failed cloud write and failed MQTT/FCM attempts still produce a successful-looking toast. A token update rejected by the service logs that it was synchronized, leaving background delivery pointed at an old token.

Fix: distinguish queued, persisted, accepted by FCM and failed states; correct the false-result UI branches; inspect registration response codes; persist bounded retryable work for transient send/token failures with event idempotency. Remove invalid registrations only for a confirmed invalid token and only if the stored registration still matches it. Do not call FCM acceptance device delivery. Reference: [Firebase registration management and invalid token handling](https://firebase.google.com/docs/cloud-messaging/manage-tokens).

Verify: MQTT success/cloud failure, FCM success/MQTT failure, all channels unavailable, token upload HTTP 500, FCM 429/503 and `UNREGISTERED`. UI and logs must describe the actual outcome.

### N12 — P2: Failed invitation reads can overwrite other pending invitations

Locations: [GameInviteManager.kt:100](../../app/src/main/java/com/bingo/multiplayer/domain/network/GameInviteManager.kt#L100), [GameInviteManager.kt:127](../../app/src/main/java/com/bingo/multiplayer/domain/network/GameInviteManager.kt#L127), [GameInviteManager.kt:179](../../app/src/main/java/com/bingo/multiplayer/domain/network/GameInviteManager.kt#L179), [GameInviteManager.kt:189](../../app/src/main/java/com/bingo/multiplayer/domain/network/GameInviteManager.kt#L189).

`fetchInvitesForUser` returns `emptyList()` for failed/unavailable/malformed reads as well as a genuinely empty inbox. Send/remove then modify that list and write the whole cloud inbox. A failed read followed by a successful write can erase unrelated pending invites. Concurrent successful read-modify-write operations from different senders can also overwrite one another. Removal clears the single retained topic before confirming the intended event's state.

Scenario: Bob has invitations from Kevin and Stuart. A transient read failure during another send or Decline is followed by a successful save of a one-item or empty list, deleting the other invitation.

Fix: return an explicit success/failure read result; never perform a full-list replacement after an unavailable read. Store events independently, or use a server-side atomic/idempotent mutation with revision checks; a local mutex alone cannot protect writes from other phones. Match retained clearing to the event being handled so a delayed cleanup cannot erase a newer invite. Check save responses and retry safely.

Verify: failed GET then successful POST, malformed read, overlapping sends from two phones, send while another device declines, and delayed cleanup after a fresh invitation.

## Additional coverage/UI observations

### C01 — Update and announcement system notifications are not implemented

[UpdateCheckWorker.kt:19](../../app/src/main/java/com/bingo/multiplayer/domain/network/UpdateCheckWorker.kt#L19) only queries/logs update availability. [BroadcastMessageManager.kt:159](../../app/src/main/java/com/bingo/multiplayer/domain/network/BroadcastMessageManager.kt#L159) and [BroadcastMessageManager.kt:208](../../app/src/main/java/com/bingo/multiplayer/domain/network/BroadcastMessageManager.kt#L208) set in-app dialog state. A search across production source found only the invite and friend-online `notify(...)` calls. Registered update/broadcast channels and reserved notification IDs do not themselves deliver notifications.

This is a feature gap if background update/announcement alerts are part of the release promise. If those are intentionally in-app only, document that and remove misleading claims later; do not advertise killed-app announcement pushes based on the existing relay README.

### C02 — Collapsed custom invite layout needs device verification

[notification_invite_collapsed.xml:12](../../app/src/main/res/layout/notification_invite_collapsed.xml#L12) stacks two text rows plus a 32dp action row, margins and padding. This exceeds the 48dp custom-content space available in collapsed notifications on relevant Android versions, so the promise of always-visible Accept/Decline buttons is at risk of clipping. Rendering was not tested in this review; treat the visual result as unverified, not a reproduced screenshot failure.

Use a compact standard collapsed layout with native notification actions, or redesign the custom layout to fit available height. Test collapsed, expanded, heads-up, lockscreen, light/dark, long names and large font sizes. Reference: [Android custom notification layout limits](https://developer.android.com/develop/ui/views/notifications/custom-notification).

## Existing pieces worth preserving

- Manifest has the messaging service and receiver marked non-exported, and declares `POST_NOTIFICATIONS`; the activity requests permission on Android 13+.
- Posting methods initialize notification channels, and preference evaluators load persisted settings even in a service-only cold start.
- PendingIntents use `FLAG_IMMUTABLE`. Accept launches the activity directly rather than using a notification trampoline.
- The FCM invite branch already separates foreground and background presentation and calls the relevant preference evaluators on its own path.
- The friends repository has account-scoped, persisted request deduplication and verifies requests/friendship before MQTT callbacks. Its 24 targeted tests passed. Keep those protections when adding FCM support.
- The local OAuth token cache was reused correctly in the Worker probe. Real credential validity was not tested.
- The private service-account file is excluded from Git and was not included in review artifacts.

## Required verification after repair

Use two dedicated test accounts/devices. Include API 33+ permission granted/denied and Android 12+ custom-notification rendering. Capture event ID, account ID, created/received/presented times, transport, dedup decision and action result without logging tokens, credentials or private message text.

| Case | Expected result | Review status |
| --- | --- | --- |
| Fresh invitation: foreground/background/process absent | One eligible popup or system alert; correct sender and room | Device test pending |
| Same invite through MQTT, FCM and polling | One alert; no extra vibration | N07 defect identified |
| Request / accepted push while MQTT unavailable | Correct social alert, no stale-request resurrection | N03 defect identified |
| All master/category setting combinations | Disabled presentations suppressed; data still reconciles | N05 defect identified |
| Delayed, expired or future-dated event | Drop invalid alert and invalid actions | N06 defect identified |
| Decline offline then kill/relaunch/reconnect | Remains handled; cleanup retries safely | N07/N08 defects identified |
| Bob logout → Stuart login; old tray tap | No cross-account event/action | N02 defect identified |
| Tap unrelated Accept during an active match | Existing board/transport/state retained | N04 defect identified |
| Tap online alert in cold/warm app | Intended friend context; no automatic send | N10 defect identified |
| Removed friend / reordered presence | No false online alert | N09 defect identified |
| All sends fail; token registration fails | Honest error/queued status and bounded retry | N11 defect identified |
| Failed inbox GET followed by write | Preserve other invitations | N12 defect identified |
| Concurrent invitations and delayed cleanup | Preserve all distinct live events | N12 defect identified |
| Permission denied or channel disabled | No crash; accurate settings explanation; in-app follows its own setting | Device test pending |
| Doze, background restrictions, swipe from recents | Measure delivery and restoration on target devices | Device test pending |
| Force-stop from Android Settings | Do not promise background FCM until the app is reopened | Platform limitation; see below |
| Update/broadcast while app closed | Match explicitly agreed release scope | C01 feature gap |
| Collapsed/expanded/heads-up/lockscreen notification | Readable content and usable actions | C02 device test pending |

High FCM priority is an attempt at timely delivery, not an instant-delivery guarantee. Repeated high-priority messages that do not result in visible notifications can be deprioritized; the unhandled social pushes are therefore also relevant to reliability. See [Firebase Android message priority](https://firebase.google.com/docs/cloud-messaging/android-message-priority). Android force-stop from Settings requires reopening before messages resume, as described in [Firebase's receive-message prerequisites](https://firebase.google.com/docs/cloud-messaging/flutter/receive-messages). Swiping from recents and force-stop must be tested and described separately.

## Suggested repair order

1. Fix the active-match guard and account boundary before allowing notification actions to mutate navigation/room state.
2. Establish authenticated notification sends, explicit event IDs/recipient/expiry, and a shared durable eligibility/deduplication path.
3. Add the missing social handlers through that path; make every ingress respect settings.
4. Make Decline, registration and notification retries durable; fix truthful send results and safe inbox mutations.
5. Resolve the update/announcement scope and verify notification rendering and delivery on the two target phones.

Keep the fixes within notification, invitation, account-transition and action-routing code. Preserve the tested heartbeat timers, host-only pause/resume, host-disconnected hold and match reducer behavior. Rebuild the APK after fixes; the APK linked above still contains the reviewed issues.
