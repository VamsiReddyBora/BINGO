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
