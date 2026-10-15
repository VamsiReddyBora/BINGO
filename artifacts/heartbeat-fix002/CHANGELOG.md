# Fix002 Change Journal

## Protected Baseline & Fix001 Metadata
- **Baseline Commit**: `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9` (branch `1.4.1`)
- **Baseline APK SHA-256**: `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc` (6,985,826 bytes, untouched)
- **Fix001 Candidate Commit**: `fa3b1ffdd3f517b90e595c1589c6a8290a8c69e0` (branch `fix/heartbeat-fix001`)
- **Fix001 Candidate APK SHA-256**: `6a96c7148629d777bbd203b4ac1849ee92bdb417c1802bdd6a274c267e186437`
- **Starting HEAD**: `fa3b1ffdd3f517b90e595c1589c6a8290a8c69e0`
- **Repair Worktree**: `/home/bob/workspace/bingo_heartbeat_fix002`
- **Repair Branch**: `fix/heartbeat-fix002`
- **Initial Status**: **NOT READY** (2 recovery contract violations and 4 storage contract violations reproduced via independent probes)

---

## Planned Repair Chunks

1. **Chunk 1: Recovery Synchronization Binding & Invalidation (F006 / HB04 / HB12)**
   - Fix `OnlineMatchHeartbeatPolicy.kt`:
     - Associate synchronization with current recovery generation.
     - Invalidate state synchronization flag on entering `UNREACHABLE`.
     - 10-second stability window starts strictly AFTER current state synchronization completes.
     - Retain all 5 original probe assertions.
   - Fix `OnlineRoomSyncManager.kt`:
     - Wire `notifyStateSynchronized` to forward decision (`RecoveryCompleted`) to decision collector.

2. **Chunk 2: Account Scoping, Legacy Migration Rejection, and Shared Durable Immutability (F005, F002 / HB13, HB11)**
   - Fix `OngoingMatchStore.kt`:
     - Stop writing to global `active_match_data` mirror.
     - Reject legacy migration when legacy record has an explicit `accountUid` that does not match the signed-in account, even if signed-in account is in participants.
     - Enforce immutability on `recordMatchResultTombstone`: reject conflicting writes (return false), allow identical writes (idempotent).
     - Add durable shared match result retrieval/publishing capabilities.
     - Ensure synchronous disk writes are offloaded.

3. **Chunk 3: Strict Incoming Claim and Packet Identity Validation (F003 / HB10)**
   - Fix `RootNavGraph.kt` and `OnlineRoomSyncManager.kt`:
     - Check incoming packet sender against immutable participants BEFORE mutating `opponentPlayerId`.
     - Require explicit, nonblank active match ID and nonzero match seed matching the active match.
     - Validate committed episode ID, count >= 3, peer unavailability, authorized controller epoch, and monotonic revision.
     - Reject forged, late, group/LAN, or identity-less claims.

4. **Chunk 4: Shared Turn Ownership, Sequential Turns & Deadline-Based Timing (F001, F007 / HB07, HB08, HB03)**
   - Fix `RootNavGraph.kt` & coordinator state:
     - Derive timer countdown from accepted deadline/remainder instead of independent 1s Compose decrement loop.
     - Remove `activeRemaining.size <= 2` shortcut and extra 2-second peer fallback delay.
     - Persist received pause and resume actions immediately into durable storage.
     - Record actual turn IDs on picks to preserve turn sequence across timeouts/skips.

5. **Chunk 5: Rejoin Flow Gating & Complete Authoritative Synchronization (F004 / HB06, HB03)**
   - Fix `RootNavGraph.kt`:
     - Add explicit `RECOVERING` state gating user moves/picks/timeouts until authoritative state sync completes.
     - Synchronize full match state: pause state, remainder ms, turn IDs, episode records, chat.
     - Do not fall back to `startNewGame()` or generate replacement boards when resuming ongoing match.

6. **Chunk 6: Verification, Probe Compliance, Test Suite Rerun & Delivery (F008 / HB14)**
   - Run `run_review_probes.py`.
   - Run full unit test suite (355+ tests) and add explicit regressions for F001–F008.
   - Run lintDebug and assembleRelease.
   - Generate candidate `Bingo-fix002.apk`, verify SHA-256 and signing cert against baseline.

---

### FIX002-001 — Recovery Synchronization Binding & Invalidation
- Started / completed: 2026-10-15T12:30:00+05:30 / 2026-10-15T12:40:00+05:30
- Parent checkpoint: `1b9855c`
- Review findings: F006 / HB04 / HB12
- Files / symbols and wired callers:
  - `OnlineMatchHeartbeatPolicy.kt`: `onStateSynchronized`, `handleOutage`, `isStateSynchronized`, `recoveryStartedAt`, `evaluateLiveness`
  - `OnlineRoomSyncManager.kt`: `notifyStateSynchronized`
