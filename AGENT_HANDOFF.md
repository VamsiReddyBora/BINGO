# Agent handoff — Bingo 1.0 / build 48

The active source is this main checkout. The APK is `releases/Bingo-1.0.apk`; a clearly named workspace copy is `/home/bob/workspace/Bingo-1.0-build48.apk`. Settings reads the installed PackageInfo and shows `Version 1.0 · Build 48`. Read `releases/Build48Review.md`, the historical `releases/InvitationDeliveryFix.md` / `releases/Round11Review.md`, and `releases/DeferredNetworkingIssues.md`. The old automatic review loop remains stopped.

## Preserve these rules

`SimplifiedGameRules.md` is the active specification. Do not change it without the user. Its SHA-256 is `204fb52d6156257e982cfb51620e0e618f8b8cf5f415cd03ab127870d78e318d`.

- Going away or losing connectivity does not surrender or remove a player.
- Status at turn start chooses 30 seconds online or 5 seconds away/offline. Leaving during a 30-second turn does not shorten it. Returning during an unexpired absence turn promotes it once to 30 seconds.
- Expiry passes once without auto-picking or penalties. Rejoin does not recover missed turns.
- Everyone inactive: recover within five minutes with the frozen remaining countdown; inactivity expiry awards no winner. Ongoing player activity keeps the game alive.
- Explicit confirmed exit is permanent; group games continue with remaining participants. Bingo eligibility includes offline participants; board-size target and caller-priority ties remain unchanged.

## Current networking design

Gameplay packets, presence and accepted snapshots remain on MQTT. Room discovery/start/rejoin metadata uses authenticated per-room conditional commits in the existing Cloudflare worker, with revision and exact match-seed comparisons. It does not control turns or picks. `RoomMetadataStore.kt` contains the native transport/coordinator; `cloudflare_worker/rooms.js` contains the backend transition. The existing notification-directory Durable Object binding is reused with a distinct instance name per room. No new secret or binding is required.

Cold legacy migration uses bounded eight-second upstream reads and a matching 20-second native metadata budget; canonical records return directly. Backend version `9180067e-9b37-42d4-b640-472f7d4596fc` was deployed and its authenticated room → invitation → recipient inbox route tested with owned identities. This is not a per-pick HTTP dependency.

A verified canonical WAITING room owned by the sender is already discoverable. Do not restore the redundant MQTT metadata-publication gate that blocked invitations in build 46. Initial sends and durable retries use `GameInviteManager.dispatchInviteAttempt`; retries preserve event ID, creation time and expiry and resend the live hint. Real metadata outages remain queued; closed/started/foreign-host rooms and expired or obsolete-account requests remain rejected.

## Validation and work discipline

No regression is acceptable. Preserve all valid baseline cases, reproduce defects before and after changes, exercise actual production handlers/shared transitions, and disclose missing execution evidence. Never weaken assertions or use copied-expression tests as complete callback proof.

Final build command: `BINGO_LIVE_MQTT=1 ./gradlew :app:testReleaseUnitTest :app:assembleRelease --rerun --console=plain`. Result: **777 passed, zero failed or skipped**, including actual MQTT invitation delivery, retained reconnect delivery, accepted gameplay commits, cold recovery and explicit surrender. All prior 774 passing cases remain passing. `npm test --prefix cloudflare_worker`: **64 passed**. APK signature matches previous releases.

Ordinary unit fixtures must not publish test room metadata to production. NetworkRound1RegressionTest supplies a controlled publisher per case. Opt-in live tests use private random identities/topics and remove their own retained data. Backed up and removed 56 unmistakable old unit-test records; no real six-character room was removed. A fresh broker audit found zero new records from the isolated fixtures after the full suite. Keep those isolation hooks and all original assertions.

## Remaining limits

Physical phone UI/FCM presentation, Google sign-in on the user's phone, vendor background restrictions and complete Compose/WorkManager lifecycle execution were not established by these headless checks. The prior P3 closure-retry resource duplication and other evidence gaps remain documented. Broker capacity still needs monitoring as production usage grows. Do not claim universal zero bugs. The GitHub release description links the exact updated source commit; the historical v1.0.0 tag is unchanged.

## Required APK delivery

After every APK build, overwrite `Bingo.apk` on both SSH destinations configured in `~/.config/bingo/apk-delivery.json`. Debug/release assemble tasks do this automatically through `tools/deliver_apk.py`; for APKs built by another route, run the helper explicitly. See `tools/APK_DELIVERY.md`. Credentials stay outside Git and logs. Verify both destination checksums in the delivery receipt and retry any failed copy; do not report full delivery success if either destination failed. This is a standing user requirement for future sessions.
