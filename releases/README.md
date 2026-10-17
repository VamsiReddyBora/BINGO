# Bingo Multiplayer — Releases

### Latest Release: **v1.5.0 (Build 44)**

Current artifact: Production Release v1.5.0 with zero regressions and complete resolution of all 22 findings (R01–R22) and V1 from the comprehensive review. 100% test pass rate across 630 unit tests.

| Asset | Description | Size | Checksum (SHA-256) |
| :--- | :--- | :--- | :--- |
| **[Bingo-1.5.0.apk](Bingo-1.5.0.apk)** | Signed Release APK | 7.18 MB | `05d562a9ba57465945ee7d10697257b473e189d60e5b48f5db52c0f65174d70d` |
| **[Bingo-1.5.0.apk.sha256](Bingo-1.5.0.apk.sha256)** | SHA-256 Checksum | 65 B | — |
| **[Bingo-1.5.0-mapping.zip](Bingo-1.5.0-mapping.zip)** | R8 ProGuard Mapping | 6.73 MB | `135e3338edb1aba74a1669f2a15c222cfecb69220c95c6b1886b66262e9f2337` |

---

### Quick Installation

```bash
adb install -r releases/Bingo-1.5.0.apk
```

---

### Key Highlights in v1.5.0

- **Comprehensive Multiplayer Resilience**: Complete resolution of all 22 findings from the comprehensive audit, including 100% test pass rate across 630 unit tests with zero regressions.
- **Group Match Continuity**: In-game player exits are handled as explicit surrenders allowing active peers to continue playing; turn clock suspends properly when host backgrounds; 60s host absence limits (10s buffer + 50s HOLD) strictly enforced with state synchronization barrier on resume.
- **1v1 Heartbeat & Disconnection Recovery**: Automatic reconnection during active 1v1 matches without losing board state. Host-disconnected banner and pause states accurately preserve remaining turn time.
- **Durable Match Continuity**: Ongoing matches are durably stored locally (24-hour match TTL), allowing players to rejoin their match if the app is temporarily backgrounded or killed by the OS.
- **Deterministic First Turn**: Pure deterministic uniform distribution for first player selection across both cloud and local fallback match setups.
- **Hardened Notifications (Protocol 2)**: Cryptographically authenticated push notifications via `bingo-notifications-v2`, with persistent deduplication and protection against accidental room overrides while in an active game.
- **Social & Friends Polish**: Fixed roster loading on launch, suppressed stale friend requests, and improved avatar username resolution.
- **Sound & Haptics Control**: Full volume sliders and vibration muting that persist reliably across sessions.

---

### Related Links

- [Main Documentation](../README.md)
- [Comprehensive Review Report](../MultiplayerComprehensiveReview.md)
- [Release Manifest](ReleaseManifest.json)
- [GitHub Repository](https://github.com/VamsiReddyBora/BINGO)
