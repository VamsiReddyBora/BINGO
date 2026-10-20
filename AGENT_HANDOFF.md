# Agent handoff — Bingo 1.0 / build 46

The active source is this main-branch checkout. The APK is `releases/Bingo-1.0.apk`. Read `releases/Round11Review.md` and `releases/DeferredNetworkingIssues.md` before making further changes. The automatic Round 11–15 handoff was stopped when the user requested this focused takeover, build and publication.

## Preserve these rules

`SimplifiedGameRules.md` is the active specification. Do not change it without the user. Its SHA-256 is `204fb52d6156257e982cfb51620e0e618f8b8cf5f415cd03ab127870d78e318d`.

- Going away or losing connectivity does not surrender or remove a player.
- Status at turn start chooses 30 seconds online or 5 seconds away/offline. Leaving during a 30-second turn does not shorten it. Returning during an unexpired absence turn promotes it once to 30 seconds.
- Expiry passes once without auto-picking or penalties. Rejoin does not recover missed turns.
- Everyone inactive: recover within five minutes with the frozen remaining countdown; inactivity expiry awards no winner. Ongoing player activity keeps the game alive.
- Explicit confirmed exit is permanent; group games continue with remaining participants. Bingo eligibility includes offline participants; board-size target and caller-priority ties remain unchanged.

## Current networking design

Gameplay packets, presence and accepted snapshots remain on MQTT. Room discovery/start/rejoin metadata uses authenticated per-room conditional commits in the existing Cloudflare worker, with revision and exact match-seed comparisons. It does not control turns or picks. `RoomMetadataStore.kt` contains the native transport/coordinator; `cloudflare_worker/rooms.js` contains the backend state transition. The notification-directory Durable Object binding is reused with a distinct instance name per room.

No new backend secret or binding is required. Existing notification identity/session authentication is used. The backend has been deployed for this build; see the review for its version and smoke checks. Legacy data is read until authenticated adoption, after which stale legacy writes cannot overwrite the canonical record. Update all match participants to this APK.

## Validation and work discipline

Run `./gradlew :app:testReleaseUnitTest --rerun --console=plain`, `npm test --prefix cloudflare_worker` and the release build after relevant changes. Retain all original 746 required test cases and every later valid regression. Tests must exercise production handlers or shared transitions actually used by the app. Never weaken assertions or fabricate failing-before/device evidence.

The review records exact final counts, APK checksum and limitations after validation. Device tests remain with the user. Deferred resource work and coverage gaps remain explicit; this is not a claim that every possible bug or device condition has been excluded.
