# Bingo Releases

### Version 1.0 — build 48


- **APK**: [Bingo-1.0.apk](Bingo-1.0.apk) (~7.0 MB)
- **SHA-256**: `959fada480696ac6eb1fb8e65a10f42e9e3663e84c2cad0318adb70c7444e5bd`

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

### Build 48 validation

777 tests passed with zero failures or skips, including both live broker cases; 64 backend tests passed. APK signature verified against the previous release. Physical-device testing remains with the user.

See [Round 11 review](Round11Review.md) and [deferred work](DeferredNetworkingIssues.md).

Build 47 fixes home ping text and invitation queue blocking. See [invitation delivery fixes and evidence](InvitationDeliveryFix.md). Settings shows Version 1.0 · Build 48. See [live investigation and validation](Build48Review.md).
