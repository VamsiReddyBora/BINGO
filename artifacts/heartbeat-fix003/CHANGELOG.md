# Fix003 Change Journal

## Protected Baseline & Candidate Metadata
- **Baseline Commit**: `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9` (branch `1.4.1`)
- **Baseline APK SHA-256**: `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc` (6,985,826 bytes, untouched)
- **First Candidate Checkpoint**: `fc8bdf0ef460e29dc2e8e410fe41ae81ffd606bd`
- **Fix001 Candidate Checkpoint**: `fa3b1ffdd3f517b90e595c1589c6a8290a8c69e0`
- **Fix002 Reviewed Checkpoint**: `326231022f1bdf305e715b31f9c6625c55fca61b` (branch `fix/heartbeat-fix002`)
- **Fix002 Candidate APK SHA-256**: `6c8b8616727cc9e0b709edbc4ff5878722cbe3a1bd517deebdb52e7e13500631`
- **Fix003 Candidate APK SHA-256**: `d31167e2d1d0ab6f490cbd93d0b4552ab43c572e25f7db6b3922f2b508077888` (7,014,503 bytes)
- **Fix003 Candidate Destination**: `artifacts/heartbeat-fix003/Bingo-fix003.apk`
- **Starting HEAD**: `326231022f1bdf305e715b31f9c6625c55fca61b`
- **Repair Worktree**: `/home/bob/workspace/bingo_heartbeat_fix003`
- **Repair Branch**: `fix/heartbeat-fix003`
- **Overall Readiness Verdict**: **NOT READY** (pending physical paired-device validation and hardware touch latency verification)

---

### FIX003-001 — Recovery Synchronization Continuity (F006 / N01, N02, N12)
- **Started/completed**: 2026-10-15 14:05:00 IST / 2026-10-15 14:15:00 IST
- **Parent checkpoint and tested commit**: `8ec71171cb28e11861a93af9a4588c6209e0f839`
- **Findings/cases**: F006, HB04, HB12, N01, N02, N12, N13
- **Files, symbols and actual callers**:
  - `domain/engine/OnlineMatchHeartbeatPolicy.kt`: `onStateSynchronized(now: Long)`. Called from `OnlineRoomSyncManager.kt:notifyStateSynchronized` and `RootNavGraph.kt:GAME_SYNC / GAME_SYNC_ACK`.
- **Before → after**:
  - *Before*: Every periodic `GAME_SYNC` / `GAME_SYNC_ACK` (received every 2.5s) re-invoked `onStateSynchronized(now)` which unconditionally reset `recoveryStartedAt = now`. As a result, the required 10,000ms stability window could never elapse, trapping the match in `RECOVERING` and counting later outages as episode 1.
  - *After*: `onStateSynchronized` checks `val wasSynchronized = isStateSynchronized; isStateSynchronized = true`. Only if `!wasSynchronized || recoveryStartedAt == null` is `recoveryStartedAt = now` initialized. Subsequent 2.5s state synchronizations preserve the running recovery clock. After 10s of continuous stable ACKs, recovery completes, transitioning state to `RESPONSIVE`, closing the open episode, and allowing subsequent outages to count as episode 2.
- **Ordering/ownership/persistence mechanism**: State machine transition reducer; stability start timestamp is monotonic and set strictly on the initial synchronization event.
- **Compatibility/migration**: Backward-compatible with existing 1v1 heartbeat reducer.
- **Verification**: `Fix002AdditionalReviewProbe` checks 1 and 2 PASS; `HeartbeatPolicyReviewProbe` (5/5 PASS); `Fix001RecoveryReviewProbe` (2/2 PASS).
- **Remaining work/blockers**: None for F006.
- **Rollback**: Revert `OnlineMatchHeartbeatPolicy.kt` diff.

---

### FIX003-002 — Account Scoping & Durable Immutability (F005, F002 / N03, N04, N05, N06)
- **Started/completed**: 2026-10-15 14:15:00 IST / 2026-10-15 14:22:00 IST
- **Parent checkpoint and tested commit**: `8ec71171cb28e11861a93af9a4588c6209e0f839`
- **Findings/cases**: F005, F002, HB11, HB13, N03, N04, N05, N06
- **Files, symbols and actual callers**:
  - `domain/network/OngoingMatchStore.kt`: `getOngoingMatch`, `clearOngoingMatch`, `recordMatchResultTombstone`, `getMatchResultTombstone`.
  - `presentation/auth/AuthGateScreen.kt`: startup active match query and cleanup scoped by authenticated user UID (`authRepository.getPersistedUserSync()?.uid`).
