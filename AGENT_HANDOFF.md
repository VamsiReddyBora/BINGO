# Gameplay feedback and disconnect popup fix handoff

Updated 2026-10-15T22:37:21+05:30. Active source `/home/bob/workspace/bingo_gameplay_feedback_fix`, branch `fix/gameplay-feedback-dialogs`, base `9babde7`.

APK: `artifacts/gameplay-feedback-fix/Bingo-gameplay-feedback-fix.apk` (7,096,411 bytes), SHA256 `bd2b95935f43e6d0730054a8366d7c3726f0df78a4e8d0dab56f9cb606a62e72`. Signature verified with the existing certificate; version 1.4.1 / 41. Final run: 481 tests across 46 suites pass; lint 0 errors / 36 warnings; signed release built.

Fixes periodic snapshots replaying pick haptics, stale lobby compatibility downgrades, top status banners, dismissible/unbounded disconnect choices, guest pause/resume, and missing peer status during pause. Choices have a persisted 15-second deadline and default to Continue without altering the game timer. Statuses are centered local system chat and survive sync/rejoin while preserving accepted player-chat order. Host alone resumes after fresh peer/state synchronization. Recovery also explicitly synchronizes when heartbeats return without transport loss. Existing Host Disconnected indefinite hold and exact timer recovery remain unchanged.

The unexpected closure was not reproduced and has no trace. **Do not claim it is fixed.** Settings → Copy app diagnostics now captures private bounded crash frames/types and Android process-exit codes/times. No chat, packet payloads or exception messages are recorded/uploaded. Exact release R8 mapping is local in `artifacts/gameplay-feedback-fix/evidence/release-r8-mapping.zip` for retracing.

Read `artifacts/gameplay-feedback-fix/CHANGELOG.md`, `TEST_REPORT.md` and `ReviewManifest.json`. Source/test and evidence files are saved on the repair branch; generated APK and mapping ZIP stay local/ignored. Prior worktrees and APKs, including protected 1.4.1's 159 source fingerprints, were rechecked unchanged.

User will test physical devices. Do not launch more emulator/live-broker simulations without a new request. No push, deployment, real invitation or profile write was performed. For rollback use the untouched preceding `bingo_match_start_fix` checkout/APK and retain app data. Install this exact new APK on both phones for testing.
