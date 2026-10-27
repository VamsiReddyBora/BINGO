# Bingo 1.0.4 — Build 76

Android Bingo with AI, MQTT multiplayer, Nearby Network play, friends and notifications.

## This release

- Supported Home text, layout values and static images can update in the background, with progress, validation and rollback. Current visuals remain the defaults. See [OTA_CONTENT_UPDATES.md](OTA_CONTENT_UPDATES.md).
- APK updates can be offered to everyone or one registered account. Downloads verify size, checksum, package, build and signing certificate. Android still asks for installation approval. See [APK_UPDATE_CHANNELS.md](APK_UPDATE_CHANNELS.md).
- Existing admin profile/statistics/photo/friendship synchronization and legacy account recovery remain included.
- Existing gameplay rules, MQTT match traffic, Firebase storage and notification behavior are preserved. This release contains Bingo only.

## Source layout

`app/` contains the Android application and regression tests. `web/` is the companion web client. `cloudflare_worker/` is the authenticated notification relay. Firebase rules are in `firestore.rules`. Read `SimplifiedGameRules.md` and `AGENT_HANDOFF.md` before changing gameplay.

## Android build

Use JDK 17 and Android SDK 34. Set the SDK location through `ANDROID_HOME` or a private `local.properties` file.

1. Supply your own `app/google-services.json`; a schema example is included.
2. Copy `local.release.properties.example` to `local.release.properties` and fill deployment values privately.
3. Run `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleDebug`.
4. For a signed release, supply the keystore path, alias and passwords privately and run `./gradlew :app:assembleRelease`. Without signing configuration the release is unsigned.

For release optimization on larger projects, use a 4 GiB Gradle heap when memory permits, for example `./gradlew --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx4096m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' :app:assembleRelease`.

Outputs are under `app/build/outputs/apk/`. Public source does not copy files to personal devices. Production credentials and the signing key are required to reproduce the owner's deployment; byte-for-byte APK reproduction is not claimed.

## Web client and notification relay

From `web/`, run `npm ci`, configure `.env.local` from `.env.example`, then run `npm test` or `npm run build`.

From `cloudflare_worker/`, run `npm ci` and `npm test`. Supply `SERVICE_ACCOUNT_JSON` and `LEGACY_APP_KEY` using Wrangler secrets; `.dev.vars.example` lists local secret names. Existing public identifiers and Durable Object bindings are provided in `wrangler.toml`. Never commit a real service account or private variables file.

## Versioned releases and privacy

Versions 1.0, 1.0.1, 1.0.2, 1.0.3 and 1.0.4 have separate Git tags, APKs and source archives. Main reflects the latest published source. Source packages exclude signing keys, passwords, real Firebase client configuration, account/player data, device addresses, local state, logs and build caches. Existing intentional in-app developer contact links are retained.

See `AGENT_HANDOFF.md` for verification results and remaining hardware acceptance limits.

The admin console has a website-only usability update: validated session restoration, coherent app versions, clearer status, black dark theme and combined player controls. See ADMIN_USABILITY.md. The Android APK is unchanged.
