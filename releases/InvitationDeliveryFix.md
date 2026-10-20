# Home ping and invitation delivery — build 47

Updated: 2026-10-20T19:22:53+05:30. Baseline: main commit `9197bced18e42e2f0833b46d0143b11269b683ed` (build 46).

## Changes

1. Home ping displays the value alone, for example `30ms`. The accessibility description retains the metric source. No latency calculation or connection-color rule changed. Location: `MainMenuScreen.kt`, ping Text.
2. A freshly verified canonical WAITING room owned by the sender is ready for an invitation. A redundant retained MQTT room-metadata publication can no longer block both peer and authenticated inbox/push delivery. Canonical room creation returns success after its conditional storage commit even if its best-effort MQTT mirror fails. Locations: `OnlineRoomRegistry.ensureInvitationReadinessDetailed` and `createRoom`. Legacy-only discovery retains its original publication checks.
3. Initial sends and durable retries now use `GameInviteManager.dispatchInviteAttempt`. Once readiness recovers, the worker resends the MQTT invitation and registers the inbox/push event, using the original event ID, creation time and expiry. Previously it retried only event registration. Invalid/closed/started rooms, obsolete accounts and expired invites remain rejected; a real metadata outage still queues work.
4. Queued invitations request expedited WorkManager scheduling on Android 12 and later, falling back to ordinary work when quota is exhausted. Android 11 and earlier retain ordinary scheduling to avoid adding a foreground-service requirement. This scheduling behavior is source-reviewed, not executed on devices in this pass.

The delivery result remains truthful: `Invitation sent` requires successful event registration. `Invitation queued` still applies to actual temporary service/network failures; the message is not renamed to pretend delivery occurred. MQTT remains the gameplay transport. The conditional room protocol, authentication, board/turn rules and profile/social behavior are preserved. No backend runtime implementation changed or needed redeployment.

## Permanent regression evidence

`InvitationDeliveryRegressionTest` tests the actual registry entry points and the shared dispatcher called by the button action and NotificationWorker. Its metadata/MQTT transports are controlled test dependencies, rather than a copied decision model.

| Finding | Before proof | After coverage |
|---|---|---|
| I47-01: canonical readiness wrongly depends on redundant MQTT publication | Executed failure: `canonicalInvitationIsReadyWhenRedundantMqttMetadataPublicationFails` | Same test plus canonical delivery through both channels |
| I47-02: committed room reported as failed when mirror publication fails | Executed failure: `committedCanonicalRoomCreationSucceedsWhenMqttMetadataPublicationFails` | Same test; committed room identity/status retained |
| I47-03: durable retry does not resend MQTT invitation | Source-confirmed in baseline worker; no executed failing-before full worker test claimed | Shared production dispatcher tested across metadata outage/recovery; worker wiring source-reviewed |

Additional cases cover closed/started/foreign-host rejection, missing-room cold retries without recreation, independent inbox delivery after a peer hint failure, legacy mirror-pending versus true unavailability, account/expiry fencing, and not reporting failed registration as success.

Two backend tests run the real worker routes and notification/room objects: authenticated canonical invite → recipient inbox and FCM outbox (including idempotent retry), and rejection of started/closed rooms despite stale legacy WAITING data. These tests prove queue creation, not Google FCM delivery to a phone.

Before evidence: `/home/bob/workspace/bingo-invitation-fix-20261020-191423/failing-before-tests.log` (two failures before the fixes, with only the transport test seam installed). The original source archive and source commit are saved alongside it.

## Validation

- Full release tests/build: `./gradlew :app:testReleaseUnitTest :app:assembleRelease --rerun --console=plain` — BUILD SUCCESSFUL.
- **774 passed, 0 failed, 1 skipped** (existing opt-in live MQTT test). All **764** previously passing cases remain passing, including the required baseline and Round 11 regressions.
- **63 backend tests passed, 0 failed**, including both new canonical-invitation integration tests.
- Package `com.bingo.multiplayer`, version **1.0 (47)**. APK signature verified and signer matches build 46.
- APK size: **7,314,171 bytes**. SHA-256: `959fada480696ac6eb1fb8e65a10f42e9e3663e84c2cad0318adb70c7444e5bd`.
- APK copies: `/home/bob/workspace/Bingo-1.0.apk` and `releases/Bingo-1.0.apk`.
- `SimplifiedGameRules.md` SHA-256 unchanged: `204fb52d6156257e982cfb51620e0e618f8b8cf5f415cd03ab127870d78e318d`.
- Final evidence: `/home/bob/workspace/bingo-invitation-fix-20261020-191423/full-tests-and-build.log`, `final-unit-test-results.json`, `baseline-validation.json`, `backend-tests.log`, `apk-signature.txt`.
- Completed: 2026-10-20T19:26:29+05:30.


## Limits and follow-up

No phone logs, emulator execution or physical notification screenshots were captured for this follow-up. Therefore the code-confirmed queue cause is repaired, while actual delivery to the user's two phones remains to be checked with this APK. WorkManager lifecycle timing and vendor push restrictions are not established by JVM/Node tests. Previously documented P3 closure-worker resource duplication and integration-evidence gaps remain in `DeferredNetworkingIssues.md`.

The new build is prepared locally for the user; this follow-up does not replace the previously published build 46 GitHub asset.

This historical local build 47 report is superseded by [build 48 live-service investigation](Build48Review.md).
