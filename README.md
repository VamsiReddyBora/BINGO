# Bingo Multiplayer

This is the single active source folder for the Android release candidate **1.4.2 (version code 43)**. Make all new changes here: `/home/bob/workspace/bingo_multiplayer`.

## Project layout

- `app/` — Android app, assets and unit tests.
- `gradle/`, `gradlew`, `build.gradle`, `settings.gradle` — Android build configuration.
- `releases/` — current signed APK and its verification information.
- `docs/release/` — current change log, test evidence and release handoff.
- `web/` — companion web source, retained with its existing behavior.
- `cloudflare_worker/` — protocol-2 notification service; see its README for rollout and compatibility. `firebase.json`, `firestore.rules` retain the existing application-data configuration.
- `bingo.py` — existing optional development/ADB helper.

## Build

Use JDK 17 and Android SDK 34. Set your SDK path in ignored `local.properties`.

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease --offline --console=plain --max-workers=2
```

The signed APK is copied to `releases/Bingo-1.4.2.apk`. Future version names automatically determine the APK filename. Keep existing signing configuration/key files intact when building updates.

## Current behavior

The accepted heartbeat/reconnection behavior remains unchanged. System chat uses compact centered coloured text, and disconnect popups resolve profile photos through the same username/cache lookup as other player avatars. Read `AGENT_HANDOFF.md` and `docs/release/CHANGELOG.md` before continuing work.

Notifications now use verified identities, private inboxes, persistent deduplication, account-scoped actions, original expiry and durable retries. Number picks do not use the notification service. Read `docs/release/NotificationHardeningReport-2026-10-16.md` for the audit fixes, real-device checks, old-guest/web compatibility and the separate legacy-service retirement item. Final deployment status is in `releases/ReleaseManifest.json`.

## History and backups

Prior working folders, build artifacts, old reports and an independent original 1.4.1 source snapshot are preserved outside the workspace at `/home/bob/backups/bingo-release-preparation-2026-10-15`. Their Git branches are retained. Use that backup inventory rather than creating another active source copy. Nothing has been published by this preparation task.
