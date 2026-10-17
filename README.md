# Bingo Multiplayer

Modern peer-to-peer and online multiplayer Bingo game for Android, built with Jetpack Compose, Material 3, and Kotlin Coroutines.

[![Release](https://img.shields.io/badge/Release-v1.5.0-blue.svg)](releases/README.md)
[![Build](https://img.shields.io/badge/Build-44-green.svg)](releases/Bingo-1.5.0.apk)
[![Android](https://img.shields.io/badge/Android-7.0%2B-brightgreen.svg)](app/build.gradle)

---

### 📦 Latest Release: [v1.5.0 (Build 44)](releases/README.md)

Download the production-signed APK directly:
- **[Bingo-1.5.0.apk](releases/Bingo-1.5.0.apk)** *(7.18 MB)*
- **[SHA-256 Checksum](releases/Bingo-1.5.0.apk.sha256)**: `05d562a9ba57465945ee7d10697257b473e189d60e5b48f5db52c0f65174d70d`
- **[Release Notes & Verification](releases/README.md)** | **[Release Manifest](releases/ReleaseManifest.json)**

Current APK includes full resolution of all 22 review findings (R01–R22) and V1 with zero regressions. [Comprehensive Review Report](MultiplayerComprehensiveReview.md).

---

### 🎮 Features

- **Online Multiplayer**: Play with friends anywhere using 6-character room codes or direct invites via low-latency MQTT.
- **Heartbeat & Disconnect Recovery**: Mid-game network drops and brief backgrounding preserve match state, remaining turn time, and board selections.
- **Local & Offline Modes**:
  - **LAN & Nearby Wi-Fi**: Play locally without internet using Wi-Fi Direct and hotspot discovery.
  - **Play vs AI**: Challenging single-player offline mode.
- **Social & Friends**: Add friends by username, check online presence, inspect win/loss streaks, and customize theme colors.
- **Hardened Notifications**: Protocol-2 push notifications for game invites and friend requests with duplicate suppression and active-game protection.
- **Customization & Controls**: Full master volume sliders, sound effects toggles, and persistent haptic silencing.

---

### ⚠️ Known Issues

- **Legacy Guest Profiles**: Older guest accounts require a Google sign-in or a new guest sign-in to receive background push notifications. Game stats and profile data remain completely preserved.
- **OEM Battery Optimizations**: Aggressive battery managers on some devices (e.g. MIUI, OneUI) may delay background notifications until the app is opened. The app automatically reconciles and syncs pending invitations upon resume.

---

### 🛠️ Building from Source

**Requirements**: JDK 17, Android SDK 34 (API 34).

```bash
# Clone the repository
git clone https://github.com/VamsiReddyBora/BINGO.git
cd BINGO

# Run unit tests
./gradlew testDebugUnitTest

# Assemble signed release APK
./gradlew assembleRelease
```

The signed APK will be generated at `app/build/outputs/apk/release/Bingo.apk` and copied to `releases/Bingo-1.5.0.apk`.

---

### 📁 Repository Structure

- [`app/`](app/) — Android application source code, Jetpack Compose UI, domain logic, and unit tests.
- [`releases/`](releases/) — Production-signed release APKs, checksums, and ProGuard mappings.
- [`cloudflare_worker/`](cloudflare_worker/) — Protocol-2 authenticated push notification relay.
- [`docs/`](docs/) — Documentation, architecture notes, and test records.
