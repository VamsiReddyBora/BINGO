# Bingo Multiplayer — Official Releases

### Official Release: **v1.0.0 (Build 45)** 🎉

Current artifact: **Production Release v1.0.0** — Official Public Launch of Bingo Multiplayer!
Featuring a streamlined zero-stress multiplayer architecture, real-time EMQX broker monitoring with graceful HTTP fallback, live horizontal status alerts, dedicated WhatsApp developer reporting, and full AMOLED dark mode support. Verified with 100% passing automated test suite across 722 tests.

| Asset | Description | Size | Checksum (SHA-256) |
| :--- | :--- | :--- | :--- |
| **[Bingo-1.0.0.apk](Bingo-1.0.0.apk)** | Official Signed Release APK | ~6.90 MB (7,232,234 B) | `c6c9210a1b50662be0bde70aaf5f408e2501ec2de2bef616748a3989d0f64f00` |
| **[Bingo.apk](Bingo.apk)** | Standard Universal APK Symlink | ~6.90 MB (7,232,234 B) | `c6c9210a1b50662be0bde70aaf5f408e2501ec2de2bef616748a3989d0f64f00` |
| **[Bingo-1.0.0.apk.sha256](Bingo-1.0.0.apk.sha256)** | SHA-256 Checksum File | 83 B | — |

---

### Quick Installation

```bash
adb install -r releases/Bingo-1.0.0.apk
```

---

### Key Highlights in v1.0.0

- **Simplified Zero-Stress Multiplayer Engine**:
  - Turn-based 5x5 Bingo engine with real-time peer-to-peer and cloud-synchronized state.
  - Transparent turn countdown timers (30-second move budget + 5-second skip on away status).
  - Versioned peer presence resolution guaranteeing that online successors receive their full move budget.
- **Real-Time EMQX Broker Monitoring & Graceful HTTP Fallback**:
  - Live continuous broker health monitoring distinguishing primary cluster from backup nodes.
  - Automatic fallback to high-availability HTTP Key-Value cloud storage during MQTT network disruptions.
  - Non-intrusive horizontal scrolling alert note on the main menu informing players of server status.
  - Dedicated one-tap WhatsApp developer alert button on the Developer Note screen with auto-framed error reports.
- **Nearby & Offline Multiplayer**:
  - Peer-to-peer discovery over local Wi-Fi and mobile hotspots without needing internet connectivity.
  - Challenging single-player offline mode against AI bots.
- **Social & Community Features**:
  - Player profiles, customizable avatars, and global stats tracking (games played, win streaks, XP).
  - Direct friend requests, real-time presence indicators, and private game invites.
- **AMOLED Dark Mode & Tactile Feedback**:
  - High-contrast, battery-friendly AMOLED dark theme alongside crisp light theme.
  - Authentic haptic feedback and original Bingo sound effects with dedicated toggle controls.

---

### Related Links

- [Main Documentation](../README.md)
- [Official GitHub Repository](https://github.com/VamsiReddyBora/BINGO)
- [GitHub Releases Page](https://github.com/VamsiReddyBora/BINGO/releases)
