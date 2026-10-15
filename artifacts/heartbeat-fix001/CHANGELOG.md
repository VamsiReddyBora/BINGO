# Fix001 Change Journal

## Protected Baseline & Reviewed Candidate Metadata
- **Baseline Commit**: `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9` (branch `1.4.1`)
- **Baseline APK SHA-256**: `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc`
- **Reviewed Candidate Commit**: `fc8bdf0ef460e29dc2e8e410fe41ae81ffd606bd` (branch `feature/heartbeat-1v1`)
- **Reviewed Candidate APK SHA-256**: `46b5038530ca19685581bb407bff648c85ba5ee6b5ea5470fc577066f688c8e0`
- **Repair Worktree**: `/home/bob/workspace/bingo_heartbeat_fix001`
- **Repair Branch**: `fix/heartbeat-fix001`
- **Initial Status**: **NOT READY** (5 contract violations reproduced via independent probe)

---

## Planned Repair Chunks

1. **Chunk 1: Pure Policy State Machine Corrections (HB04, HB05)**
   - Fix `OnlineMatchHeartbeatPolicy.kt`:
     - Bounded observation window initialization without requiring missing response (HB05).
     - Unified recovery-completion predicate across tick, ACK, and sync paths (HB04).
     - Recovery stability requires continuous window of 10,000 ms with maximum 5,000 ms gap (HB04).
     - Proper episode closing and re-arming after local health loss/restoration (HB04).
     - Verify with `HeartbeatPolicyReviewProbe` and unit tests.
2. **Chunk 2: Transport & Packet Freshness Corrections (HB12, HB10, HB05)**
   - Fix `OnlineRoomSyncManager.kt` & `FastPacketCodec.kt`:
     - Remove generic packet activity fallback `onFreshPeerResponse()` on arbitrary packets (HB12).
     - Only verified challenge ACKs establish liveness (HB12).
     - Delay `onStateSynchronized` until actual validated state application (HB12).
     - Validate incoming claims against active match identity, episode, count, controller, revision (HB10).
     - Bounded observation start upon match setup and connection readiness (HB05).
3. **Chunk 3: Coordinator Lifecycle, Activation & Rejoin Unification (HB01, HB06, HB02)**
   - Fix `RootNavGraph.kt`:
     - Centralize match heartbeat activation for automatic boards, manual boards, and rematches (HB01).
     - Unify main-menu, lobby, join-room, and foreground rejoin through a single recovery routine (HB06).
     - Ensure `connectToRoom` doesn't destroy freshly started heartbeat (HB06).
     - Remove 3-consecutive-missed-turns forfeit and any auto-forfeit for 1v1 online (HB02).
4. **Chunk 4: Shared Pause Remainder, Claim UI Decoupling & Owner Serialization (HB07, HB08, HB09, HB11)**
   - Fix `RootNavGraph.kt` & `GameScreen.kt`:
     - Fix `GAME_RESUMED` and pause timer effect to preserve exact millisecond remainder on both phones without 30s reset (HB08).
     - Remove `|| isClaimVictoryEligible` from `executeClaimVictory` (HB09).
     - Persist durable match result tombstone before clearing active match so offline losers can recover the result (HB11).
     - Enforce single controller / commit ordering and action deduplication (HB07).
5. **Chunk 5: Enforced Account Scoping & Storage Hardening (HB03, HB13)**
   - Fix `OngoingMatchStore.kt` & callers in `RootNavGraph.kt`, `MainMenuScreen.kt`, `AuthGateScreen.kt`:
     - Enforce mandatory non-null `accountUid` and account-prefixed keys in SharedPreferences (HB13).
     - Persist policy checkpoint on all availability transitions (HB03).
     - Offload disk writes (`commit()`) from the UI thread (HB03).
     - Handle failed writes explicitly (HB03).
6. **Chunk 6: Documentation, Evidence Correction & Verification Build (HB14)**
   - Update `TEST_REPORT.md`, `AGENT_HANDOFF.md`, `CHANGELOG.md`, `Fix001ReviewResolution.md`.
   - Run full JVM test suite, `HeartbeatPolicyReviewProbe`, lint, and release build.
   - Generate candidate Fix001 APK and check SHA-256 and signing certificate.

