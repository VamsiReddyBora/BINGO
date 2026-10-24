# Bingo 1.0.2 — Build 67

Android Bingo with AI, MQTT online multiplayer, Nearby Network play, friends and background notifications.

## This release

- Home opens using local authentication and never waits for saved-room discovery. Automatic update/broadcast dialogs wait for the first rendered app destination.
- Completed checkpoints open in a read-only saved-result screen without reconnecting to the old match. Boards, selected numbers and accepted chat remain available; Home closes only the matching saved-result card.
- Live finished-board viewers can continue reviewing after the host intentionally closes the lobby. The result offers Back to Home instead of entering a stale lobby.
- A backgrounded or unreachable host produces a waiting state, not an intentional lobby closure. A fresh MQTT room response is required before Return to Lobby changes local match state.
- Existing gameplay rules, continuous-one-hour FCM online-alert policy, invitations, friends, photos, Online and Nearby behavior are preserved.

- The Home EMQX note uses its original server wording and keeps the ten-second outage state across Dashboard/Settings navigation. Internet loss, real broker recovery and app foreground grace retain their existing behavior.

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

Versions 1.0, 1.0.1 and 1.0.2 have separate Git tags, APKs and source archives. Main reflects the latest published source. Source packages exclude signing keys, passwords, real Firebase client configuration, account/player data, device addresses, local tooling, logs and build caches. Existing intentional in-app developer contact links are retained.

See `AGENT_HANDOFF.md` for verification results and remaining hardware acceptance limits.
