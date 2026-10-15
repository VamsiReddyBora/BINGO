# Bingo 1v1 Heartbeat & Recovery Change Journal

## Protected Baseline Metadata
- **Repository**: `https://github.com/VamsiReddyBora/BINGO.git`
- **Protected Branch**: `1.4.1`
- **Feature Branch**: `feature/heartbeat-1v1`
- **Feature Worktree**: `/home/bob/workspace/bingo_heartbeat_work`
- **Baseline Commit**: `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9`
- **Baseline Signed APK SHA-256**: `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc`
- **Baseline Verification**: Verified clean execution of `./gradlew testDebugUnitTest --offline --console=plain --max-workers=2` (all 304 unit tests passed, 0 failures, 0 errors).

---

## Proposed Chunks & Inventory of Target Files
1. **Chunk 1: Pure Policy Reducer & Monotonic Clock Abstraction**
   - New file: `app/src/main/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicy.kt`
   - Test suite: `app/src/test/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicyTest.kt`
   - Purpose: Implements the exact state machine (`SYNCING`, `RESPONSIVE`, `UNREACHABLE`, `RECOVERING`, `LOCAL_UNAVAILABLE`), monotonic clock abstraction (`Clock`), 15,000 ms silence threshold, 10,000 ms recovery window, max 5,000 ms gap restart, incident episode tracking, and claim eligibility.
2. **Chunk 2: Dedicated Transport, Match Heartbeat Codec, & Decoupled Scheduler**
   - Target files:
     - `app/src/main/java/com/bingo/multiplayer/domain/network/RoomMessagePacket.kt`
     - `app/src/main/java/com/bingo/multiplayer/domain/network/FastPacketCodec.kt`
     - `app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt`
   - Test suites:
     - `app/src/test/java/com/bingo/multiplayer/domain/network/MatchHeartbeatPacketCodecTest.kt`
     - Additional tests in `app/src/test/java/com/bingo/multiplayer/domain/network/TurnRotationAndCodecTest.kt`
   - Purpose: Adds dedicated `MATCH_HEARTBEAT` packet type with `PROBE`/`ACK` challenges, connection generation, intended peer UID, sequence, and versioning. Decouples the 2.5s match probe job from cloud reconciliation in `OnlineRoomSyncManager.kt`.
3. **Chunk 3: Coordinator, Pause/Resume, Dialog State, & Turn Rules**
   - Target files:
     - `app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt`
     - `app/src/main/java/com/bingo/multiplayer/presentation/game/GameScreen.kt`
   - Test suites:
     - Targeted tests for turn preservation, pause/resume remainder freeze, single timeout decision, claim victory confirmation.
   - Purpose: Coordinates turn timing, keeps both 1v1 players in turn order, removes 3-missed-turn forfeit for 1v1, implements pause with frozen milliseconds, and binds "Opponent not responding" popup actions.
4. **Chunk 4: Durable Persistence & Rejoin Checkpoints**
   - Target files:
     - `app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt`
     - Integration in `RootNavGraph.kt`
   - Test suites:
     - Persistence and schema migration unit tests.
   - Purpose: Persists exact turn IDs, controller epoch, episode records, pause state, and removes 8-minute TTL for active 1v1 matches.
5. **Chunk 5: Full Build, End-to-End Verification, & Candidate Artifacts**
   - Full suite execution: `./gradlew testDebugUnitTest lintDebug assembleRelease --offline --console=plain --max-workers=2`.
   - Candidate APK generation, SHA-256 fingerprinting, certificate comparison, and `AGENT_HANDOFF.md` update.

---

### Change 001 — Pure 1v1 Heartbeat Policy Reducer & Monotonic Clock Abstraction
- Started / completed: Started 2026-10-15 09:52 IST / Completed 2026-10-15 09:54 IST
- Checkpoint before edit: a13d39edb0ff6a67d272cac187f1ed9fb294f9e9
- Files and symbols:
  - `app/src/main/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicy.kt` (`Clock`, `SystemMonotonicClock`, `PeerAvailabilityState`, `HeartbeatPolicySnapshot`, `HeartbeatDecision`, `ClaimResult`, `OnlineMatchHeartbeatPolicy`)
  - `app/src/test/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicyTest.kt` (14 unit tests)
