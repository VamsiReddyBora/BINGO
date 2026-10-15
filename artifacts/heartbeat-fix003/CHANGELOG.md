# Fix003 Change Journal

## Protected Baseline & Candidate Metadata
- **Baseline Commit**: `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9` (branch `1.4.1`)
- **Baseline APK SHA-256**: `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc` (6,985,826 bytes, untouched)
- **First Candidate Checkpoint**: `fc8bdf0ef460e29dc2e8e410fe41ae81ffd606bd`
- **Fix001 Candidate Checkpoint**: `fa3b1ffdd3f517b90e595c1589c6a8290a8c69e0`
- **Fix002 Reviewed Checkpoint**: `326231022f1bdf305e715b31f9c6625c55fca61b` (branch `fix/heartbeat-fix002`)
- **Fix002 Candidate APK SHA-256**: `6c8b8616727cc9e0b709edbc4ff5878722cbe3a1bd517deebdb52e7e13500631`
- **Starting HEAD**: `326231022f1bdf305e715b31f9c6625c55fca61b`
- **Repair Worktree**: `/home/bob/workspace/bingo_heartbeat_fix003`
- **Repair Branch**: `fix/heartbeat-fix003`
- **Initial Status**: **NOT READY** (10 reproduced contract violations across policy, storage, separate-process recovery, and wire codec checks)

---

## Planned Repair Chunks

1. **Chunk 1: Recovery Synchronization Continuity & Stability (F006 / N01, N02, N12, N13)**
   - Fix `OnlineMatchHeartbeatPolicy.kt`:
     - Once state synchronization has occurred in the current recovery attempt, subsequent periodic `GAME_SYNC` / `GAME_SYNC_ACK` calls (every 2.5s) must NOT reset `recoveryStartedAt`.
     - Stability window starts strictly once at the transition to synchronized state (or on first fresh response if already synchronized).
     - Ensure recovery completes after 10s of continuous stable post-sync ACKs, properly closing the episode and incrementing to episode 2 on a subsequent outage.
   - Verify with `Fix002AdditionalReviewProbe` checks 1 and 2.

2. **Chunk 2: Account Scoping & Persistence Integrity (F005, F002 / N03, N04, N05, N06)**
   - Fix `OngoingMatchStore.kt`:
     - Disallow arbitrary scoped-key selection when `accountUid == null` in `getOngoingMatch()`: return `null`.
     - In `clearOngoingMatch()` when `accountUid == null`: do NOT delete all accounts' scoped data; only clear legacy unscoped key.
     - In `recordMatchResultTombstone()`: only commit to cache / memory AFTER `editor.commit()` succeeds. If `editor.commit()` fails, return `false` without caching the tombstone or poisoning subsequent retry checks.
   - Require authenticated account UID at all live storage callers (`AuthGateScreen`, `RootNavGraph`).
   - Verify with `Fix002AdditionalReviewProbe` checks 3, 4, 5, 6, 7.

3. **Chunk 3: Shared Match Result Recovery Across Independent Processes (F002 / N07, N08)**
   - Remove process-local `sharedMatchResults` as a fake cross-device communication channel.
   - Provide genuine cross-process/cross-device recovery mechanism via verified durable adapter and retained topic / file / backing store.
   - Verify with `Fix002SeparateProcessProbe` (winner and absent-peer in separate JVMs).

4. **Chunk 4: Wire Codec Turn IDs & Match Seed Filtering (F007, F003 / N09, N10)**
   - Fix `FastPacketCodec.kt`:
     - Ensure `turnIds` is preserved across all packets including `TURN_TIMEOUT`.
   - Fix `LobbyLifecycleEngine.kt`:
     - `isPacketForActiveMatch()`: reject packet with `packetSeed == 0L` when `currentMatchSeed != 0L`.
   - Verify with `Fix002WireReviewProbe`.

5. **Chunk 5: Rejoin Synchronization & Full State Gating (F004, F007, F001 / N14–N27)**
   - In `RootNavGraph.kt`:
     - Rejoin gating: require complete snapshot, not partial periodic `GAME_SYNC`.
     - Apply unpaused remainder milliseconds accurately; do not reset unpaused match to 30s.
     - Apply `isPaused = false` to clear old saved pause.
     - Preserve true turn IDs; reset `pickedTurnIdsHistory` on genuinely new match.
     - Monotonic deadline calculation for turn timers.

6. **Chunk 6: Verification, Probe Compliance, Full Test Suite Rerun & Handover (F008 / N28–N35)**
   - Run `run_review_probes.py` and `run_additional_probes.py`.
   - Run full unit test suite (362+ tests) with zero failures.
   - Run Android lint (0 errors) and assemble release APK.
   - Document complete resolution in `Fix003ReviewResolution.md`, `TEST_REPORT.md`, `OWNERSHIP_AND_STORAGE.md`, and `AGENT_HANDOFF.md`.
