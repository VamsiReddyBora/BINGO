# BINGO — Real-Time Multiplayer Bingo Game

A modern, high-performance Android Bingo game featuring real-time online multiplayer, nearby network (LAN/Hotspot) matches, adaptive AI play, universal lobby management, and PUBG-style friend and profile synchronization.

---

## 📱 Download & Install

The latest signed release APK is bundled directly in this repository:
- **Direct Download**: [`Bingo.apk`](./Bingo.apk) (4.4 MB)
- Verified with Android Signature Scheme v2 & v3.
- Requires Android 7.0 (API level 24) or higher.

---

## 🎮 Game Modes

1. **Online Multiplayer**:
   - Universal room codes (6 characters) for instant matching.
   - Dual-channel cloud room registry (KeyValue + retained MQTT discovery).
   - Real-time turn management with turn timeouts and smooth rotation.
   - Lobby screen with interactive ready states (`⏸️`, `✅`, `⌛`, `❌`) and host-controlled inactivity extensions.

2. **Nearby Network (Hotspot / LAN)**:
   - Zero-room-code local multiplayer over Wi-Fi or mobile hotspot.
   - Automatic mDNS and local socket discovery.

3. **Single Player vs AI**:
   - Adaptive AI that matches player pace and strategy.

---

## 🌟 Key Features

- **Social & Friends (PUBG-Style)**:
  - Add friends by unique username.
  - Real-time incoming game invites and friend request notifications.
  - Online, recently active, and last-seen presence indicators.
- **Profile & Avatars**:
  - Custom profile photos with cloud backup and cross-device synchronization.
  - Player levels, XP progression, win rates, and match history records.
- **Modern Architecture**:
  - Jetpack Compose UI with custom design system and fluid animations.
  - Kotlin Coroutines & Flow for asynchronous state management.
  - Strict module isolation between Domain, Network, Repository, and Presentation layers.

---

## 🛠️ Tech Stack

- **Language**: Kotlin 1.9.22
- **UI Toolkit**: Jetpack Compose & Material 3
- **Network / Messaging**: Eclipse Paho MQTT v3 Client (HiveMQ broker), OkHttp 4
- **Serialization**: Kotlinx Serialization JSON
- **Build System**: Gradle 8.2 / Android Gradle Plugin 8.2.2
- **Minimum SDK**: Android API 24 (Nougat 7.0)
- **Target SDK**: Android API 34 (Android 14)

---

## 🏗️ Building from Source

To build debug or release APKs from source:

```bash
# Clone the repository
git clone https://github.com/VamsiReddyBora/BINGO.git
cd BINGO

# Run unit tests (all 60 tests)
./gradlew testDebugUnitTest

# Assemble Release APK
./gradlew assembleRelease
```

The resulting signed APK will be output to `app/build/outputs/apk/release/Bingo.apk`.

---

## 📄 License

Developed by Vamsi Reddy Bora. All rights reserved.