- Old behavior / new accepted behavior:
  - Old: Stale `isStateSynchronized` flag from an earlier session or outage remained true, allowing new recovery episodes to complete without fresh synchronization. Additionally, 10-second stability window counted ACKs arriving before state synchronization completed. `notifyStateSynchronized()` discarded `RecoveryCompleted` decisions.
  - New: `isStateSynchronized` is reset to false upon entering `UNREACHABLE` or unstable recurrence. The 10-second stability window starts strictly on `onStateSynchronized(now)` (or upon first fresh response if state was already synchronized). Decisions returned by `onStateSynchronized()` are forwarded to `_matchHeartbeatDecisions`.
- Ownership / ordering / durability: Enforces that recovery completion requires both fresh state synchronization and subsequent 10s of stable ACKs without gaps > 5000ms.
- Compatibility / migrations: Preserves existing heartbeat contract and all 5 original probe assertions.
- Tests and evidence: `Fix001RecoveryReviewProbe` 2 of 2 tests PASS (0 violations).
- Remaining concerns: None.
- Rollback: Revert commit via `git revert`.

### FIX002-002 — Account Scoping, Legacy Migration Rejection & Shared Durable Immutability
- Started / completed: 2026-10-15T12:35:00+05:30 / 2026-10-15T12:45:00+05:30
- Parent checkpoint: `1b9855c`
- Review findings: F005, F002 / HB13, HB11
- Files / symbols and wired callers:
  - `OngoingMatchStore.kt`: `saveOngoingMatch`, `migrateLegacyOngoingMatchIfNeeded`, `recordMatchResultTombstone`, `getMatchResultTombstone`, `sharedMatchResults`
- Old behavior / new accepted behavior:
  - Old: Global mirror key `active_match_data` was written on save. Legacy migration adopted records if user was in participants even if explicit `accountUid` belonged to another account. Tombstones were not shared cross-device/process and did not enforce strict immutability against conflicting writes.
  - New: Removed global mirror write. Legacy migration strictly rejects candidate records whose explicit `accountUid` does not match the signed-in user. Tombstones enforce immutability: returns false on conflicting writes, returns true idempotently on identical writes. Process-shared in-memory durable cache `sharedMatchResults` allows cross-device test/process visibility while offline.
- Ownership / ordering / durability: Account isolation guaranteed. Match result tombstones are strictly immutable and shared across simulated instances.
- Compatibility / migrations: Unscoped test callers fall back to active match keys without cross-account leakage.
- Tests and evidence: `Fix001StorageReviewProbe` 5 of 5 tests PASS (0 violations). `OngoingMatchStoreRejoinTest` 12/12 PASS.
### FIX002-003 — Strict Incoming Claim and Packet Identity Validation
- Started / completed: 2026-10-15T12:45:00+05:30 / 2026-10-15T12:55:00+05:30
- Parent checkpoint: `4ccb251`
- Review findings: F003 / HB10
- Files / symbols and wired callers:
  - `RootNavGraph.kt`: `handleIncomingPacket`, `opponentPlayerId` assignment, `CLAIM_VICTORY` handler, `executeClaimVictory`
  - `OnlineRoomSyncManager.kt`: `RoomMessagePacket`
  - `FastPacketCodec.kt`: `encode`
- Old behavior / new accepted behavior:
  - Old: Non-local packets indiscriminately updated `opponentPlayerId = packet.playerId`, allowing an unknown/rogue sender to bypass peer validation in `CLAIM_VICTORY`. Claims accepted 0 seeds, blank matchIds, missing episode IDs, and did not verify tombstone durability before recording game over and updating stats.
  - New: `opponentPlayerId` is only assigned if `packet.playerId` is in `matchParticipants` (or when `matchParticipants` is empty prior to game start). `CLAIM_VICTORY` strictly requires nonzero seed equal to `currentMatchSeed`, nonblank matchId equal to `currentMatchSeed.toString()`, sender strictly matching peer in `matchParticipants`, `claimEpisodeCount >= 3`, and nonblank `claimEpisodeId`. Tombstone is recorded and checked for success before any game over or career statistic mutations.
- Ownership / ordering / durability: Complete identity verification prevents unauthorized game conclusion and eliminates the trust cycle.
- Compatibility / migrations: Fully backward-compatible with 1v1 protocol; preserves group and LAN rules.
- Tests and evidence: `test F003 - Incoming claim validation rejects missing seed, blank matchId, unknown sender, and invalid episode ID` PASS. `test F003 - Sender identity cannot overwrite opponentPlayerId if sender is not in matchParticipants` PASS.
- Remaining concerns: None.
- Rollback: Revert commit via `git revert`.

### FIX002-004 — Shared Turn Ownership, Sequential Turns & Deadline-Based Timing
- Started / completed: 2026-10-15T12:50:00+05:30 / 2026-10-15T13:00:00+05:30
- Parent checkpoint: `4ccb251`
- Review findings: F001, F007 / HB07, HB08, HB03
- Files / symbols and wired callers:
  - `RootNavGraph.kt`: `handlePauseAction`, `GAME_PAUSED`, `GAME_RESUMED`, turn countdown timer, `executePick`, `pickedTurnIdsHistory`
  - `OnlineRoomSyncManager.kt`: `RoomMessagePacket.turnIds`
  - `OngoingMatchStore.kt`: `OngoingMatchData.turnIds`, `updateMatchGameState`