- Previous behavior: No dedicated 1v1 heartbeat policy reducer existed. Disconnects were evaluated ad-hoc across `RootNavGraph` and `OnlineRoomSyncManager` with a 3-turn forfeit rule, 2s/15s fast-skips, and no recovery stability requirement.
- New behavior: Pure state machine with monotonic clock abstraction implementing exact 15,000 ms silence threshold, 10,000 ms recovery window with 5,000 ms maximum gap reset, single continuous episode tracking (no multiple increments for long outages or repeated timeouts), claim eligibility only on episode 3+ while unreachable, and local unavailability safety guard.
- Scope guard: Pure domain engine class with no external Android dependencies or global state. Only instantiated for 2-player immutable online matches. No effect on AI, LAN, 3+ group matches, or lobbies.
- Storage / packet changes: None in this chunk (pure policy reducer).
- Validation: Full JVM suite passed (318 unit tests, 0 failures, 0 errors, 31 suites). Direct unit tests in `OnlineMatchHeartbeatPolicyTest` verifying H01, H02, H03, H04, H05, H06, H07, H08, H09, C01, C02, C03, H12, R11.
- Remaining concern: None for pure reducer; ready for transport and coordinator integration in Chunks 2 & 3.
### Change 002 — Dedicated Transport, Match Heartbeat Codec, & Decoupled Scheduler
- Started / completed: Started 2026-10-15 09:56 IST / Completed 2026-10-15 10:02 IST
- Checkpoint before edit: f0bdb6e
- Files and symbols:
  - `app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt` (`RoomMessagePacket`, `activeMatchConnectionGeneration`, `startMatchHeartbeat`, `stopMatchHeartbeat`, decoupled coroutine jobs, QoS 0 disposable heartbeats, broker receive path echo tracking, Last Will safety for active 1v1)
  - `app/src/main/java/com/bingo/multiplayer/domain/network/FastPacketCodec.kt` (`MATCH_HEARTBEAT` codec path `MHB|`)
  - `app/src/test/java/com/bingo/multiplayer/domain/network/MatchHeartbeatPacketCodecTest.kt` (6 unit tests)
- Previous behavior: Match heartbeats shared the 2.5s loop with blocking cloud Firestore room reconciliation. Packets lacked matchId, generation, intended peer UID, and challenge IDs. MQTT Last Will was an unvalidated `LEAVE` that could prematurely forfeit an ongoing 1v1 match on brief socket drops.
- New behavior: Adds `MATCH_HEARTBEAT` packet type with `PROBE`/`ACK` challenges, connection generation, intended peer UID, sequence, and protocol version. Decouples the 2.5s match probe job from cloud reconciliation so slow HTTP/cloud requests never starve heartbeats. Probes are QoS 0, non-retained, and non-queued. Challenges must be answered within 5,000 ms and match the active generation. Broker receive health is tracked via broker self-echoes.
- Scope guard: Lobby heartbeats and group matches continue using standard `HEARTBEAT` and cloud reconciliation. `MATCH_HEARTBEAT` is scoped strictly to active 2-player match connections.
- Storage / packet changes: Backward-compatible additions to `RoomMessagePacket` with safe default values; compact `MHB|` micro-payload encoding in `FastPacketCodec` with JSON fallback.
- Validation: Full JVM suite passed (324 unit tests, 0 failures, 0 errors, 32 suites). Direct unit tests in `MatchHeartbeatPacketCodecTest` verifying H10, H11, H14, challenge-reply round-trips, generation isolation, and codec serialization.
- Remaining concern: Integration with `RootNavGraph` coordinator and game screen UI dialog in Chunk 3.
---