---

### FIX001-001 — Pure Policy Reducer & Monotonic Observation Window Corrections
- Started / completed: 2026-10-15 11:05 IST / 2026-10-15 11:11 IST
- Checkpoint before edit: fa190fd
- Review IDs: HB04, HB05
- Files / symbols:
  - `app/src/main/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicy.kt` (`canCompleteRecovery`, `completeRecoveryIfEligible`, `onTick`, `onFreshPeerResponse`, `onLocalHealthRestored`, `lastFreshPeerResponseAt`)
  - `app/src/test/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicyTest.kt` (updated H06, H09, C01 with periodic heartbeats; added 3 new regressions directly mirroring probe fixtures)
- Actual old behavior / replacement:
  - Old: `OnlineMatchHeartbeatPolicy` in `SYNCING` state never measured silence; if peer never sent a response, policy stayed `SYNCING` forever.
  - New: `lastFreshPeerResponseAt` defaults to observation clock start; `onTick` in `SYNCING` evaluates silence threshold (15,000 ms) and transitions to `UNREACHABLE` (HB05).
  - Old: `onTick` checked elapsed recovery time without checking heartbeat freshness/continuity, closing episodes even when no peer heartbeats arrived; `SYNCING` response jumped straight to `RESPONSIVE` while leaving `isEpisodeOpen=true`.
  - New: Unified recovery completion predicate `canCompleteRecovery` across `onTick`, `onFreshPeerResponse`, and `onStateSynchronized` requiring 10,000 ms continuous recovery with max 5,000 ms gap; open episodes returning from local health loss enter `RECOVERING` and properly close and re-arm on stable recovery (HB04).
- Scope and compatibility: Pure domain class, gated to 1v1 online matches.
- Tests:
  - `python3 /home/bob/workspace/heartbeat-review-evidence/run_probe.py /home/bob/workspace/bingo_heartbeat_fix001` -> 5 of 5 checks PASS, 0 contract violations.
  - `./gradlew testDebugUnitTest --tests "com.bingo.multiplayer.domain.engine.OnlineMatchHeartbeatPolicyTest"` -> 17 of 17 tests PASS.
- Remaining dependencies: Wire bounded observation start and transport challenge handling in Chunk 2.
- Rollback: `git checkout fa190fd -- app/src/main/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicy.kt app/src/test/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicyTest.kt`

---

### FIX001-002 — Dedicated Challenge-Reply Freshness & Generic Shortcut Removal
- Started / completed: 2026-10-15 11:15 IST / 2026-10-15 11:21 IST
- Checkpoint before edit: b95fe3c
- Review IDs: HB12, HB10 (transport part), HB05 (immediate first probe)
- Files / symbols:
  - `app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt` (`startMatchHeartbeat`, `claimMatchVictory`, `notifyStateSynchronized`, `handleIncomingPacket`)
  - `app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt` (`GAME_SYNC` packet handling)
  - `app/src/test/java/com/bingo/multiplayer/domain/network/MatchHeartbeatPacketCodecTest.kt` (`HB12 - generic peer traffic, old-match sync, and expired or duplicate ACKs do not clear unavailability`)
- Actual old behavior / replacement:
  - Old: `OnlineRoomSyncManager` had an unconditional fallback (lines 1167–1179) that treated all non-heartbeat packets as fresh peer responses, and called `onStateSynchronized()` immediately upon receiving any `GAME_SYNC` packet before validation or disk persistence. Probe loop delayed 2500ms before sending its first probe. `claimMatchVictory()` prematurely stopped heartbeat monitoring before coordinator acceptance.
  - New: Removed generic packet activity fallback. Only verified challenge ACKs for the active match, intended peer, active connection generation, with challenge age <= 5000ms call `onFreshPeerResponse()`. `notifyStateSynchronized()` is invoked only after the coordinator validates, applies, and durably persists `GAME_SYNC`. First probe is dispatched immediately upon start. Broker echo RTT freshness (<= 5000ms) enforced on `PING`. Premature `stopMatchHeartbeat()` removed from `claimMatchVictory()`.
