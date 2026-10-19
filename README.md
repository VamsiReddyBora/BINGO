# Bingo Multiplayer

Modern peer-to-peer and online multiplayer Bingo game for Android, built with Jetpack Compose, Material 3, and Kotlin Coroutines.

[![Release](https://img.shields.io/badge/Release-v1.0.0-blue.svg)](releases/README.md)
[![Build](https://img.shields.io/badge/Build-45-green.svg)](releases/Bingo-1.0.0.apk)
[![Android](https://img.shields.io/badge/Android-7.0%2B-brightgreen.svg)](app/build.gradle)

---

### 📦 Latest Release: [v1.0.0 (Build 45)](releases/README.md)

Download the production-signed APK directly:
- **[Bingo-1.0.0.apk](releases/Bingo-1.0.0.apk)** *(~6.90 MB)*
- **[SHA-256 Checksum](releases/Bingo-1.0.0.apk.sha256)**: `c6c9210a1b50662be0bde70aaf5f408e2501ec2de2bef616748a3989d0f64f00`
- **[Release Notes & Verification](releases/README.md)** | **[GitHub Release](https://github.com/VamsiReddyBora/BINGO/releases/tag/v1.0.0)**

---

### 🎮 Features

- **Zero-Stress Turn-Based Multiplayer**: Play with friends anywhere using 6-character room codes or direct invites with robust peer-synchronized board state.
- **Real-Time EMQX Broker Monitoring & Graceful HTTP Fallback**:
  - Live continuous broker reachability probing.
  - Automatic fallback to high-availability HTTP Key-Value cloud storage during MQTT network disruptions.
  - Main menu horizontal scrolling status alert note with dedicated 1-tap WhatsApp developer reporting.
- **Local & Offline Modes**:
  - **Nearby Wi-Fi / Hotspot**: Play locally without internet using Wi-Fi Direct and hotspot discovery.
  - **Play vs AI**: Challenging single-player offline mode.
- **Social & Friends**: Add friends by username, check online presence, inspect win/loss streaks, and customize theme colors.
- **AMOLED Dark Mode & Customization**: Crisp AMOLED black theme alongside clean light mode, master volume sliders, sound effects toggles, and persistent haptic muting.

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

The signed APK will be generated at `app/build/outputs/apk/release/Bingo.apk` and copied to `releases/Bingo-1.0.0.apk`.

---

### 📁 Repository Structure

- [`app/`](app/) — Android application source code, Jetpack Compose UI, domain logic, and unit tests.
- [`releases/`](releases/) — Production-signed release APKs, checksums, and release notes.
- [`cloudflare_worker/`](cloudflare_worker/) — Protocol-2 authenticated push notification relay.
- [`docs/`](docs/) — Documentation, architecture notes, and test records.
