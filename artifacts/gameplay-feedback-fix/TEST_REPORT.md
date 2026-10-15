# Gameplay feedback repair validation

This is an automated source/build validation report, not a physical-device simulation report.

| User report | Change / automated evidence | Physical status |
|---|---|---|
| Vibration every 2.5 seconds | Pick identity excludes sampling timestamp. 100 duplicate snapshot samples remain silent; restored and catch-up picks do not replay feedback. | Needs phones to verify motor behavior. |
| Same APK sometimes called outdated | Current nonce-confirmed capabilities survive delayed lobby advertisements. Missing proof is described as waiting for connection; normal game synchronization no longer claims an old app. Correlated request/reply and retry tests pass. | Ready/Start with both phones needs retest. |
| Disconnect status/banner and dismissible choices | Centered named system chat, fixed 15-second persisted choice, strict modal properties. Rejoin and stale checkpoint writes cannot reopen/reset the same choice. Continue does not send pause/resume or change the turn. | Verify outside tap, Back and swipe-back; wait 15 seconds without choosing. |
| Guest can resume; pause popup lacks peer status | UI, client and authoritative reducer restrict pause/resume to original host. Test freezes at 14,250 ms, rejects guest actions and host resume while peer is absent, then resumes at 14,250 ms after recovery. Heartbeat-only recovery also triggers a fresh state handshake while preserving manual pause. | Host watches peer status and resumes after guest returns. Guest has no Resume control. |
| Host-disconnected hold works | Existing host dialog text/properties unchanged; existing recovery protocol tests retained. | Confirm guest remains blocked while host is away, then resumes the same state. |
| Unexpected closure during possible opponent turn | No crash trace/device available. Added private crash capture and user-invoked diagnostic copying, plus a defensive checkpoint lookup. | **Unresolved cause.** If repeated, reopen app and use Settings → Copy app diagnostics. |
| Synchronization text shown at top | Removed status banners; synchronization notices join centered system chat and survive subsequent authoritative checkpoints. | Verify chat placement and board/chat preservation on rejoin. |

Completed 2026-10-15T22:37:21+05:30.

- Final full run: **481 tests, 46 suites, 0 failures/errors/skips**.
- `testDebugUnitTest lintDebug assembleRelease`: passed. Lint: 0 errors, 36 warnings (same count as preceding build).
- Signed release verified with the existing signing certificate. Package `com.bingo.multiplayer`, version `1.4.1` / `41`.
- APK: `Bingo-gameplay-feedback-fix.apk`, 7,096,411 bytes; SHA256 `bd2b95935f43e6d0730054a8366d7c3726f0df78a4e8d0dab56f9cb606a62e72`.
- Protected baseline: 159 source fingerprints match; previous APK hashes unchanged.
- Exact release mapping is retained locally in `evidence/release-r8-mapping.zip` for retracing copied crash diagnostics. Generated ZIP and APK are not committed.
- XML unit results, lint XML, build log, source hashes and APK metadata are archived in `evidence/` and `ReviewManifest.json`.

Android UI gestures, vibration hardware, OS process killing and physical network behavior require the user's device checks. No guarantee of every edge case is claimed.