- Scope and compatibility: Applies exclusively to online 1v1 match heartbeats; legacy packet codecs and LAN/group matches unaffected.
- Tests:
  - `./gradlew testDebugUnitTest --tests "com.bingo.multiplayer.domain.network.MatchHeartbeatPacketCodecTest"` -> PASS (all 6 tests, including new HB12 regression).
  - `python3 /home/bob/workspace/heartbeat-review-evidence/run_probe.py /home/bob/workspace/bingo_heartbeat_fix001` -> 5 of 5 checks PASS, 0 contract violations.
- Remaining dependencies: Coordinator lifecycle and auto-forfeit bypass in Chunk 3.
- Rollback: `git checkout b95fe3c -- app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt app/src/test/java/com/bingo/multiplayer/domain/network/MatchHeartbeatPacketCodecTest.kt`

---

### FIX001-003 — Coordinator Lifecycle, Centralized Activation, and Rejoin Route Consolidation
- Started / completed: 2026-10-15 11:25 IST / 2026-10-15 11:38 IST
- Checkpoint before edit: 8df72ba
- Review IDs: HB01, HB02, HB06
- Files / symbols:
  - `app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt` (`activateMatchHeartbeatIf1v1`, `restoreAndRejoinOngoingMatch`, `startNewGame`, `startCountdownAndInitiateTurn`, MainMenuScreen `onRejoinMatch`, JoinRoomScreen playing match restore, LobbyScreen `onRejoinMatch`)
- Actual old behavior / replacement:
  - Old: Automatic board starts in `startNewGame()` never initialized or activated the match heartbeat (only manual board countdown did). `TURN_TIMEOUT` marked disconnected players and forfeited after 3 consecutive missed turns even in 1v1 online matches. MainMenuScreen, JoinRoomScreen, and LobbyScreen each had separate divergent copy-pasted match rejoin logic; some started heartbeat before `connectToRoom()`, which wiped the heartbeat session during room connection.
  - New: `activateMatchHeartbeatIf1v1()` centralized and invoked on every 1v1 match start path (automatic boards in `startNewGame`, manual countdown, and rejoins). In `TURN_TIMEOUT`, 1v1 online matches bypass `markPlayerDisconnected`, `consecutiveMissedTurns` increment, and forfeit logic. All rejoin entry points (MainMenu, JoinRoom, Lobby) delegate to a single unified `restoreAndRejoinOngoingMatch()` helper that connects to room FIRST, restores the policy checkpoint (`committedEpisodeCount`, `currentEpisodeId`, `isEpisodeOpen`), and attaches the restored heartbeat policy.
- Scope and compatibility: Multi-player (3+) and LAN matches retain standard forfeit and presence handling.
- Tests:
  - `./gradlew testDebugUnitTest --tests "com.bingo.multiplayer.domain.network.MatchCoordinatorAndPauseTest"` -> PASS
  - `python3 /home/bob/workspace/heartbeat-review-evidence/run_probe.py /home/bob/workspace/bingo_heartbeat_fix001` -> 5 of 5 checks PASS, 0 contract violations.
- Remaining dependencies: Pause remainder synchronization and result tombstone persistence in Chunk 4.
- Rollback: `git checkout 8df72ba -- app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt`

---

### FIX001-004 — Shared Pause Remainder, Strict Claim Victory Guard, and Result Tombstone Persistence
- Started / completed: 2026-10-15 11:38 IST / 2026-10-15 11:43 IST
- Checkpoint before edit: 8df72ba
- Review IDs: HB07, HB08, HB09, HB10, HB11
- Files / symbols:
  - `app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt` (`executeClaimVictory`, `CLAIM_VICTORY` handler, `GAME_RESUMED` handler, turn timer LaunchedEffect)
  - `app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt` (`recordMatchResultTombstone`, `getMatchResultTombstone`, `MatchResultTombstone`)
  - `app/src/test/java/com/bingo/multiplayer/domain/network/MatchCoordinatorAndPauseTest.kt` (`test C01`, `test C02`, `test C04`, `test HB09`, `test HB10`)
