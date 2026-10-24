# Agent handoff — Bingo 1.0.2, Build 67

## Startup and results

- `AppLaunchReadiness` retains the one-second intro plus at most half a second for local authentication. AuthGate performs no room lookup. MainMenu handles unfinished-match discovery after rendering.
- MainActivity waits for RootNavGraph's rendered-destination readiness before automatic checks or presenting update/broadcast dialogs. Manual update checks remain supported.
- `SavedMatchOpening` routes completed checkpoints to `SavedMatchResultScreen`; it never connects to the old room or starts a live turn. Accepted history/statistics use the existing stable result ID, preserving idempotence.
- `PostMatchLobbyAccess` distinguishes explicit room closure from temporary unavailability. Process room departure before finished-match packet filtering; do not convert it to a new surrender or alter the accepted result.
- Live results require a fresh MQTT writer response before returning to a lobby. Retained room records alone cannot admit a result viewer. Closure wins over in-flight reads, and old room/session responses cannot enter another lobby.
- Preserve board inspection after closure. Offer Back to Home for closed or unavailable lobbies and Check lobby for temporary waiting. Host background/network/process loss remains recoverable according to the existing rules.
- Source wiring guards and runtime callback/MQTT tests are `StartupResultWiringRegressionTest`, `StartupAndPostMatchRegressionTest`, and `Version102UpdateFeedTest`. Before-fix proof consists of five failing source-wiring assertions; phone timing/UI reproduction was not executed. Runtime callback, virtual-time and controlled-MQTT tests execute the final production helpers.
- `SimplifiedGameRules.md`, picks/turn timing, winners, explicit in-game surrender, friends, photos, FCM policy and Nearby rules are unchanged. Do not add a server authority, room crypto handshake, or new storage protocol.

Validation totals and hardware limits are in SOURCE_MANIFEST.json and release notes. Real-phone startup duration, rendering and background behavior still require acceptance on hardware. Source/tests do not establish a measured device launch time.

## Home broker warning

- Keep the warning on Home only. `BrokerWarningPresentation` owns one application-context `BrokerWarningState`, eagerly shared independently of Home collectors. Do not recreate/cancel its grace period on tab switches or reset its first rendered value to false.
- Keep the original ten-second online/foreground/broker-down policy. Genuine broker recovery clears the warning; phone internet loss and app background still suppress it and retain the existing resume grace.
- The note reads “Note: EMQX server is temporarily unavailable. Online game play may not work properly. Please use Developer note to report this issue.” and keeps the existing Developer Note action and styling.
- `BrokerWarningStateTest` exercises tab unsubscribe/resubscribe, grace while away, recovery while away, connectivity/background grace, and single upstream subscription. All six tests failed with the old cold ownership/wording before the correction. This is executed flow/helper and source-wiring proof, not a rendered phone test.

## Existing notification behavior and private configuration

## Preserve these behaviors

- Read `SimplifiedGameRules.md`. Preserve all existing Online, Nearby, AI, chat, friend, avatar and invitation flows.
- `FriendOnlineAnnouncements` is the persistent sender-side eligibility policy. It measures continuous absence, including app/process restarts. Background/offline transitions persist immediately; confirmed foreground MQTT activity checkpoints every 15 seconds. Unknown hard-kill timing adds a conservative 15-second margin.
- A qualifying return creates one persisted event identity with an original two-minute expiry. Each friend is recorded after durable work enqueue. Short returns do not reset or renew the event. Gameplay presence remains MQTT; no notification HTTP request runs in the pick/turn path.
- The relay and presentation ledger provide one-hour duplicate guards. They do not replace the sender's continuous-absence decision.
- FCM invitations and online alerts pass the real account/installation/generation/expiry checks, present immediately, and synchronize the authenticated inbox afterward. Friend request and acceptance validation still uses the canonical inbox. Never bypass membership checks for room admission.
- New registrations advertise the longer online lifetime. Older APK registrations retain their original 15-second FCM and inbox payload contract. Invitations retain the original two-minute expiry on all versions.
- Delivery diagnostics are bounded and private. FCM received, Android notification posted, and an alert actually observed by a person are distinct events.
- Earlier Nearby host holding, recovery, MQTT shutdown protection, friend ordering and avatar fixes are retained. Whole-host-process-kill Nearby recovery remains separate work.

## Verification

Final validation totals are included in `SOURCE_MANIFEST.json` and the release notes. Regression suites include `FriendOnlineAnnouncementsTest`, `FriendOnlineDeliveryRegressionTest`, `OfflineInvitationPushTest`, and `Version101UpdateFeedTest`. The original delivery-lifetime and repeat-alert tests failed before the fixes. Physical-device acceptance and OEM background restrictions are not certified by unit tests.

## Configuration and future changes

Run native and clean-source full suites sequentially: the Nearby integration tests use fixed localhost ports, so concurrent suites can collide. `PresenceAndFriendRequestTest.testOnlineRoomSession_compactPayloadUnderLimit` now uses a fixed timestamp fixture; its existing 700-character limit and lossless compatibility assertions are unchanged. This removes clock-dependent encoded-size variation without changing production serialization.

Read the README and keep private deployment inputs out of Git. Use the repository owner's GitHub noreply identity for publishing commits. Preserve old release tags and artifacts when publishing another version.

Keep fixes fixed: exercise the production path, retain earlier tests, record evidence and distinguish source review from device testing. Keep MQTT as messaging and Firebase as account/history storage. Do not add match authorities, key-pinning handshakes or MQTT storage without an explicit requirement.
