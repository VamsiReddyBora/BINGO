# Bingo Multiplayer

An Android Bingo game built with Jetpack Compose to play with friends online or over local Wi-Fi.

### Download
- **[Bingo-1.0.apk](releases/Bingo-1.0.apk)** (~6.9 MB)
- [SHA-256 Checksum](releases/Bingo-1.0.apk.sha256): `c6c9210a1b50662be0bde70aaf5f408e2501ec2de2bef616748a3989d0f64f00`

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
The APK will be generated at `app/build/outputs/apk/release/Bingo.apk`.
