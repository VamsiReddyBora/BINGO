# Fix001 Review Resolution Report

**Overall Status**: **NOT READY** (Repairs underway)

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
| **HB01** | P1 | Activate the monitor for every real match-start path | NOT RUN | `RootNavGraph.kt`: `startNewGame`, manual countdown, host/guest auto-start | Pending | Must centralize match activation after connection/identity readiness |
| **HB02** | P1 | Remove every new-policy automatic-forfeit path | NOT RUN | `RootNavGraph.kt`: `TURN_TIMEOUT` handler, `consecutiveMissedTurns` | Pending | Bypass 3-miss forfeit for negotiated 1v1 online |
| **HB03** | P1 | Persist and restore the actual policy checkpoint | NOT RUN | `RootNavGraph.kt`, `OngoingMatchStore.kt` | Pending | Persist policy state on availability changes and restore into policy |
| **HB04** | P1 | Correct the recovery state machine | NOT RUN | `OnlineMatchHeartbeatPolicy.kt`: recovery completion & sync handling | Pending | Single completion predicate requiring 10s stable heartbeats with max 5s gap |
| **HB05** | P1 | Establish an observation window without requiring missing response | NOT RUN | `OnlineMatchHeartbeatPolicy.kt`, `OnlineRoomSyncManager.kt` | Pending | Bounded observation start once local health and match identity ready |
| **HB06** | P1 | Unify every rejoin entry point and connection lifecycle | NOT RUN | `RootNavGraph.kt`: menu, lobby, join-room rejoin | Pending | Single recovery routine; do not start heartbeat before `connectToRoom` |
| **HB07** | P1 | Enforce one owner, one turn sequence and actual deadlines | NOT RUN | `RootNavGraph.kt`: turn countdown, action commit, timeout auth | Pending | Serialized production coordinator, action IDs, epoch/revision |
| **HB08** | P1 | Preserve the same paused remainder on both phones | NOT RUN | `RootNavGraph.kt`: `GAME_RESUMED`, pause handling, timer effect | Pending | Render timer from single accepted remainder state without resetting to 30 |
| **HB09** | P1 | Remove the stale UI override from victory claims | NOT RUN | `RootNavGraph.kt`: `executeClaimVictory` | Pending | Remove `\|\| isClaimVictoryEligible`; validate current accepted policy result |
| **HB10** | P1 | Scope and validate incoming claims and policy capability | NOT RUN | `RootNavGraph.kt`: `CLAIM_VICTORY` handler | Pending | Validate match ID, episode, count, controller, revision; reject LAN/group |
| **HB11** | P1 | Make accepted final results recoverable after both apps close | NOT RUN | `RootNavGraph.kt`, `OngoingMatchStore.kt` | Pending | Store durable final result tombstone before clearing active match |
| **HB12** | P1 | Remove the generic packet shortcut around heartbeat freshness | NOT RUN | `OnlineRoomSyncManager.kt`: incoming packet handler | Pending | Only verified challenge ACKs call `onFreshPeerResponse`; sync on validated state |
| **HB13** | P2 | Enforce account scoping at every storage boundary | NOT RUN | `OngoingMatchStore.kt`, `RootNavGraph.kt`, `MainMenuScreen.kt` | Pending | Mandatory `accountUid` parameter, account-scoped storage keys |
| **HB14** | P2 | Replace unsupported PASS claims with actual evidence | NOT RUN | `TEST_REPORT.md`, `CHANGELOG.md`, `AGENT_HANDOFF.md` | Pending | Mark unexecuted integration/device cases as NOT RUN/BLOCKED; real evidence |
