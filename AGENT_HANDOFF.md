# Agent handoff — Bingo 1.0.7, Build 95

The Android baseline is the clean v1.0.7 release source; main additionally includes the authorized service-only maintenance described below. Preserve previous releases, their tags, source ZIPs and APKs. Read SimplifiedGameRules.md before gameplay work.

## Features included since public Build 85

- Request to join a friend's waiting lobby, using the compact Join action; existing direct invites remain.
- Latest-only compact speech bubbles above lobby avatars with the accepted fixed placement and bounce animation. Compose messages inline by tapping Chat; Enter or keyboard dismissal sends, blank dismissal closes, long-press offers Delete/Cancel. No presets or temporary tuning gear.
- Live typing indicators and an in-game theme switch.
- Optional Hide status bar switch in Animations, off by default.
- Native light/dark theme alignment and live system-bar diagnostics in Copy app diagnostics.

## Known unresolved issue

The owner confirmed Build 94 still displays a dark status bar when the phone is dark and Bingo is light. The owner explicitly deferred further repair and authorized 1.0.7/build95 publication. This is NOT fixed and must not be described as fixed. Use the new live native/window diagnostics before another speculative change. This release changes only version metadata from the current app; no additional gameplay or networking policy was introduced for publication.

## Preserve existing behavior

MQTT carries gameplay/invitations; Firebase stores player data. Preserve SimplifiedGameRules.md, turns, pause/rejoin/wins, nearby mode, avatars, friends, push notifications, existing result animations and approved support contact links. Automatic MQTT maintenance was authorized on 31 October 2026; manual runs still require a fresh preview/passkey. Read AUTOMATIC_MQTT_CLEANUP.md. This package contains Bingo, without the Sudoku experiment.

Read APK_UPDATE_CHANNELS.md and OTA_CONTENT_UPDATES.md. OTA remains bounded data, not executable code. Package updates require Android installer consent and retain signing/hash checks. Keep historical public/targeted update assets separate.

## Build and verification

Use JDK17 and Android SDK34. Supply google-services.json, deployment values and optional release signing inputs privately as described in README.md/local.release.properties.example. No private credentials, player records, signing keys, device addresses or local logs belong in the public source.

Automated verification is recorded in SOURCE_MANIFEST.json. It does not establish physical-device status-bar appearance, Android installer behavior, notification delivery or frame rate. Optional real-device/live tests require explicit opt-in; no emulator or physical phone is used for release packaging.

Verification: 1,340 automated Android tests passed, zero failures and nine unchanged optional skips. Clean-source case IDs/results match Build94; gameplay and networking logic are unchanged from that build. Android lint: zero errors, 34 warnings. Original signing certificate and APK alignment verified. The known status-bar appearance issue remains unresolved; no new physical-device UI/FPS verification is claimed.

## Service maintenance — 31 October 2026

The owner approved automatic retained cleanup after discussing the policy and requested a used/2,000 admin display. The existing admin Durable Object checks every 15 minutes and handles a bounded 200-record batch, with one-minute continuation for productive backlog. Finished move receipts compact after ten minutes; final boards remain 24 hours. Active/recoverable games, manual pauses, account data and unknown records remain protected. Exact signed 64-bit IDs fix the cleaner's old false exclusions. Verified private Firebase archives precede every deletion; changed/recreated data is not counted as confirmed removal.

The usage counter reads all bingo/# retained topics, not just cleanup filters. This deployment refuses root #; other applications can consume additional shared capacity. A failed inventory displays unknown/last-known values. Current automated checks: 194 backend tests and 45 browser checks passed; a real EMQX/Firebase test compacted two owned receipts and preserved two final boards. Android source, APKs, update feeds and historical release artifacts were not changed for maintenance. Known status-bar issue remains deferred.

MC-001 through MC-004 in AUTOMATIC_MQTT_CLEANUP.md map to permanent tests. Preserve every existing regression assertion. Disable future automatic runs with MQTT_AUTOCLEAN_ENABLED="false" and redeploy; do not delete control-v1 storage or replay old gameplay archives onto an active match.

## Cleanup gameplay/rejoin protections — 31 October 2026

MC-005 and MC-006 add service-only liveness checks before bulk reads or cleanup. An active room or activity within the five-minute rejoin grace period defers housekeeping for a one-minute recheck. Live gameplay beginning during a batch stops remaining deletes; every match category depends on room metadata, lobby and availability records, and each deletion checks the plan deadline. Usage refresh and manual preview also wait, with last-known/deferred status shown explicitly.

Verification: 205 backend tests, 45 browser checks and the opt-in real EMQX/Firebase production-client test passed. The live test exercised actual OnlineRoomSyncManager picks and PeerMatchRecovery, automatic-alarm deferral, one-player reconnect and both-clients-closed cold recovery including an unsnapshotted final pick. Boards, picks, turn, exact timer deadline and chat were preserved. Test fixtures used isolated owned rooms and were removed afterwards. Android runtime source, APKs, update feeds, releases and gameplay rules are unchanged; the only Android addition is an opt-in test. Hardware/UI lifecycle behavior was not tested. MQTT 3.1.1 cannot guarantee atomic conditional deletion against a simultaneous publisher; preserve all liveness, archive, fingerprint and acknowledgement guards.

Read AUTOMATIC_MQTT_CLEANUP.md before changing housekeeping. Keep every regression and the control-v1 Durable Object storage/secrets. A rollback may disable MQTT_AUTOCLEAN_ENABLED without modifying gameplay or replaying archived data.
