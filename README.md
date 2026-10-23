# Bingo 1.0 — Build 65

Android Bingo with AI, online MQTT multiplayer and Nearby Network play. This is the current local Build 65 source, with deployment credentials and machine-specific tooling separated from the public package.

## Contents

- `app/`: Android Kotlin/Compose app and regression tests.
- `gradle/`, Gradle scripts and wrapper: Android build.
- `web/`: companion web client source and tests.
- `cloudflare_worker/`: notification relay source and tests.
- `firebase.json`, `firestore.rules`: Firebase rules configuration.
- `SimplifiedGameRules.md`, `AGENT_HANDOFF.md`: active behavior and continuation notes.

## Android setup

Requirements: JDK 17, Android SDK 34 and an SDK location in `ANDROID_HOME` or your local `local.properties`.

1. Download your Firebase Android client configuration and save it locally as `app/google-services.json`. A schema example is included. Use the same Firebase/OAuth project as your deployed notification relay.
2. Copy `local.release.properties.example` to `local.release.properties`. Supply your MQTT and legacy storage configuration privately.
3. Run `./gradlew :app:assembleDebug` for a normal debug build, or `./gradlew :app:testDebugUnitTest` for tests. Optional live-network tests need a configured test environment.
4. To sign a release, provide your own keystore path, alias and passwords in that private properties file or environment, then run `./gradlew :app:assembleRelease`. Without release-signing configuration the release output is unsigned. The production signing key is not included.

Android output: `app/build/outputs/apk/`. No remote-device copying or installation runs from this public source.

## Companion web client

From `web/`, run `npm ci`. Copy `.env.example` to `.env.local` and configure the deployment values, then run `npm run dev`, `npm test` or `npm run build`.

## Notification relay

From `cloudflare_worker/`, run `npm ci` and `npm test`. Set private deployment secrets with Wrangler, including the Firebase service-account configuration and legacy storage key. `wrangler.toml` contains bindings and public deployment identifiers; `.dev.vars.example` documents local secret names. Never upload a real service account or filled private variables file.

## Build 65 behavior and verification

Nearby host backgrounding holds the match until the original host returns and a validated snapshot restores picks, boards, chat, current turn and remaining countdown. Guest absence retains the current turn budget and uses five seconds for future absent turns, with the existing one-time welcome-back grant. Temporary socket loss is distinct from explicit surrender.

The production Build 65 APK passed 1,039 unit tests, with eight optional network tests skipped and no failures/errors. Release lint had zero errors and 33 existing warnings. No physical Android/hotspot acceptance is claimed. Cold Nearby recovery after the host process is killed remains separate work.

The public source differs from the private build inputs only in deployment-configuration plumbing, machine-tool removal and public documentation. Runtime behavior changes from Build 65 are not introduced here. Restoring the matching private configuration and signing key is required to reproduce a production deployment; byte-for-byte APK reproduction is not claimed.

Existing in-app developer contact links are intentionally retained. Player databases, device addresses, account dumps, signing keys, passwords, local logs and build caches are not included.
