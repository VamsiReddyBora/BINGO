# Match-start fix handoff

Updated 2026-10-15T21:23:51+05:30. Worktree `/home/bob/workspace/bingo_match_start_fix`, branch `fix/lobby-version-handshake`; base `d7dccec`.

Fixes missing compatibility data in READY packets, adds a small correlated MQTT request/reply with bounded retries at Start, distinguishes missing confirmation from explicit old versions, preserves cloud START metadata, and closes owned MQTT resources after DISCONNECT completes. Friends fixes and host-disconnection policy remain included.

APK: `artifacts/match-start-fix/Bingo-match-start-fix.apk` (7080033 bytes), SHA256 `bb1d3b4f066f5c0e85634d05fa9d09a92b80f2b55362543fc2e63895467b055a`. Release build succeeded; apksigner verified the existing signing certificate. Final unit run: 466 tests, 0 failures.

User explicitly requested immediate APK delivery and will test physical devices. Do not run more simulations without a new request. Prior live run completed a 21-pick Bingo match but hung during shutdown; thread dump showed leaked MQTT ping timers. Final source fixes that cleanup; live rerun after this final change was deferred at the user's request. Physical checks remain unverified. Preserve initial evidence and do not present those shutdown-timeout runs as fully passed.

Install the exact APK on both phones and create a fresh room. Previous friends-fix, host-wait, shared and protected 1.4.1 APKs/checkouts remain unchanged. No deployment, push, real invitations or profile writes.
