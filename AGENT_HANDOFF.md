# Agent handoff — Bingo 1.0.1, Build 66

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

Read the README and keep private deployment inputs out of Git. Use the repository owner's GitHub noreply identity for publishing commits. Preserve old release tags and artifacts when publishing another version.

Keep fixes fixed: exercise the production path, retain earlier tests, record evidence and distinguish source review from device testing. Keep MQTT as messaging and Firebase as account/history storage. Do not add match authorities, key-pinning handshakes or MQTT storage without an explicit requirement.
