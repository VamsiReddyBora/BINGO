# 🎱 BINGO — Real-Time Multiplayer

A fast, modern Android Bingo game built with Jetpack Compose. Play online with friends via room codes, connect locally over Wi-Fi/Hotspot without internet, or challenge the adaptive AI.

---

## 📥 Download

Get the latest signed release APK directly from this repository:

- **[Download Bingo.apk (Latest v1.0.1)](./Bingo.apk)**
- Or download from [**GitHub Releases**](https://github.com/VamsiReddyBora/BINGO/releases/tag/v1.0.1)

> Requires Android 7.0 (API 24) or higher. Verified with Android Signature Schemes v2 & v3.

---

## 🌐 Web Application (Cross-Platform Play)

Play directly in your browser on PC, Mac, Linux, or iPhone with **full real-time cross-play against Android app players**:

```bash
# Run web version locally
cd web
npm install
npm run dev
```

- **Shared MQTT Broker**: Connects to the same EMQX edge broker via secure WebSockets (`wss://broker.emqx.io:8084/mqtt`).
- **Identical Features**: 5x5 board, B-I-N-G-O letters banner, hold-to-grow big reaction emojis, quick-chat drawer, turn timers, and 360-degree radial starburst celebration!
- **Zero Friction**: Friends can join a match on PC or mobile browser via 6-digit room code or direct link (`?room=123456`).

---

## 🎮 Game Modes

- **🌐 Online Multiplayer**: 6-character room codes, live turn timer, turn rotation, and synchronized rematch lobby.
- **📡 Nearby Network (LAN / Hotspot)**: Zero-config local multiplayer over Wi-Fi or mobile hotspot (Android).
- **🤖 Single Player**: Offline play against an adaptive AI.
- **💬 In-Game Chat & Emotes**: Floating emojis, hold-to-grow big emojis, quick chat, and in-game message board.
- **🏆 Custom Boards & Celebrations**: 360-degree radial starburst celebration on win.

---

## 🛠️ Tech Stack

- **Android Mobile App**:
  - **UI**: Jetpack Compose & Material 3
  - **Language**: Kotlin 1.9 & Coroutines / Flow
  - **Networking**: Eclipse Paho MQTT (TCP port 1883) & OkHttp
  - **Serialization**: Kotlinx Serialization Micro-Codec & JSON
- **Web Application**:
  - **UI**: React 19, TypeScript, Tailwind CSS v4 & Lucide Icons
  - **Bundler**: Vite
  - **Networking**: MQTT.js over Secure WebSockets (TLS port 8084)
  - **Audio**: Procedural Web Audio API sound synthesizer
  - **Celebration**: Canvas-accelerated 360-degree radial starburst & Confetti

---

## 🏗️ Build

### Android APK
```bash
./gradlew assembleRelease
```
The signed release APK will be generated at `app/build/outputs/apk/release/Bingo.apk`.

### Web Application
```bash
cd web
npm run build
```
The production web assets will be generated in `web/dist/`.

---

## 📄 License

Developed by Vamsi Reddy Bora.

