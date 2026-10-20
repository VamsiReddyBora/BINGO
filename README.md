# Bingo Multiplayer

An Android Bingo game built with Jetpack Compose to play with friends online or over local Wi-Fi.

### Download

- **[Bingo-1.0.apk](releases/Bingo-1.0.apk)** (~7.0 MB)
- [SHA-256 Checksum](releases/Bingo-1.0.apk.sha256): `959fada480696ac6eb1fb8e65a10f42e9e3663e84c2cad0318adb70c7444e5bd`

### Features

- **Online Multiplayer**: Play with friends using 6-character room codes or direct invites.
- **Nearby Wi-Fi / Hotspot**: Play locally without internet.
- **Single Player**: Offline game against AI bots.
- **Profiles & Streaks**: Win/loss stats and customizable themes (light and AMOLED dark).

### How to Build

Requirements: Android Studio / JDK 17, Android SDK 34.

```bash
./gradlew assembleRelease
```
The APK will be generated at `app/build/outputs/apk/release/Bingo-1.0.apk`.

### Build 48 live invitation repair

Settings displays the installed version and build number. Cold metadata timeouts are corrected, old unit-fixture broker records are backed up and cleared, and both live invitation and gameplay recovery checks pass. See [build 48 validation](releases/Build48Review.md).

### Build 47 invitation update

Home ping shows only the value. Canonical room discovery no longer depends on a duplicate MQTT metadata publication; invitation retries deliver the live hint and authenticated event together.

- [Invitation delivery fixes and validation](releases/InvitationDeliveryFix.md)

### Build 46 networking update

Room updates use conditional revision and match-seed commits; gameplay remains on MQTT. Manual-board recovery and obsolete room closure handling are corrected. Install the updated APK on every participant device.

- [Round 11 review and validation](releases/Round11Review.md)
- [Deferred work and device checks](releases/DeferredNetworkingIssues.md)
- [Agent handoff](AGENT_HANDOFF.md)