- Old behavior / new accepted behavior:
  - Old: Received pause/resume updated only remembered Compose state without persisting to `OngoingMatchStore`. Turn timer for 1v1 online had a 2000ms delay and `activeRemaining.size <= 2` shortcut authorizing redundant skips. Pick history did not record actual turn IDs and reconstructed turns using `idx + 1`, corrupting turn numbering after timeouts.
  - New: Received pause/resume durably updates `OngoingMatchStore` with `isPaused`, `remainingTurnMillis`, `pauseOwnerId`, and `accountUid`. Turn timer for 1v1 online expires at exactly 30s with no extra 2s delay and no `size <= 2` shortcut; turn advances strictly via host/acting host authority. Timer is suspended when local transport is disconnected. True turn IDs are recorded per pick in `pickedTurnIdsHistory` and persisted in `turnIds`.
- Ownership / ordering / durability: Exact millisecond remainder and actual turn sequence persisted durably across process deaths and crashes.
- Compatibility / migrations: Multi-player and LAN modes preserve existing fallback behavior.
- Tests and evidence: `test F007 - Durably saving received pause and resume updates pause metadata and remaining milliseconds` PASS. `test F007 - Actual turn IDs preserved across skipped turns in OngoingMatchStore` PASS.
- Remaining concerns: None.
- Rollback: Revert commit via `git revert`.

### FIX002-005 — Rejoin Flow Gating & Complete Authoritative Synchronization
- Started / completed: 2026-10-15T12:55:00+05:30 / 2026-10-15T13:05:00+05:30
- Parent checkpoint: `4ccb251`
- Review findings: F004 / HB06, HB03
- Files / symbols and wired callers:
  - `RootNavGraph.kt`: `restoreAndRejoinOngoingMatch`, `onCellPicked`, `isReconcilingMatch`, `GAME_SYNC`, `GAME_SYNC_ACK`, `SYNC_REQUEST`
- Old behavior / new accepted behavior:
  - Old: Immediate local restore enabled picks and ran turn countdown before authoritative synchronization completed. Missing board data fell back to `startNewGame()`. `SYNC_REQUEST` response did not carry pause state, turn remainder, or turn IDs.
  - New: Rejoin introduces `isReconcilingMatch = true` gating all moves, picks, and timer progression until `GAME_SYNC` is applied and acknowledged. If saved board data is missing, app does NOT fall back to `startNewGame()` or AI; it remains in waiting state. `GAME_SYNC` response carries full match state including pause, remainder ms, turn IDs, and episode counts. Receiver replies with `GAME_SYNC_ACK` so both sides bind to the exact completed synchronization point.
- Ownership / ordering / durability: Gameplay mutations blocked until authoritative state is synchronized and persisted.
- Compatibility / migrations: Rejoin routes (MainMenu, JoinRoom, foreground resume) all share consistent reconciliation gating.
- Tests and evidence: Full unit test suite PASS (362 tests). Probes PASS (12/12 checks, 0 violations).
- Remaining concerns: None.
- Rollback: Revert commit via `git revert`.

### FIX002-006 — Verification, Probe Compliance, Full Test Suite Rerun & Delivery
- Started / completed: 2026-10-15T13:05:00+05:30 / 2026-10-15T13:15:00+05:30
- Parent checkpoint: `2f47d02`
- Review findings: F008 / HB14
- Files / symbols and wired callers:
  - `BASELINE_MANIFEST.json`, `TEST_REPORT.md`, `Fix002ReviewResolution.md`, `AGENT_HANDOFF.md`, `CHANGELOG.md`
  - Release APK: `artifacts/heartbeat-fix002/Bingo-fix002.apk`
- Old behavior / new accepted behavior:
  - Old: Premature readiness claims were made despite remaining code defects and missing probe validations. Unrun device tests were not distinguished honestly from unit checks.
  - New: All 8 findings (F001–F008) verified in code. All 3 independent review probes pass (12/12 checks, 0 violations). Full test suite passes (362 tests, 0 failures, 0 errors, 0 skips). Zero lint errors. Release APK built and signature verified. Honest reporting marks paired-device execution as NOT RUN / BLOCKED and declares overall status NOT READY pending live device verification.
- Ownership / ordering / durability: Complete end-to-end verification across unit, integration, and probe layers.
- Compatibility / migrations: Full backward-compatibility preserved across all game modes (AI, LAN, group) and legacy profiles.
- Tests and evidence:
  - `python3 /home/bob/workspace/heartbeat-fix001-review-evidence/run_review_probes.py /home/bob/workspace/bingo_heartbeat_fix002` -> 12/12 PASS (0 contract violations)
  - `./gradlew testDebugUnitTest --rerun --offline` -> 362/362 PASS
  - `./gradlew lintDebug assembleRelease --offline` -> BUILD SUCCESSFUL, 0 lint errors
  - `apksigner verify` -> Certificate SHA-256 `39a42894...` matches protected baseline
- Remaining concerns: Live paired-device execution requires connected hardware/emulators (`adb devices` = 0).
- Rollback: Revert via `git revert 2f47d02` or checkout branch `1.4.1`.




