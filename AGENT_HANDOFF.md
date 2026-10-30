# Agent handoff — Bingo 1.0.6, Build 85

This is the clean release source. Main and tag v1.0.6 identify this version; keep previous release tags, APKs and source ZIPs unchanged. Read SimplifiedGameRules.md before gameplay work. Preserve all intentional behavior and valid production-path regression coverage.

## Current change

Read MANUAL_MQTT_CLEANUP.md. Owner-requested cleanup is manual only, through Settings → MQTT record cleanup or `node tools/cleanup-retained.mjs --apply`. There is no scheduler or automatic retry. Both use the same production planner/executor, exact-topic checks, verified private Firebase recovery archive and fresh broker readback. Waiting/playing lobbies, active matches and unverified accounts are protected. One invocation is bounded; the owner can request another batch.

AccountSessionManager now publishes legacy registry/Google account announcements live with retain=false. Firebase remains durable account storage; no gameplay or authorization policy changes were made. The old retained-publication test was intentionally replaced by a live-publication requirement, while its identity/payload assertions remain. The AuthRepository public-key publication test now captures the actual live publication rather than relying on the retired retained copy; stable-key/restart/rename/avatar/career assertions remain.

## Preserve existing features

MQTT transports invitations, rooms and gameplay. Firebase stores players. The authenticated notification relay handles background pushes. Existing admin profile/statistics/photo/friendship sync, migrated accounts, 30-minute friend-online interval, update channels and result animations remain included. Preserve SimplifiedGameRules.md, turn clocks, pause, recovery, wins, invitations, nearby mode and existing support links. This package contains Bingo only.

Read OTA_CONTENT_UPDATES.md and APK_UPDATE_CHANNELS.md before publication. Preserve all current public/targeted update manifests and past APKs. OTA remains bounded data, never downloaded executable code. Package updates retain Android installer consent and signature/hash checks.

## Build and verification

Use JDK 17 and SDK 34. Supply Firebase client config, deployment values and release signing inputs privately using README.md and local.release.properties.example. SOURCE_MANIFEST.json records checks and hashes. No signing keys, service account, device addresses, passwords, player data or operator logs belong in public source.

The new regression reproduced retained account copies before the fix. Shared maintenance tests cover protected records, archive failures, changed packets, exact deletes, fresh passkey approval and absence of scheduled cleanup. The browser harness exercises the actual admin UI and service with virtual WebAuthn. The live cleanup check uses only newly created owned fixture records on the real broker and a verified private Firebase archive; it does not delete user records. Android/JVM and browser checks do not replace physical-device notification, installer, animation or FPS testing.

Verification: 1,252 Android tests passed, zero failures and eight optional live/device skips. The clean public source passed the same cases. All gameplay regressions were retained; retired retained-account checks were replaced by live-publication checks preserving their payload/key assertions. Release lint: zero errors, 34 existing warnings. Original signing certificate and APK alignment verified. No new physical-device/FPS measurement was performed.