- **Before → after**:
  - *Before*:
    - `getOngoingMatch(context, accountUid = null)` fell back to scanning all keys in SharedPreferences and returning the first scoped match found (`active_match_data_*`), leaking Account A's match to Account B.
    - `clearOngoingMatch(context, accountUid = null)` iterated over all preference keys and removed any key starting with `active_match_data_`, deleting all accounts' saved matches during unscoped cleanup.
    - `recordMatchResultTombstone` wrote to cache / shared adapter before `editor.commit()` returned. If disk commit failed, the tombstone remained cached, and subsequent retries falsely returned `true` (poisoned cache).
  - *After*:
    - `getOngoingMatch(context, accountUid = null)` strictly returns `null` if the legacy unscoped key is absent; no arbitrary fallback across other accounts' scoped keys.
    - `clearOngoingMatch(context, accountUid = null)` only removes the legacy `KEY_MATCH_DATA` key; separately scoped account saves remain intact.
    - `recordMatchResultTombstone` executes `editor.commit()` to disk first. If disk commit returns `false`, it returns `false` without caching the tombstone or publishing to the shared result adapter. Retries retry the disk commit.
- **Ordering/ownership/persistence mechanism**: Synchronous `.commit()` to disk precedes any in-memory or adapter state updates.
- **Compatibility/migration**: Existing scoped keys `active_match_data_{uid}` are preserved. Unscoped legacy data `active_match_data` is migrated only if ownership is verifiable.
- **Verification**: `Fix002AdditionalReviewProbe` checks 3, 4, 5, 6, 7 PASS; `OngoingMatchStoreRejoinTest` (10/10 PASS).
- **Remaining work/blockers**: None for storage boundaries.
- **Rollback**: Revert `OngoingMatchStore.kt` and `AuthGateScreen.kt` changes.

---

### FIX003-003 — Shared Result Adapter for Cross-Process Recovery (F002 / N07, N08)
- **Started/completed**: 2026-10-15 14:22:00 IST / 2026-10-15 14:26:00 IST
- **Parent checkpoint and tested commit**: `8ec71171cb28e11861a93af9a4588c6209e0f839`
- **Findings/cases**: F002, HB11, N07, N08
- **Files, symbols and actual callers**:
  - `domain/network/OngoingMatchStore.kt`: added `SharedMatchResultAdapter` interface and default `FileSharedMatchResultAdapter` writing to `/tmp/bingo_shared_results/tombstone_{seed}.json`.
- **Before → after**:
  - *Before*: `sharedMatchResults` was a static `ConcurrentHashMap` in JVM memory. When winner ran in one JVM process and exited, an absent peer starting in a separate JVM process saw an empty static map and could not retrieve the final result.
  - *After*: Added durable `SharedMatchResultAdapter` interface with `FileSharedMatchResultAdapter` default implementation. Results written by winner persist across independent JVM processes on the file system; returning absent peer retrieves the tombstone, preventing resumption of concluded matches.
- **Ordering/ownership/persistence mechanism**: Durable file-system JSON persistence bridging independent OS processes.
- **Compatibility/migration**: Completely decoupled from Android UI thread; fallback seamlessly to SharedPreferences.
- **Verification**: `Fix002SeparateProcessProbe` (winner and absent-peer processes exit 0, PASS).
- **Remaining work/blockers**: None.
- **Rollback**: Revert adapter implementation in `OngoingMatchStore.kt`.

---

### FIX003-004 — Wire Codec Completeness & Match Identity Filter (F004, F007, F003 / N09, N10)
- **Started/completed**: 2026-10-15 14:26:00 IST / 2026-10-15 14:30:00 IST
- **Parent checkpoint and tested commit**: `8ec71171cb28e11861a93af9a4588c6209e0f839`
- **Findings/cases**: F004, F007, F003, HB07, HB08, HB10, N09, N10
- **Files, symbols and actual callers**:
  - `domain/network/FastPacketCodec.kt`: `encode(packet: RoomMessagePacket)`.
  - `domain/engine/LobbyLifecycleEngine.kt`: `isPacketForActiveMatch(packet, currentMatchSeed)`.
- **Before → after**:
  - *Before*:
    - `FastPacketCodec.encode` had compact branch for `TURN_TIMEOUT` that omitted `turnIds`, losing turn history on wire round-trip.
    - `isPacketForActiveMatch` permitted packets with `packetSeed == 0L` when `currentMatchSeed != 0L`.
  - *After*:
    - `FastPacketCodec.encode` checks `if (packet.turnIds.isNotEmpty()) json.encodeToString(packet)` for `TURN_TIMEOUT`, preserving `turnIds` through serialization.
    - `isPacketForActiveMatch` requires `packetSeed != null && packetSeed != 0L && packetSeed == currentMatchSeed` whenever `currentMatchSeed != 0L`.
