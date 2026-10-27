# Agent handoff — Bingo 1.0.4, Build 76

This is the clean release source. Main and tag v1.0.4 identify the same source tree; previous version tags/APKs/source ZIPs remain separate. Read SimplifiedGameRules.md before changing gameplay. Keep fixes covered by production-path regressions and retain all previous valid assertions. Do not introduce a gameplay-policy change as a networking repair.

## Architecture and behavior

MQTT transports invitations, rooms and gameplay. Firebase stores player data. The authenticated Cloudflare notification relay delivers background pushes; the admin service controls existing player data and update publication. Preserve these roles, game rules, manual pause, turn timers, winners, rejoin behavior and friend/photo synchronization.

This release includes the existing notification-reconnection prompt, 30-minute friend-online return interval, saved-result/lobby fixes, live admin profile/statistics/photo/friendship synchronization and verified legacy account recovery. See ADMIN_APP_SYNC.md and MIGRATION_RECOVERY.md.

## New update support

Read OTA_CONTENT_UPDATES.md and APK_UPDATE_CHANNELS.md. Content updates are data only: supported Home strings, bounded layout values and static backgrounds. No executable code, new native feature or general hotpatching is downloaded. The default visuals remain unchanged. The temporary selected-account test text was withdrawn using a higher content revision; retain that withdrawal manifest on Pages.

APK offers use the public feed or a SHA-256 Firebase-UID target route. Higher known native build numbers win; account changes invalidate stale checks/downloads/installer ownership. Downloads verify size/hash and Android package/version/build/signing identity. Android installer approval is retained. The admin service and Pages controls have already been deployed. Preserve existing Pages content and account-target manifests during future APK publications.

## Build, privacy and validation

Use JDK 17 and SDK 34. See README.md. Supply app/google-services.json, local.release.properties and production signing/deployment inputs privately from the included templates. The published source externalizes deployment values into BuildConfig; it contains no signing keys, device addresses, passwords, real service account, player records or local logs. Existing intentional support contact links remain.

SOURCE_MANIFEST.json records the executed release verification and file hashes. Policy/JVM/browser checks do not replace native Android installer, WorkManager or notification testing. The previous Build 75 APK and a selected-account content change were exercised by the owner. Build 76 is a versioned publication of that implementation, with no new gameplay changes.

## Release validation fixture correction

RELEASE-TEST-01: an initial release run failed `AuthRepositoryTest.latePreMigrationBackupCannotReplaceRecoveredIdentityAndPhoto` because a fresh fake cloud gateway retained process-wide bin/backup caches from an earlier fake store. The test setup now clears those caches, and the scenario checks that its backup fixture was saved. All original recovery assertions remain. This changes test isolation only; production account and gameplay code are unchanged. The initial failure is retained in private release evidence, and final verification is recorded in SOURCE_MANIFEST.json.