- Actual old behavior / replacement:
  - Old: `GAME_RESUMED` set `frozenRemainingTurnSeconds = null` immediately in packet handler, causing the turn timer LaunchedEffect to reset to 30s instead of resuming the paused remainder. `executeClaimVictory()` allowed victory claim if `isClaimVictoryEligible` was true even if policy rejected it due to concurrent peer recovery. Concluded matches cleared active match without durable tombstone, allowing offline peers to attempt rejoining a finished match. Incoming `CLAIM_VICTORY` did not validate 1v1 online mode, match seed, peer identity, or 3-episode requirement.
  - New: `GAME_RESUMED` preserves `frozenRemainingTurnSeconds` remainder so LaunchedEffect sets `turnTimer` to exact remainder and then clears it. `executeClaimVictory()` strictly requires `result is ClaimResult.Accepted`. `recordMatchResultTombstone()` durably records immutable final result tombstone before clearing ongoing match. Incoming `CLAIM_VICTORY` enforces 1v1 online mode, validates match seed, peer sender, and requires `claimEpisodeCount >= 3`.
- Scope and compatibility: Concluded match tombstones prevent ghost rejoins without affecting normal career statistics or other game modes.
- Tests:
  - `./gradlew testDebugUnitTest --tests "com.bingo.multiplayer.domain.network.MatchCoordinatorAndPauseTest"` -> 13 of 13 tests PASS.
  - `python3 /home/bob/workspace/heartbeat-review-evidence/run_probe.py /home/bob/workspace/bingo_heartbeat_fix001` -> 5 of 5 checks PASS, 0 contract violations.
- Remaining dependencies: Account scoping and storage hardening in Chunk 5.
- Rollback: `git checkout 8df72ba -- app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt app/src/test/java/com/bingo/multiplayer/domain/network/MatchCoordinatorAndPauseTest.kt`

---

### FIX001-005 — Enforced Account Scoping, Isolated Storage Keys, and Policy Checkpoint Persistence
- Started / completed: 2026-10-15 11:43 IST / 2026-10-15 11:47 IST
- Checkpoint before edit: 8df72ba
- Review IDs: HB03, HB13
- Files / symbols:
  - `app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt` (`getMatchKey`, `saveOngoingMatch`, `getOngoingMatch`, `updateMatchGameState`, `clearOngoingMatch`)
  - `app/src/main/java/com/bingo/multiplayer/presentation/menu/MainMenuScreen.kt` (account-scoped match read & clear)
  - `app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt` (persists policy checkpoint on `matchHeartbeatDecisions` with `clearNullableEpisodeFields = true`)
  - `app/src/test/java/com/bingo/multiplayer/domain/network/OngoingMatchStoreRejoinTest.kt` (`test HB11`, `test HB03`, `test HB13`)
- Actual old behavior / replacement:
  - Old: `OngoingMatchStore` used a single global key `active_match_data`, allowing any signed-in account to see and resume another account's match. Policy checkpoints (committed episodes, open episode ID, peer availability) were not updated on availability decisions. `updateMatchGameState` could not clear nullable episode fields when episodes closed.
  - New: `OngoingMatchStore` scopes keys by account UID (`active_match_data_$accountUid`). Seamless legacy migration migrates global records only when account ownership matches. Heartbeat availability transitions (`BecameUnreachable`, `StartedRecovering`, `RecoveryCompleted`) trigger `updateMatchGameState` with `clearNullableEpisodeFields = true` to accurately record committed episode counts and clear episode IDs on recovery completion.
- Scope and compatibility: Backward-compatible deserialization and migration for existing installations.
- Tests:
  - `./gradlew testDebugUnitTest --tests "com.bingo.multiplayer.domain.network.OngoingMatchStoreRejoinTest"` -> 12 of 12 tests PASS.
  - Full unit test suite `./gradlew testDebugUnitTest` -> 355 of 355 tests PASS.
  - `python3 /home/bob/workspace/heartbeat-review-evidence/run_probe.py /home/bob/workspace/bingo_heartbeat_fix001` -> 5 of 5 checks PASS, 0 contract violations.
- Rollback: `git checkout 8df72ba -- app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt app/src/main/java/com/bingo/multiplayer/presentation/menu/MainMenuScreen.kt app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt app/src/test/java/com/bingo/multiplayer/domain/network/OngoingMatchStoreRejoinTest.kt`