### Change 003 — Coordinator, Pause/Resume with Frozen Seconds, Dialogs & Turn Rules
- Started / completed: Started 2026-10-15 10:05 IST / Completed 2026-10-15 10:22 IST
- Checkpoint before edit: c5fba42
- Files and symbols:
  - `app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt` (`isOpponentUnreachable`, `interruptionEpisodeCount`, `isClaimVictoryEligible`, `frozenRemainingTurnSeconds`, `executeClaimVictory`, `handlePauseAction`, match heartbeat integration, turn order preservation, removal of 1v1 auto-forfeits)
  - `app/src/main/java/com/bingo/multiplayer/presentation/game/GameScreen.kt` ("Opponent not responding" dialog, Interruption count body, "Pause game", "Continue countdown", "Claim victory", status banner, confirmation dialog)
  - `app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt` (`claimMatchVictory`, `startMatchHeartbeat` default policy, peer packet fresh response reporting)
  - `app/src/main/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicy.kt` (`canClaimVictory`, `claimVictory`, `updateConnectionGeneration`, `connectionGeneration` in constructor)
  - `app/src/test/java/com/bingo/multiplayer/domain/network/MatchCoordinatorAndPauseTest.kt` (13 unit tests validating T01, T02, T03, T04, T05, P01, P02, P03, P04, C01, C02, C03, C04)
- Previous behavior: 1v1 matches forfeited players after 3 consecutive missed turns, had 2s/15s fast-skips on disconnect, lacked structured pause freezing of milliseconds, and lacked interruption popups.
- New behavior: 1v1 matches preserve both immutable players in turn order without forfeit. Normal 30s countdown runs cleanly. Pause freezes exact remainder and resumes that remainder. Interruption popup presents "Opponent not responding" with "Pause game", "Continue countdown", and "Claim victory" (on episode 3+ while unreachable).
- Scope guard: Multi-player group matches (3+ players) and AI matches continue using their respective rules. All changes are strictly gated to 2-player online matches.
- Storage / packet changes: Handled `CLAIM_VICTORY`, `GAME_PAUSED`, `GAME_RESUMED` packets with `remainingTurnMillis`.
- Validation: Unit test suite `MatchCoordinatorAndPauseTest` covering T01–T05, P01–P04, C01–C04, and full JVM test suite (337 tests, 0 failures, 0 errors, 33 suites).
- Remaining concern: Durable persistence and rejoin checkpointing in Chunk 4.
- Rollback: `git checkout c5fba42 -- app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt app/src/main/java/com/bingo/multiplayer/presentation/game/GameScreen.kt app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt app/src/main/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicy.kt`

---

### Change 004 — Durable Persistence, Account Scoping, & Non-Expiring 1v1 Rejoin
- Started / completed: Started 2026-10-15 10:24 IST / Completed 2026-10-15 10:29 IST
- Checkpoint before edit: 4fadac6
- Files and symbols:
  - `app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt` (`OngoingMatchData` with `turnIds`, `is1v1Match`, `accountUid`, `protocolVersion`, `controllerEpoch`, `matchRevision`, `isPaused`, `remainingTurnMillis`, `pauseOwnerId`, `committedEpisodeCount`, `currentEpisodeId`, `isEpisodeOpen`, `peerAvailabilityState`, atomic `.commit()`, non-expiring 1v1 matches bypassing 8m TTL, account-scoped isolation via `accountUid`, `customPrefs` test injection hook)
  - `app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt` (checkpoint updates on pause/resume, saving `is1v1Match` and `accountUid` on initial start, restoring `isGamePaused`, `frozenRemainingTurnSeconds`, `interruptionEpisodeCount`, and starting `startMatchHeartbeat` on rejoin)
  - `app/src/test/java/com/bingo/multiplayer/domain/network/OngoingMatchStoreRejoinTest.kt` (9 unit tests validating R01, R02, R03, R04, R04b, R05, R06, R08, R10)
- Previous behavior: `OngoingMatchStore` wrote asynchronously with `.apply()`, had a hard 8-minute TTL that silently discarded matches on long pauses or outages, lacked turnIds, pause remaining millis, episode tracking, and lacked account scoping.
- New behavior: Atomic synchronous persistence using `.commit()`, 1v1 online matches bypass 8m TTL and persist indefinitely until game over or explicit surrender, account-scoped isolation prevents cross-account match leakage, backward compatibility with legacy snapshots, full preservation of pause state, turn remainder, and incident history across app switches, restarts, and process kills.
- Scope guard: 1v1 bypass applies strictly when `is1v1Match || participants.size == 2`. Group matches (3+ players) retain their 8-minute TTL.
- Storage / packet changes: Added optional fields to `OngoingMatchData` with backward-compatible defaults.
- Validation: All 346 JVM tests passed (34 suites, 0 failures, 0 errors).
- Rollback: `git checkout 4fadac6 -- app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt`






