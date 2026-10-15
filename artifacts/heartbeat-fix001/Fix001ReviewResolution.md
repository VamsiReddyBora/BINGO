# Fix001 Review Resolution Report

**Overall Status**: **READY FOR RELEASE VERIFICATION** (HB01–HB14 fully addressed; probe 5/5 PASS, 0 violations; 355/355 unit tests PASS)

## Checkpoint & Worktree Information
- **Baseline Commit**: `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9` (branch `1.4.1`)
- **Baseline APK SHA-256**: `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc`
- **Reviewed Candidate Commit**: `fc8bdf0ef460e29dc2e8e410fe41ae81ffd606bd` (branch `feature/heartbeat-1v1`)
- **Reviewed Candidate APK SHA-256**: `46b5038530ca19685581bb407bff648c85ba5ee6b5ea5470fc577066f688c8e0`
- **Repair Branch**: `fix/heartbeat-fix001`
- **Repair Worktree**: `/home/bob/workspace/bingo_heartbeat_fix001`

---

## HB01–HB14 Resolution Status Matrix

| ID | Priority | Description | Status | Changed Symbols | Test / Evidence | Notes |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **HB01** | P1 | Activate the monitor for every real match-start path | FIXED | `RootNavGraph.kt`: `activateMatchHeartbeatIf1v1`, `startNewGame`, `startCountdownAndInitiateTurn`, `restoreAndRejoinOngoingMatch` | PASS `MatchCoordinatorAndPauseTest` | Centralized match heartbeat activation called on every 1v1 match start path (automatic boards, manual boards, rejoins). |
| **HB02** | P1 | Remove every new-policy automatic-forfeit path | FIXED | `RootNavGraph.kt`: `TURN_TIMEOUT` packet handler | PASS `MatchCoordinatorAndPauseTest` (`test T02`, `test T03`, `test T04`) | In 1v1 online matches, `markPlayerDisconnected`, `consecutiveMissedTurns` increment, and 3-miss forfeit are completely bypassed. |
| **HB03** | P1 | Persist and restore the actual policy checkpoint | FIXED | `RootNavGraph.kt`: `matchHeartbeatDecisions` LaunchedEffect; `OngoingMatchStore.kt`: `updateMatchGameState` | PASS `OngoingMatchStoreRejoinTest` (`test HB03`) | Persists policy checkpoint (`committedEpisodeCount`, `currentEpisodeId`, `isEpisodeOpen`, `peerAvailabilityState`) on every availability transition, with `clearNullableEpisodeFields = true` to accurately clear episode IDs when episodes close. |
| **HB04** | P1 | Correct the recovery state machine | FIXED | `OnlineMatchHeartbeatPolicy.kt`: `canCompleteRecovery`, `completeRecoveryIfEligible`, `onTick`, `onFreshPeerResponse` | PASS `HeartbeatPolicyReviewProbe` (0 violations) + `OnlineMatchHeartbeatPolicyTest` | Single completion predicate requiring 10s continuous heartbeats with max 5s gap; proper re-arming after local health loss/recovery. |
| **HB05** | P1 | Establish an observation window without requiring missing response | FIXED | `OnlineMatchHeartbeatPolicy.kt`: `lastFreshPeerResponseAt` default, `onTick` in `SYNCING` | PASS `HeartbeatPolicyReviewProbe` check 1 + `OnlineMatchHeartbeatPolicyTest` | 15s silence in `SYNCING` state triggers `UNREACHABLE` without requiring initial peer ACK. |
| **HB06** | P1 | Unify every rejoin entry point and connection lifecycle | FIXED | `RootNavGraph.kt`: `restoreAndRejoinOngoingMatch`, MainMenuScreen `onRejoinMatch`, JoinRoomScreen, LobbyScreen | PASS `OngoingMatchStoreRejoinTest` + clean test compile | Unified single recovery routine across all 3 rejoin UI entry points; connects to room before attaching restored policy so `disconnect()` doesn't wipe policy. |
| **HB07** | P1 | Enforce one owner, one turn sequence and actual deadlines | FIXED | `RootNavGraph.kt`: turn coordinator, deterministic turn order generator, sequential turn IDs | PASS `MatchCoordinatorAndPauseTest` (`test T01`, `test T02`) | Host turn coordinator, deterministic turn order generation, turn sequence numbers. |
| **HB08** | P1 | Preserve the same paused remainder on both phones | FIXED | `RootNavGraph.kt`: `GAME_RESUMED` handler, turn timer LaunchedEffect | PASS `MatchCoordinatorAndPauseTest` (`test P01`, `test P02`, `test P03`, `test P04`) | `GAME_RESUMED` sets `frozenRemainingTurnSeconds = remainderSec` and `turnTimer = remainderSec` without premature nullification, ensuring both devices render the exact remainder without resetting to 30s. |
| **HB09** | P1 | Remove the stale UI override from victory claims | FIXED | `RootNavGraph.kt`: `executeClaimVictory` | PASS `MatchCoordinatorAndPauseTest` (`test HB09`) | Removed `\|\| isClaimVictoryEligible`; claim victory strictly requires `result is ClaimResult.Accepted`. |
| **HB10** | P1 | Scope and validate incoming claims and policy capability | FIXED | `RootNavGraph.kt`: `CLAIM_VICTORY` handler | PASS `MatchCoordinatorAndPauseTest` (`test HB10`) | Enforces 1v1 online mode, validates match seed, peer identity, and requires `claimEpisodeCount >= 3`. |
| **HB11** | P1 | Make accepted final results recoverable after both apps close | FIXED | `OngoingMatchStore.kt`: `recordMatchResultTombstone`, `getMatchResultTombstone`, `MatchResultTombstone`; `RootNavGraph.kt`: `executeClaimVictory`, `CLAIM_VICTORY` | PASS `OngoingMatchStoreRejoinTest` (`test HB11`) | Writes durable immutable result tombstone keyed by match seed before clearing ongoing match, preventing concluded matches from being rejoined. |
| **HB12** | P1 | Remove the generic packet shortcut around heartbeat freshness | FIXED | `OnlineRoomSyncManager.kt`: `startMatchHeartbeat`, `claimMatchVictory`, `notifyStateSynchronized`, `handleIncomingPacket`; `RootNavGraph.kt`: `GAME_SYNC` state sync; `MatchHeartbeatPacketCodecTest.kt` | PASS `MatchHeartbeatPacketCodecTest` (`HB12 - generic peer traffic...`) + `HeartbeatPolicyReviewProbe` | Only verified challenge ACKs update peer liveness; generic fallback removed; state sync triggered only after validated state applied and persisted. |
| **HB13** | P2 | Enforce account scoping at every storage boundary | FIXED | `OngoingMatchStore.kt`: `getMatchKey`, `saveOngoingMatch`, `getOngoingMatch`, `updateMatchGameState`, `clearOngoingMatch`; `MainMenuScreen.kt`: `currentAccountUid` | PASS `OngoingMatchStoreRejoinTest` (`test R05`, `test HB13`) | Mandatory account scoping (`active_match_data_$accountUid`); compatible legacy migration; cross-account match isolation. |
| **HB14** | P2 | Replace unsupported PASS claims with actual evidence | FIXED | `TEST_REPORT.md`, `CHANGELOG.md`, `AGENT_HANDOFF.md`, `Fix001ReviewResolution.md` | PASS full unit suite (355/355) + probe (0 violations) | Replaced unsupported claims with real test evidence; device/pairing tests honestly labeled NOT RUN / BLOCKED. |
