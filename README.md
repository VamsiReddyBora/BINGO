# BINGO 2.0 — Build 137

Android Bingo with AI, MQTT multiplayer, Nearby Network play, lobby bots, friends, notifications and in-app APK updates.

## Release 2.0

This release publishes the latest tested Android app, including native Admin Control for the two configured administrator identities, retained-message usage and topic partitions, and biometric-protected shared cleanup settings.

The admin latency chart supports centred pinch zoom, horizontal history navigation and tap inspection. Reset opens the latest 60 seconds at 0–250 ms, with one-second tick marks. Automatic scales keep the trace visible. Actual recorded averages are connected across gaps, and peaks appear in the selected record's tooltip. Lines between observations do not create additional measurements.

An independent cloud monitor performs the existing three isolated non-retained MQTT echo probes every 60 seconds and stores statistics in Cloudflare SQLite for up to 30 days. Recording continues with the apps closed and is independent of the game-cleanup toggle. Graph history measures monitor-to-broker latency; the phone's raw RTT is a separate readout. The ingress animation remains labelled PREVIEW.

The permanent page gap is 15 dp, the launch animation is centred and the dashboard appearance is finalized. Existing AI, online matches, Nearby Network, bots, friends and APK installation safeguards remain included. This packaging release changes Android version metadata from the tested build 136 to version 2.0 / build 137. It does not deploy or alter the cloud monitor.

## Source layout

- `app/`: native Android application and regression tests.
- `web/`: companion web client.
- `admin/` and `admin_service/`: admin client and authenticated service, including the separate cloud-history monitor.
- `cloudflare_worker/`: authenticated notification relay.
- `firestore.rules`: Firebase rules for existing application data.

Read `SimplifiedGameRules.md` before gameplay changes and `AGENT_HANDOFF.md` for operational constraints. The source is configured for the existing deployment; supply your own private configuration and signing inputs to build another deployment.

## Android build

Use JDK 17 and Android SDK 34. Configure `ANDROID_HOME` or a private `local.properties` file.

1. Supply your private `app/google-services.json`; a schema example is included.
2. Copy `local.release.properties.example` to `local.release.properties` and fill deployment values privately.
3. Run `./gradlew :app:testDebugUnitTest` for Android tests.
4. Supply a keystore path, alias and passwords privately, then run `./gradlew :app:lintRelease :app:assembleRelease`.

The APK is written under `app/build/outputs/apk/release/`. Without release signing inputs, a reproducible owner-signed APK cannot be produced. On smaller machines, use one Gradle worker, a 2 GiB heap and JDK 17.

Existing live-test harnesses are opt-in and require separately supplied credentials and owned fixtures. Normal release checks do not enable them or mutate production rooms. Do not enable live cleanup tests merely to package a release.

## Services

Use `npm ci` and the test scripts in each service's `package.json`. Supply actual secrets using Wrangler secrets or private environment files. Do not deploy `admin_service` without preserving the existing ADMIN and MONITOR Durable Object bindings, migrations and stored state. Its minute Cron records latency and does not invoke game cleanup.

Firebase stores existing account/friend data. The admin cloud latency history in this release is stored in Cloudflare, not Firebase. See the service README files and `APK_UPDATE_CHANNELS.md`, `OTA_CONTENT_UPDATES.md`, `MANUAL_MQTT_CLEANUP.md` and `AUTOMATIC_MQTT_CLEANUP.md` for their respective workflows.

## Validation and limitations

`SOURCE_MANIFEST.json` records source hashes and release verification. The runtime Android source matches build 136, which passed 58 focused Android regression checks including native Robolectric gesture sequences. Release-specific signing, APK identity, update compatibility and lint results are recorded separately in that manifest.

Automated tests do not establish physical-device installation, frame rate, Google consent or biometric interaction. Device-specific light-theme status-bar appearance remains a hardware acceptance limitation. No new gameplay or performance fix is claimed by the version bump.

## Source privacy and version history

Every public release has a separate immutable tag, signed APK and source archive. Previous tags and releases are preserved. Source packages exclude signing keys, deployment passwords, actual Firebase client configuration, player records, local receipts, logs, dependencies and build caches. Public endpoint identifiers and intentional in-app developer contact links remain part of the application.
