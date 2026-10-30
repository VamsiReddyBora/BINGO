# Agent handoff — Bingo 1.0.5, Build 84

This is the clean release source. Main and tag v1.0.5 identify the same source tree; previous version tags/APKs/source ZIPs remain separate. Read SimplifiedGameRules.md before changing gameplay. Keep fixes covered by production-path regressions and retain all previous valid assertions. Do not introduce a gameplay-policy change as a networking repair.

## Architecture and behavior

MQTT transports invitations, rooms and gameplay. Firebase stores player data. The authenticated Cloudflare notification relay delivers background pushes; the admin service controls existing player data and update publication. Preserve these roles, game rules, manual pause, turn timers, winners, rejoin behavior and friend/photo synchronization.

This release includes the existing notification-reconnection prompt, 30-minute friend-online return interval, saved-result/lobby fixes, live admin profile/statistics/photo/friendship synchronization and verified legacy account recovery. See ADMIN_APP_SYNC.md and MIGRATION_RECOVERY.md.

## New update support

Read OTA_CONTENT_UPDATES.md and APK_UPDATE_CHANNELS.md. Content updates are data only: supported Home strings, bounded layout values and static backgrounds. No executable code, new native feature or general hotpatching is downloaded. The default visuals remain unchanged. The temporary selected-account test text was withdrawn using a higher content revision; retain that withdrawal manifest on Pages.

APK offers use the public feed or a SHA-256 Firebase-UID target route. Higher known native build numbers win; account changes invalidate stale checks/downloads/installer ownership. Downloads verify size/hash and Android package/version/build/signing identity. Android installer approval is retained. The admin service and Pages controls have already been deployed. Preserve existing Pages content and account-target manifests during future APK publications.

## Build, privacy and validation

Use JDK 17 and SDK 34. See README.md. Supply app/google-services.json, local.release.properties and production signing/deployment inputs privately from the included templates. The published source externalizes deployment values into BuildConfig; it contains no signing keys, device addresses, passwords, real service account, player records or local logs. Existing intentional support contact links remain.

SOURCE_MANIFEST.json records the executed checks, file hashes and APK identity for this release. Policy/JVM checks do not replace native Android installer, WorkManager, glyph, animation or notification testing. Build 84 adds the final result animation preset tested by the owner in the preceding controls build; gameplay and networking are preserved.

## Result animation

Read RESULT_EMOJI_ANIMATION.md. The final preset is 50 emojis, 2.00x up, 0.70x fall, 900 ms launch, 0.80x size, 2.00x rotation, 20–90% vertical area, 96% width, 50% centre and 1.95x sideways drift. Win/loss palettes each contain the owner's fifteen choices. Remove all preview/adjustment UI; keep the compile-time flag and per-device on/off switch. Old experimental tuning/palette keys are intentionally ignored so an upgrade uses the final preset. Do not restore tuning UI or alter match clocks as an animation fix. GameScreen one-shot/lifecycle behavior remains unchanged.

## Verification caveats

The prior controls build had one intermittent unchanged account-migration test failure, followed by passing isolated/full reruns. Its root cause was not investigated as part of the cosmetic work. Preserve evidence and do not describe it as fixed by this release. Native visual/FPS acceptance remains with the owner.

## Release validation fixture correction

RELEASE-TEST-01: an initial release run failed `AuthRepositoryTest.latePreMigrationBackupCannotReplaceRecoveredIdentityAndPhoto` because a fresh fake cloud gateway retained process-wide bin/backup caches from an earlier fake store. The test setup now clears those caches, and the scenario checks that its backup fixture was saved. All original recovery assertions remain. This changes test isolation only; production account and gameplay code are unchanged. The initial failure is retained in private release evidence, and final verification is recorded in SOURCE_MANIFEST.json.

Verification: 1,251 Android tests passed, zero failures and eight optional live/device skips. The clean public source passed the same cases. All gameplay regressions were retained; retired adjustment/picker checks were replaced by final-preset and upgrade tests. Release lint: zero errors, 34 existing warnings. Original signing certificate and APK alignment verified. No new physical-device/FPS measurement was performed.
