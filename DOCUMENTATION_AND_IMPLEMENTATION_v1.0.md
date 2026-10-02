# BINGO Multiplayer v1.0 — Architecture & Implementation Reference

## 📌 Overview
This document contains the complete technical specification, architectural design, network protocols, game rules, and release artifacts for **BINGO Multiplayer Version 1.0**.

---

## 🏗️ Project Architecture

The application is built in **Kotlin** using **Jetpack Compose** following an MVI / Unidirectional Data Flow (UDF) pattern.

```
com.bingo.multiplayer/
├── domain/
│   ├── engine/
│   │   ├── BingoEngine.kt             # Board generation, line calculation, cell marking
│   │   ├── BingoAiPlayer.kt           # Offline single-player bot heuristic decision engine
│   │   ├── ManualBoardEngine.kt       # Interactive board designer, validation & countdown
│   │   └── LobbyLifecycleEngine.kt    # Lobby state, ready synchronization & turn ordering
│   ├── model/
│   │   ├── Board.kt, Cell.kt, Line.kt # Core gameplay data models
│   │   ├── Player.kt, UserProfile.kt  # Player identity, stats, level progression
│   │   ├── Friend.kt, FriendRequest.kt# Social hub, invitations, and request packets
│   │   └── RoomMessagePacket.kt       # Network wire protocol payload definitions
│   ├── network/
│   │   ├── FastPacketCodec.kt         # Ultra-compact binary/pipe wire codec for line-rate latency
│   │   ├── OnlineRoomSyncManager.kt   # High-speed MQTT client & packet routing
│   │   ├── OnlineRoomRegistry.kt      # Cloud Key-Value room session registry & dual sync
│   │   ├── LanP2pSessionManager.kt    # Direct socket-based local Wi-Fi / Hotspot multiplayer
│   │   ├── GameInviteManager.kt       # Cloud lobby invitation delivery with 15s cooldown
│   │   └── PresenceManager.kt         # Real-time player presence tracking & heartbeat
│   └── repository/
│       ├── AuthRepository.kt          # Persistent user profiles, guest accounts & stats
│       └── FriendsRepository.kt       # Local & cloud-synced friends list with deletion blacklist
└── presentation/
    ├── common/                        # Custom theme tokens, avatars, dialogs, particles
    ├── game/                          # GameScreen, board review strip, spotlight indicator
    ├── lobby/                         # LobbyScreen, readiness toggles, player lists, kicks
    ├── manual/                        # ManualBoardDesignScreen & countdown locks
    ├── menu/                          # MainMenuScreen, game mode selectors, join modal
    ├── navigation/                    # RootNavGraph & Screen routing
    ├── settings/                      # SettingsScreen, cell color palette picker, audio toggles
    └── social/                        # DashboardAndFriendsScreen, search player, match history
```

---

## 🌐 Networking & Protocols

### 1. Online Real-Time Multiplayer (MQTT + Cloud Registry)
* **Broker URL**: HiveMQ Public TLS Broker (`ssl://broker.hivemq.com:8883` / TCP `1883`)
* **Topic Structure**: `bingo/v3/room/{ROOM_CODE}`
* **QoS Levels**:
  * **QoS 0**: Game move packets (`PICK_NUMBER`, `PING`, `PONG`) for line-rate zero-ACK speed.
  * **QoS 1**: Room lifecycle & control packets (`JOIN`, `ROOM_STATE`, `START_GAME`, `BOARD_READY`, `PLAY_AGAIN`, `KICK_PLAYER`).
* **FastPacketCodec**: Compresses packets into pipe-delimited strings (e.g. `P|{playerId}|{number}|{seed}`) saving up to 85% bandwidth over raw JSON.
* **Dual-Channel Cloud Fallback**: Cloud Key-Value REST sync reconciles room status every 2 seconds to guarantee recovery if a mobile packet drops.

