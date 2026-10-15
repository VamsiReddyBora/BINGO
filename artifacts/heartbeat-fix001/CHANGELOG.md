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