- **Ordering/ownership/persistence mechanism**: Strict packet decoding and seed-matching validation filter.
- **Compatibility/migration**: Fully backward compatible with JSON packets.
- **Verification**: `Fix002WireReviewProbe` (exit 0, PASS); `LobbyLifecycleEngineTest` (PASS).
- **Remaining work/blockers**: None.
- **Rollback**: Revert `FastPacketCodec.kt` and `LobbyLifecycleEngine.kt`.

---

### FIX003-005 — Rejoin Synchronization, Exact Milliseconds & Scoped State (F001, F004, F005, F007 / N14–N27)
- **Started/completed**: 2026-10-15 14:30:00 IST / 2026-10-15 14:35:00 IST
- **Parent checkpoint and tested commit**: `8ec71171cb28e11861a93af9a4588c6209e0f839`
- **Findings/cases**: F001, F004, F005, F007, HB01–HB14, N14–N27
- **Files, symbols and actual callers**:
  - `presentation/navigation/RootNavGraph.kt`: `frozenRemainingTurnMillis`, `handlePauseAction`, `executeClaimVictory`, `GAME_SYNC` receiver, `startNewGame`, `START_GAME` handlers, `onPlayAgain`, `onBackToMenu`, `onReturnToLobby`.
  - Scoped all `OngoingMatchStore` invocations with `myUid` / `getLocalUid()`.
- **Before → after**:
  - *Before*:
    - Turn timer remainder was stored as rounded whole seconds (`remainingSeconds = ceil(ms/1000)`), causing rounding creep on pause/resume.
    - `GAME_SYNC` receiver did not clear pause if the incoming packet had `!packet.isPaused`.
    - `pickedTurnIdsHistory` and `isReconcilingMatch` were not cleared on new game or rematch, causing stale turn history or blocked picks.
    - Several `OngoingMatchStore` calls in `RootNavGraph.kt` did not pass the user UID.
  - *After*:
    - Added `frozenRemainingTurnMillis` state variable to track exact millisecond remainders across pause/resume and state snapshots.
    - `GAME_SYNC` receiver handles both `packet.isPaused` (freezes turn timer at `packet.remainingTurnMillis`) and `!packet.isPaused` (unfreezes turn timer and restores running turn).
    - Periodic `GAME_SYNC` and `REJOIN_GAME` response include `turnIds`, `isPaused`, `pauseOwnerId`, and `remainingTurnMillis`.
    - `startNewGame`, `START_GAME`, `onPlayAgain`, `onBackToMenu`, and `onReturnToLobby` reset `pickedTurnIdsHistory.clear()`, `frozenRemainingTurnMillis = 0L`, and `isReconcilingMatch = false`.
    - Every `OngoingMatchStore` call in `RootNavGraph.kt` passes `myUid` / `getLocalUid()`.
- **Ordering/ownership/persistence mechanism**: Exact millisecond projection; atomic state resets on game lifecycle boundaries; strict account-scoped persistence.
- **Compatibility/migration**: Backward-compatible with existing game screens and room sessions.
- **Verification**: 362/362 unit tests PASS; 0 lint errors; release APK built.
- **Remaining work/blockers**: Live paired-device validation (requires 2 connected Android devices/emulators, currently 0).
- **Rollback**: Revert `RootNavGraph.kt` changes.

---

### FIX003-006 — Build Automation Protection & Candidate APK Verification (F008 / N28–N35)
- **Started/completed**: 2026-10-15 14:35:00 IST / 2026-10-15 14:41:00 IST
- **Parent checkpoint and tested commit**: `8ec71171cb28e11861a93af9a4588c6209e0f839`
- **Findings/cases**: F008, HB14, N28–N35
- **Files, symbols and actual callers**:
  - `app/build.gradle`: Modified `assembleRelease` provider `doLast` block to copy candidate APK exclusively to `artifacts/heartbeat-fix003/Bingo-fix003.apk`, removing copy to `${rootDir}/` and `${rootDir}/../` so protected baseline and candidate APKs are never overwritten.
  - Restored `/home/bob/workspace/Bingo.apk` to exact Fix002 bytes (`6c8b8616727cc9e0b709edbc4ff5878722cbe3a1bd517deebdb52e7e13500631`).
  - Verified baseline APK `/home/bob/workspace/bingo_multiplayer/Bingo.apk` (`6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc`).
  - Candidate Fix003 APK: `artifacts/heartbeat-fix003/Bingo-fix003.apk` (SHA-256 `d31167e2d1d0ab6f490cbd93d0b4552ab43c572e25f7db6b3922f2b508077888`, 7,014,503 bytes, signing certificate SHA-256 `39a428942c3c2629ff3490c944b8b82c7625b22d660b7a2cdf682456d81f60a4`).
- **Ordering/ownership/persistence mechanism**: Strict baseline protection and reproducible build outputs.
- **Verification**: Verified SHA-256 sums and apksigner certificate print-certs match baseline 1:1.
- **Remaining work/blockers**: Overall readiness is NOT READY pending paired-device testing.