### 2. Offline Nearby Network (LAN / Mobile Hotspot)
* **Protocol**: Direct TCP ServerSocket / ClientSocket (`port 8999`).
* **Discovery**: Zero-internet local hotspot discovery without room codes.
* **Compatibility**: Works across any shared Wi-Fi router or portable Android Wi-Fi hotspot.

---

## 🎮 Core Game Mechanics & Rules

### 1. Dynamic Board Sizing
The board dimension dynamically scales with player count:
* **2 Players**: **5×5** Grid (25 numbers, 5 lines to win)
* **3 Players**: **6×6** Grid (36 numbers, 6 lines to win)
* **4 Players**: **7×7** Grid (49 numbers, 7 lines to win)
* **5 to 8 Players**: **8×8** Grid (64 numbers, 8 lines to win)

### 2. Dynamic B-I-N-G-O Spelling
Lines correspond to the letters of `B-I-N-G-O`. When grid size exceeds 5, letters dynamically append `O`s:
* 5 lines: `B` • `I` • `N` • `G` • `O`
* 6 lines: `B` • `I` • `N` • `G` • `O` • `O`
* 7 lines: `B` • `I` • `N` • `G` • `O` • `O` • `O`
* 8 lines: `B` • `I` • `N` • `G` • `O` • `O` • `O` • `O`

### 3. Multi-Player Win & Draw Calculation
* **Single Winner**: If one player completes the required lines first on a turn, they receive a solo victory (Crown 👑) and all opponents receive a loss.
* **Simultaneous Draw**: If 2 or more players complete all required lines on the exact same turn:
  * Those finishing players receive a **DRAW** (`🤝 Draw Match`).
  * Any players who did not complete their lines receive a loss.
* **All-Player Draw**: If all active players complete their lines simultaneously, all players receive a draw.

### 4. Circular Spotlight Turn Indicator
In 3+ player games, the bottom avatar bar displays a 3-icon rotating spotlight:
* **Left**: Previous turn player (small icon)
* **Center**: Current turn player (highlighted, zoomed spotlight icon)
* **Right**: Next turn player (small icon)
* Supports smooth circular wrapping (e.g., in a 5-player match: Player 5 $\to$ Player 1).

### 5. Post-Match Board Review
* **2-Player Matches**: Balanced 50/50 edge-to-edge layout with `Modifier.weight(1f)` tabs and zero trailing space.
* **3+ Player Matches**: Swipeable horizontal strip to review every competitor's completed board and line marks.

### 6. Anti-Resurrection Protections
* **Lobby Kick Blacklist**: Kicked players are stored in a `kickedPlayerIds` blacklist. In-flight heartbeats, reconnect packets, or cloud responses from kicked guests are immediately rejected and dropped.
* **Friend Deletion Blacklist**: Deleted friends are stored in a 60-second `recentlyRemoved` blacklist. Stale in-flight cloud syncs ignore deleted friends and overwrite cloud storage with the sanitized list.

---

## 🎨 Theming & Visuals

* **100% AMOLED Pitch Black**: `#000000` background with matte slate surfaces for OLED battery savings.
* **Clean Light Theme**: High-contrast, clean daytime palette.
* **Custom Cell Color Palettes**: Users can select individual colors for:
  * *My Pick*
  * *Opponent Pick*
  * *Recent Pick*
  * *Line Completion*
* **Celebration Effects**: Hardware-accelerated radial starburst fireworks with particle physics on win.

---

## 📦 Release Artifacts

* **Release Tag**: `v1.0`
* **Release Binary**: `Bingo.apk` (4.81 MiB)
* **Minimum SDK**: Android 7.0 (API 24)
* **Target SDK**: Android 14 (API 34)
* **Build System**: Gradle 8.2 / Android Gradle Plugin 8.2.2 / Kotlin 1.9.22

---

## 🛠️ Build Commands

```bash
# Clean project
./gradlew clean

# Run full unit test suite
./gradlew testDebugUnitTest

# Assemble Release APK
./gradlew assembleRelease
# Output located at: app/build/outputs/apk/release/Bingo.apk
```

---
*Created on October 2, 2026 for BINGO Multiplayer Official Release v1.0.*
