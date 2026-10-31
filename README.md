# Bingo 1.0.7 — Build 95

Android Bingo with AI, MQTT multiplayer, Nearby Network play, friends and notifications.

## This release

- Compact lobby profile messages, inline keyboard sending, typing indicators and join requests.
- In-game theme switching, optional status-bar hiding, and expanded app diagnostics.
- **Known issue:** with the phone in dark mode and Bingo in light mode, the status bar may remain dark on some devices. This is not fixed in this release.

- Manual retained-message maintenance is available in admin Settings and an operator script. No automatic cleanup is enabled. Redundant account announcements are now live-only; Firebase account storage and gameplay remain unchanged. See [MANUAL_MQTT_CLEANUP.md](MANUAL_MQTT_CLEANUP.md).

- Manual retained-message maintenance is available in admin Settings and an operator script. No automatic cleanup is enabled. Redundant account announcements are now live-only; Firebase account storage and gameplay remain unchanged. See [MANUAL_MQTT_CLEANUP.md](MANUAL_MQTT_CLEANUP.md).

- Win/loss emoji effects use the owner-tested final 50-emoji preset and two colorful fifteen-emoji palettes. Settings retains a simple on/off switch; testing controls are removed. See [RESULT_EMOJI_ANIMATION.md](RESULT_EMOJI_ANIMATION.md).

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

Versions 1.0, 1.0.1, 1.0.2, 1.0.3, 1.0.4 1.0.5 and 1.0.6 have separate Git tags, APKs and source archives. Main reflects the latest published source. Source packages exclude signing keys, passwords, real Firebase client configuration, account/player data, device addresses, local state, logs and build caches. Existing intentional in-app developer contact links are retained.

See `AGENT_HANDOFF.md` for verification results and remaining hardware acceptance limits.

The admin console has a website-only usability update: validated session restoration, coherent app versions, clearer status, black dark theme and combined player controls. See ADMIN_USABILITY.md. This release adds the manual maintenance controls to the admin service and site.

Verification: 1,340 automated Android tests passed, zero failures and nine unchanged optional skips. Clean-source case IDs/results match Build94; gameplay and networking logic are unchanged from that build. Android lint: zero errors, 34 warnings. Original signing certificate and APK alignment verified. The known status-bar appearance issue remains unresolved; no new physical-device UI/FPS verification is claimed.

Current main includes service-only automatic retained-message maintenance and the admin usage counter. See AUTOMATIC_MQTT_CLEANUP.md. Android 1.0.7 / Build 95 and its release assets are unchanged.
