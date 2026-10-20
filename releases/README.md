# Bingo Releases

### Version 1.0 — build 46


- **APK**: [Bingo-1.0.apk](Bingo-1.0.apk) (~7.0 MB)
- **SHA-256**: `2645eb3094be953096567e39ed5df0fe6bd927c5df62e74ec298367295a592c6`

### Quick Install
```bash
adb install -r releases/Bingo-1.0.apk
```

### Changes in 1.0
- Turn-based multiplayer with synchronized board states.
- 30-second turn countdown with auto-skip when player is away.
- Reconnection support for active matches.
- Server connection monitor with automatic HTTP fallback.
- Light and AMOLED dark theme support.

### Build 46 validation

764 unit tests passed, one existing opt-in broker test skipped; 61 backend tests passed. APK signature verified against the previous release. Physical-device testing remains with the user.

See [Round 11 review](Round11Review.md) and [deferred work](DeferredNetworkingIssues.md).
