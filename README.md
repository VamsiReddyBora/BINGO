# 🎱 BINGO — Real-Time Multiplayer

A fast, modern Android Bingo game built with Jetpack Compose. Play online with friends via room codes, connect locally over Wi-Fi/Hotspot without internet, or challenge the adaptive AI.

---

## 📥 Download

Get the latest signed release APK directly from this repository:

- **[Download Bingo.apk (v1.0)](./Bingo.apk)**
- Or download from [**GitHub Releases**](https://github.com/VamsiReddyBora/BINGO/releases/tag/v1.0)

> Requires Android 7.0 (API 24) or higher. Verified with Android Signature Schemes v2 & v3.

---

## 🎮 Game Modes

- **🌐 Online Multiplayer**: 6-character room codes, live turn timer, turn rotation, and synchronized rematch lobby.
- **📡 Nearby Network (LAN / Hotspot)**: Zero-config local multiplayer over Wi-Fi or mobile hotspot.
- **🤖 Single Player**: Offline play against an adaptive AI.
- **💬 In-Game Chat & Emotes**: Floating emojis, quick chat, and in-game message board.
- **🏆 Custom Boards & Celebrations**: Create custom boards and celebrate victories with animated stamp and starburst effects.

---

## 🛠️ Tech Stack

- **UI**: Jetpack Compose & Material 3
- **Language**: Kotlin 1.9 & Coroutines / Flow
- **Networking**: Eclipse Paho MQTT & OkHttp
- **Serialization**: Kotlinx Serialization JSON
- **Compatibility**: Android 7.0+ (API 24–34)

---

## 🏗️ Build

```bash
# Clone
git clone https://github.com/VamsiReddyBora/BINGO.git
cd BINGO

# Build signed release APK
./gradlew assembleRelease
```

The signed release APK will be generated at `app/build/outputs/apk/release/Bingo.apk`.

---

## 📄 License

Developed by Vamsi Reddy Bora.
