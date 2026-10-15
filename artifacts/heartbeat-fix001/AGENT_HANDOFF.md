# Bingo 1v1 Heartbeat & Recovery Fix001 Agent Handoff

**Prepared**: 2026-10-15 (Asia/Kolkata)  
**Worktree**: `/home/bob/workspace/bingo_heartbeat_fix001`  
**Branch**: `fix/heartbeat-fix001`  
**Current HEAD**: `bd4c5c1`  
**Overall Readiness**: **NOT READY FOR PRODUCTION DEPLOYMENT**  
- **Code & Test Status**: All 14 review findings (**HB01–HB14**) are fully repaired, committed, and verified. Independent probe: 5/5 PASS (0 violations). Full JVM unit suite: 355/355 PASS. Lint: 0 errors. Candidate Release APK built and signed.
- **Why NOT READY**: Physical/emulator paired-device execution and hardware tap-to-pick latency measurement could not be run in this headless execution environment (no adb devices connected). In accordance with HB14, unrun hardware tests are recorded as `NOT RUN / BLOCKED` rather than claiming premature readiness.

---

## Protected Checkpoints & APK Metadata

| Reference | Commit SHA | Path / SHA-256 | Notes |
| :--- | :--- | :--- | :--- |
| **Protected Baseline** | `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9` (branch `1.4.1`) | APK: `/home/bob/workspace/bingo_multiplayer/Bingo.apk`<br>SHA-256: `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc`<br>Size: 6,985,826 bytes | Protected original clean 1.4.1 baseline. NEVER overwrite, force-push, or rebase. Untouched. |
| **Reviewed Candidate** | `fc8bdf0ef460e29dc2e8e410fe41ae81ffd606bd` (branch `feature/heartbeat-1v1`) | APK: `artifacts/heartbeat-1v1/Bingo-candidate.apk`<br>SHA-256: `46b5038530ca19685581bb407bff648c85ba5ee6b5ea5470fc577066f688c8e0`<br>Size: 7,006,311 bytes | Pre-fix candidate reviewed in `HeartbeatImplementationReview.md` with 14 findings. Preserved for diff. |
| **Fix001 Candidate** | `bd4c5c1` (branch `fix/heartbeat-fix001`) | APK: `artifacts/heartbeat-fix001/Bingo-fix001.apk`<br>SHA-256: `6a96c7148629d777bbd203b4ac1849ee92bdb417c1802bdd6a274c267e186437`<br>Size: 7,010,405 bytes | Repair delivery resolving HB01–HB14. Signing cert SHA-256 identically matches baseline. |

---

## Ordered Commit History in Repair Branch

1. `fa190fd`: `chore(fix001): initialize repair worktree, baseline manifest, change journal and review docs`
2. `b95fe3c`: `fix(heartbeat): correct recovery stability, gap reset, syncing silence and re-arm (HB04, HB05)`
3. `8df72ba`: `fix(heartbeat): restrict freshness to challenge ACKs and remove generic packet fallback (HB12)`
4. `bd4c5c1`: `fix(heartbeat): implement coordinator lifecycle, pause remainder, tombstones, and account scoping (HB01-HB03, HB06-HB11, HB13)`

---

## Key Technical Resolutions (HB01–HB14)

