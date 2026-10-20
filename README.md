# Bingo Multiplayer

An Android Bingo game built with Jetpack Compose to play with friends online or over local Wi-Fi.

### Download

- **[Bingo-1.0.apk](releases/Bingo-1.0.apk)** (~7.0 MB)
- [SHA-256 Checksum](releases/Bingo-1.0.apk.sha256): `2645eb3094be953096567e39ed5df0fe6bd927c5df62e74ec298367295a592c6`

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

### Build 46 networking update

Room updates use conditional revision and match-seed commits; gameplay remains on MQTT. Manual-board recovery and obsolete room closure handling are corrected. Install the updated APK on every participant device.

- [Round 11 review and validation](releases/Round11Review.md)
- [Deferred work and device checks](releases/DeferredNetworkingIssues.md)
- [Agent handoff](AGENT_HANDOFF.md)
