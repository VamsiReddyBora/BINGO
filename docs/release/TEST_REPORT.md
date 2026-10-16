# 1.4.2 / 43 notification validation

Completed 2026-10-16T08:17:44+05:30, Asia/Kolkata. Source branch `fix/notifications-1.4.2`, implementation `1e954987b0c1a1ff8f1cba9ade9824fc4423f281`.

- Final `testDebugUnitTest lintDebug assembleRelease`: **passed**; 512 tests / 47 suites, zero failures/errors/skips.
- Lint: **0 errors / 32 warnings**. Existing warnings are recorded, not described as zero warnings.
- Relay: **54 passed / 0 failed**. Actual SQLite/workerd integration: **5 checks passed**.
- Google OAuth and FCM validation: **HTTP 200 / 200**, `validate_only: true`, no real notification sent.
- Worker module/migration dry run: passed. No service-account key/email embedded in the bundle.
- Signed package `com.bingo.multiplayer`, version **1.4.2 / 43**, existing release certificate verified.
- APK `Bingo-1.4.2.apk`: 7,117,554 bytes; SHA256 `80236de25e712c2844b35f7d0e80313d8df08ebabdc6005de1aa553cae96e8c9`.
- Backend: **deployed; live health and rejection checks passed**; worker `bingo-notifications-v2`. See `ReleaseManifest.json` for live checks/version if deployed.
- Old `bingo-fcm-relay` / `BingoMatch` service was not modified. Its legacy notification endpoint retirement remains a separate open item.
- Game engines `ReliableMatchClient.kt` and `OnlineRoomSyncManager.kt` unchanged. Notification entry guards and social presence fields are the only related navigation/presence changes.
- Evidence/logs/XML/lint/signature are in `releases/Bingo-1.4.2-validation.zip`; exact R8 mapping is in `releases/Bingo-1.4.2-mapping.zip`.

Read `NotificationHardeningReport-2026-10-16.md` for N01–N12/C01/C02 repairs, old guest/web compatibility and remaining two-phone acceptance checks. No physical-device testing or real push/invitation/friend-request delivery occurred. Zero future bugs is not guaranteed. The earlier unconfirmed game-screen closure remains unconfirmed; Settings diagnostics and exact crash mapping are preserved.