1. **HB01 (Match Heartbeat Activation)**: Centralized `activateMatchHeartbeatIf1v1()` in `RootNavGraph.kt`. Invoked across automatic board starts (`startNewGame`), manual countdowns (`startCountdownAndInitiateTurn`), and all match rejoins. Idempotent start cleanly cancels stale jobs.
2. **HB02 (Removal of Auto-Forfeit)**: In `RootNavGraph.kt` `TURN_TIMEOUT` packet handler, 1v1 online matches bypass `markPlayerDisconnected`, `consecutiveMissedTurns` increment, and the 3-miss forfeit logic. Both players remain in rotation indefinitely.
3. **HB03 (Policy Checkpoint Persistence)**: `OngoingMatchStore.updateMatchGameState` supports `clearNullableEpisodeFields = true` to accurately nullify episode IDs when episodes close. Policy checkpoints (`committedEpisodeCount`, `currentEpisodeId`, `isEpisodeOpen`, `peerAvailabilityState`) are persisted on every availability transition.
4. **HB04 (Recovery State Machine)**: `OnlineMatchHeartbeatPolicy.canCompleteRecovery` unifies the recovery completion predicate across tick, ACK, and sync callbacks. Requires 10,000 ms of continuous responses with no gap exceeding 5,000 ms. Re-arms only after stable recovery completes.
5. **HB05 (Observation Window in SYNCING)**: `lastFreshPeerResponseAt` defaults to clock start; `onTick` in `SYNCING` state evaluates silence and transitions to `UNREACHABLE` after 15,000 ms without requiring a first peer ACK.
6. **HB06 (Unified Rejoin Entry Point)**: MainMenuScreen, JoinRoomScreen, and LobbyScreen all delegate to `restoreAndRejoinOngoingMatch()` in `RootNavGraph.kt`. Connects to room before attaching restored policy so room connection cleanup does not destroy policy state.
7. **HB07 (Single Owner & Sequential Turns)**: Host turn coordinator enforces monotonic turn order, turn sequence IDs, and action deduplication.
8. **HB08 (Pause Remainder Preservation)**: `GAME_RESUMED` sets `frozenRemainingTurnSeconds = remainderSec` and `turnTimer = remainderSec` without premature nullification, ensuring both devices resume at the exact frozen remainder rather than resetting to 30s.
9. **HB09 (Removal of UI Override on Claims)**: `executeClaimVictory()` strictly requires `result is ClaimResult.Accepted`, removing `|| isClaimVictoryEligible`. A fresh peer response immediately invalidates claim eligibility.
10. **HB10 (Scope and Validate Incoming Claims)**: `CLAIM_VICTORY` handler enforces 1v1 online mode, validates match seed, peer identity, and requires `claimEpisodeCount >= 3`.
11. **HB11 (Durable Final Result Tombstones)**: `OngoingMatchStore.recordMatchResultTombstone` writes an immutable tombstone keyed by match seed before clearing active match data. Prevents offline losers from rejoining completed matches.
12. **HB12 (Challenge-Reply Freshness)**: `OnlineRoomSyncManager` removed generic packet fallback that treated arbitrary traffic as liveness. Only verified challenge ACKs update peer liveness. State sync is triggered only after validated state is applied and saved.
13. **HB13 (Enforced Account Scoping)**: `OngoingMatchStore` scopes keys by authenticated UID (`active_match_data_$accountUid`). Legacy un-scoped data is safely migrated only when account ownership matches.
14. **HB14 (Honest Test Evidence)**: Replaced unsupported PASS claims with genuine test evidence in `TEST_REPORT.md` and `Fix001ReviewResolution.md`. Paired-device tests are accurately recorded as `NOT RUN / BLOCKED`.

---

## Verification Commands & Proof

```bash
# 1. Independent Contract Probe (0 violations)
python3 /home/bob/workspace/heartbeat-review-evidence/run_probe.py /home/bob/workspace/bingo_heartbeat_fix001

# 2. JVM Unit Tests (355/355 PASS)
./gradlew testDebugUnitTest --rerun --offline --console=plain --max-workers=2

# 3. Android Lint (0 errors)
./gradlew lintDebug --offline --console=plain --max-workers=2

# 4. Release APK Build (SUCCESS)
./gradlew assembleRelease --offline --console=plain --max-workers=2

# 5. Certificate Verification (Matches baseline)
/home/bob/android-sdk/build-tools/34.0.0/apksigner verify --print-certs artifacts/heartbeat-fix001/Bingo-fix001.apk
```

---

## Next Steps for Device-Level Verification

When paired physical devices or emulators (e.g. Kevin & Stuart) become available:
1. Install `artifacts/heartbeat-fix001/Bingo-fix001.apk` on both devices.
2. Run automated pairing scenarios:
   - Verify heartbeat traffic every 2.5s.
   - Disconnect network on peer for 15s: verify "Opponent not responding" popup appears on incident 1.
   - Reconnect within 10s: verify popup dismisses and game resumes without turn timer reset.
   - Test 3rd disconnect incident: verify "Claim victory" button appears and functions.
   - Measure tap-to-accepted-pick latency across 20 turns.
3. Update `TEST_REPORT.md` with screenshots and device logs, and mark overall readiness as **READY** once live verification passes.
