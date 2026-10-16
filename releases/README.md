# Bingo Multiplayer — Releases

### Latest Release: **v1.5.0 (Build 44)**

| Asset | Description | Size | Checksum (SHA-256) |
| :--- | :--- | :--- | :--- |
| **[Bingo-1.5.0.apk](Bingo-1.5.0.apk)** | Signed Release APK | 7.12 MB | `8cce8fa8f3998ad7bb0ea95e95f497e517dab3a4d8623893c53dc8b12a4892ac` |
| **[Bingo-1.5.0.apk.sha256](Bingo-1.5.0.apk.sha256)** | SHA-256 Checksum | 82 B | — |
| **[Bingo-1.5.0-mapping.zip](Bingo-1.5.0-mapping.zip)** | R8 ProGuard Mapping | 6.85 MB | — |

---

### Quick Installation

```bash
adb install -r releases/Bingo-1.5.0.apk
```

---

### Key Highlights in v1.5.0

- **1v1 Heartbeat & Disconnection Recovery**: Automatic reconnection during active 1v1 matches without losing board state. Host-disconnected banner and pause states accurately preserve remaining turn time.
- **Durable Match Continuity**: Ongoing matches are durably stored locally, allowing players to rejoin their match if the app is temporarily backgrounded.
- **Hardened Notifications (Protocol 2)**: Cryptographically authenticated push notifications via `bingo-notifications-v2`, with persistent deduplication and protection against accidental room overrides while in an active game.
- **Social & Friends Polish**: Fixed roster loading on launch, suppressed stale friend requests, and improved avatar username resolution.
- **Sound & Haptics Control**: Full volume sliders and vibration muting that persist reliably across sessions.

---

### Known Issues & Notes

- **Legacy Guest Notification Authentication**: Older guest profiles created prior to hardware P-256 key generation will not receive push notifications until signing in with Google or creating a new guest profile. Existing game data and statistics remain unaffected.
- **OEM Battery Restrictions**: Devices with aggressive background task killers (e.g., Xiaomi MIUI, Samsung OneUI) may delay background push notifications until the app is opened. The app will automatically sync all pending invites on resume.

---

### Related Links

- [Main Documentation](../README.md)
- [Release Manifest](ReleaseManifest.json)
- [GitHub Repository](https://github.com/VamsiReddyBora/BINGO)
