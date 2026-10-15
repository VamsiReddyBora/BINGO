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
- Rollback: `git checkout a13d39edb0ff6a67d272cac187f1ed9fb294f9e9 -- app/src/main/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicy.kt app/src/test/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicyTest.kt`


