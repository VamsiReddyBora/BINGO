# 🎲 BINGO — Real-Time Multiplayer

<div align="center">

### *The Ultimate Real-Time, Cross-Platform Bingo Experience*

[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/VamsiReddyBora/BINGO/releases/tag/v1.0)
[![Play Online](https://img.shields.io/badge/Web%20App-Play%20Live-007ACC?style=for-the-badge&logo=googlechrome&logoColor=white)](https://vamsireddybora.github.io/BINGO/)
[![Latest Release](https://img.shields.io/badge/Release-v1.0-FF4081?style=for-the-badge&logo=github&logoColor=white)](https://github.com/VamsiReddyBora/BINGO/releases/tag/v1.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-F59E0B?style=for-the-badge)](https://opensource.org/licenses/MIT)

<p align="center">
  <b>🌐 Global Online Rooms</b> &nbsp;•&nbsp; 
  <b>📡 Offline Hotspot / LAN P2P</b> &nbsp;•&nbsp; 
  <b>👥 2 to 8 Players</b> &nbsp;•&nbsp; 
  <b>🖤 Pure AMOLED Dark Mode</b> &nbsp;•&nbsp; 
  <b>💬 Live Chat & Reactions</b>
</p>

---

### 🚀 [**Play Instantly in Browser**](https://vamsireddybora.github.io/BINGO/) &nbsp;&nbsp;|&nbsp;&nbsp; 📥 [**Download Android APK (v1.0)**](https://github.com/VamsiReddyBora/BINGO/releases/download/v1.0/Bingo.apk)

---

</div>

## 📥 Download & Play

| Platform | Channel | Requirements | Action |
| :--- | :--- | :--- | :--- |
| 📱 **Android Native App** | **Official Release v1.0** | Android 7.0+ (API 24+) | [⬇️ **Download Bingo.apk**](https://github.com/VamsiReddyBora/BINGO/releases/download/v1.0/Bingo.apk) |
| 🌐 **Web App (Cross-Play)** | **GitHub Pages** | Any Modern Web Browser | [🎮 **Play Online Now**](https://vamsireddybora.github.io/BINGO/) |
| 📦 **GitHub Releases** | **Latest v1.0 Assets** | Checksums & Full Changelog | [🏷️ **View Release Page**](https://github.com/VamsiReddyBora/BINGO/releases/tag/v1.0) |

> [!TIP]
> **Instant Gameplay:** No registration or account setup is required to play! Jump right into matches using instant guest profiles or customize your nickname and avatar anytime.

---

## 🌟 Core Highlights & Features

<table>
<tr>
<td width="50%" valign="top">

### 🌐 Real-Time Online Multiplayer
* **6-Digit Room Codes**: Instant matchmaking with automatic clipboard detection.
* **2 to 8 Players**: Seamless group lobbies with synchronized rematches.
* **Round-Robin Turns**: Smooth circular spotlight turn indicator with 30s timers.
* **High-Speed Dual Sync**: Line-rate MQTT messaging backed by cloud persistence.

</td>
<td width="50%" valign="top">

### 📡 Offline Nearby Network (LAN / Hotspot)
* **Zero Internet Required**: Play completely offline over Wi-Fi or Mobile Hotspot.
* **Ultra-Low Latency**: High-speed direct P2P socket communication.
* **1-Tap Host Discovery**: Auto-detect open lobbies on your local network.
* **Travel & Camp Ready**: Enjoy multiplayer fun anywhere without mobile data.

</td>
</tr>

<tr>
<td width="50%" valign="top">

### 🎨 Visual Themes & Custom Cell Styling
* **True AMOLED Dark Mode**: 100% pitch-black OLED background (`#000000`) for maximum battery efficiency.
* **Clean Light Theme**: Vibrant, modern, high-contrast daytime look.
* **Custom Cell Palettes**: Set custom colors for My Pick, Opponent Pick, Recent Pick, and Line Completions.
* **Radial Starburst Celebrations**: Hardware-accelerated victory particles!

</td>
<td width="50%" valign="top">

### 📐 Dynamic Board Engine & Custom Designer
* **Adaptive Grid Sizing**: Automatically adjusts from **5×5** (2P) to **6×6**, **7×7**, and **8×8** (8P).
* **Dynamic B-I-N-G-O**: Letter tracking scales with board size (`B-I-N-G-O-O-O...`).
* **Manual Board Builder**: Hand-craft your own number placement with synchronized countdown.
* **Precise Win/Draw Rules**: Supports simultaneous multi-player wins & draws.

</td>
</tr>

<tr>
<td width="50%" valign="top">

### 👥 Social Hub, Profiles & Presence
* **Persistent Player Profiles**: Custom avatars, level progression, win streaks, and detailed match stats.
* **PUBG-Style Friends List**: Send requests, search by `@username`, and manage friends with 1 tap.
* **Live Presence Status**: Real-time activity indicators (`🟢 Online`, `🟡 In Lobby`, `🟣 Playing`, `⚪ Last Seen`).
* **Instant Cooldown Protection**: Prevents spam with intelligent 15s invite timers.

</td>
<td width="50%" valign="top">

### 💬 Interactive Chat & Emoji Reactions
* **Hold-to-Grow Emoji Reactions**: Interactive physics-based floating reaction bubbles.
* **Quick Chat Phrases**: One-tap tactical messages and friendly banter.
* **In-Game Chat Overlay**: Non-intrusive transparent messaging during live turns.
* **Smart AI Challenger**: Train offline solo against adaptive heuristic bots.

</td>
</tr>
</table>

---

## 🎯 How to Play

```
1️⃣ Host or Join a Lobby ──> Enter 6-digit room code or connect via Nearby Hotspot / Wi-Fi
2️⃣ Set Up Your Board    ──> Use auto-balanced numbers or design your lucky arrangement
3️⃣ Take Your Turns      ──> Pick a number within 30s — every pick syncs across all players
4️⃣ Complete Lines        ──> Form rows, columns, or diagonals to light up B • I • N • G • O
5️⃣ Claim the Crown 👑    ──> Complete your lines first to win (or draw on ties)!
```

---

## 🔍 Post-Match Board Review

* **Edge-to-Edge 2-Player Layout**: Balanced 50/50 board inspection for classic 1v1 showdowns with zero wasted space.
* **Multi-Player Swipe Strip**: Seamlessly swipe through every competitor's board in 3+ player games.
* **Synchronized Rematch**: One-tap "Play Again" pulls everyone back into the lobby for the next battle.

---

## 🛠️ Architecture & Tech Stack

* **UI Framework**: [Jetpack Compose](https://developer.android.com/jetpack/compose) (100% Kotlin declarative UI)
* **Architecture**: MVI / Unidirectional Data Flow with Kotlin Coroutines & StateFlow
* **Online Networking**: Eclipse Paho MQTT (QoS 0 for instant gameplay, QoS 1 for room state) + Cloud Key-Value Sync
* **Local Networking**: Pure Java ServerSocket / Socket P2P over Wi-Fi & Hotspot
* **Graphics & Design**: Material 3 Design Tokens, Custom Shaders & Hardware-Accelerated Canvas Particles

---

## 🏗️ Build from Source

```bash
# Clone the repository
git clone https://github.com/VamsiReddyBora/BINGO.git
cd BINGO

# Compile Release APK
./gradlew assembleRelease

# Output binary location:
# app/build/outputs/apk/release/Bingo.apk
```

---

<div align="center">

Crafted with ❤️ by **[Vamsi Reddy Bora](https://github.com/VamsiReddyBora)**

⭐ If you enjoy playing BINGO, don't forget to **Star** this repository!

</div>
