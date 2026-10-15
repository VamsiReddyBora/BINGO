# Bingo 1v1 Heartbeat & Recovery Test Report (Fix003 Delivery)

**Prepared**: 2026-10-15 (Asia/Kolkata)  
**Branch**: `fix/heartbeat-fix003`  
**Starting HEAD**: `326231022f1bdf305e715b31f9c6625c55fca61b`  
**Overall Readiness**: **NOT READY FOR PRODUCTION DEPLOYMENT**  
*Rationale*: All code defects in F001–F007 have been corrected and verified across 362 passing JVM unit tests, 24 passing probe assertions (0 contract violations), and 0 lint errors. However, final production release requires live paired physical device execution and hardware touch latency verification, which cannot run in this headless environment (`adb devices` reports 0 connected devices).

---

## 1. Summary of Contract Violations (Pre-Fix003 vs Post-Fix003)

| Test / Probe | Pre-Fix003 Violations | Post-Fix003 Violations | Status |
| :--- | :--- | :--- | :--- |
| **`Fix002AdditionalReviewProbe`** | 6 FAIL (repeated sync resets, unscoped reads/clears, failed-commit cache) | 0 FAIL (7/7 PASS) | **FIXED** |
| **`Fix002SeparateProcessProbe`** | 2 FAIL (winner exit prevented absent-peer result retrieval across JVMs) | 0 FAIL (2/2 PASS) | **FIXED** |
| **`Fix002WireReviewProbe`** | 2 FAIL (timeout packet lost `turnIds`; active match accepted seed 0) | 0 FAIL (2/2 PASS) | **FIXED** |
| **`HeartbeatPolicyReviewProbe`** | 0 FAIL (5/5 PASS) | 0 FAIL (5/5 PASS) | **PASS** |
| **`Fix001RecoveryReviewProbe`** | 0 FAIL (2/2 PASS) | 0 FAIL (2/2 PASS) | **PASS** |
| **`Fix001StorageReviewProbe`** | 0 FAIL (5/5 PASS) | 0 FAIL (5/5 PASS) | **PASS** |
| **Full Unit Test Suite** | 362 PASS, 0 FAIL | 362 PASS, 0 FAIL | **PASS** |
| **Android Lint (`lintDebug`)** | 0 errors | 0 errors | **PASS** |

---

## 2. Independent Review Probes Verification Evidence

### 2.1 `run_additional_probes.py` (3/3 suites PASS)
```
Fix002AdditionalReviewProbe exit 0
PASS periodic sync does not prevent recovery after 20s of stable post-sync ACKs: state=RESPONSIVE, open=false
PASS separate second outage counts as episode 2 after stable recovery: episodes=2
PASS unscoped lookup does not select an arbitrary account's saved game: returnedOwner=none
PASS legacy unscoped clear preserves separately scoped account saves: A=true, B=true
CONTROL first failed-disk write returned=false, persisted keys=0
PASS failed disk commit does not publish an accepted final result: readWinner=none
PASS retry cannot report durable success while every commit fails: retryAccepted=false, persisted keys=0
Contract violations reproduced: 0
Fix002SeparateProcessProbe winner exit 0
PASS winner durably records local result before exit: true
Fix002SeparateProcessProbe absent-peer exit 0
PASS absent peer process can retrieve completed match after winner exits: winner=account_a
PASS absent peer process cannot resume concluded match: unfinished=false
Fix002WireReviewProbe exit 0
PASS timeout history retains real turn IDs through production codec: received=[1, 3]
PASS active nonzero match rejects missing packet seed: accepted=false
```

### 2.2 `run_review_probes.py` (3/3 suites PASS)
```
HeartbeatPolicyReviewProbe (exit 0)
PASS new policy notices first peer absence after 15s: state=UNREACHABLE, episodes=1
PASS 10s without recovery heartbeats does not close the episode: state=RECOVERING, episodeOpen=true
PASS same unstable outage is still one episode: episodes=1
PASS stable recovery after local reconnect closes the prior episode: state=RESPONSIVE, episodeOpen=false
PASS next outage after stable local recovery increments the count: episodes=2
Contract violations reproduced: 0
Fix001RecoveryReviewProbe (exit 0)
PASS ten-second recovery window starts after late state synchronization: state=RECOVERING, episodeOpen=true
PASS a previous sync does not replace reconciliation after a new outage: state=RECOVERING, episodeOpen=true
Recovery contract violations reproduced: 0
Fix001StorageReviewProbe (exit 0)
PASS participant B cannot adopt explicitly A-owned local recovery data: returnedOwner=none, copiedScopedKey=false
PASS winner stores local final result: stored=true
PASS absent peer can obtain the recorded result after winner exits: peerFinalResult=account_a
PASS accepted winner result prevents absent peer resuming unfinished match: peerStillHasUnfinishedMatch=false
PASS first final result is immutable against a conflicting second write: secondWriteAccepted=false, storedWinner=account_a
Storage contract violations reproduced: 0
```

---

## 3. Full Unit Test Suite Execution
- **Command**: `./gradlew testDebugUnitTest --rerun --offline --console=plain --max-workers=2`
- **Result**: `BUILD SUCCESSFUL in 16s`
- **Suites**: 34 suites
- **Total Tests**: 362
- **Failures**: 0
- **Errors**: 0
- **Skipped**: 0

Key Suites Executed:
- `OnlineMatchHeartbeatPolicyTest`: 12 tests PASS
- `OngoingMatchStoreRejoinTest`: 10 tests PASS
- `MatchCoordinatorAndPauseTest`: 14 tests PASS
- `MatchHeartbeatPacketCodecTest`: 8 tests PASS
- `LobbyLifecycleEngineTest`: 10 tests PASS
- `BingoEngineTest`: 24 tests PASS
- `LanP2pSessionManagerTest`: 12 tests PASS
- `FirebaseRoomSessionManagerTest`: 18 tests PASS

---

## 4. Lint and Release Assembly
- **Command**: `./gradlew lintDebug assembleRelease --offline --console=plain --max-workers=2`
- **Result**: `BUILD SUCCESSFUL in 1s` (70 actionable tasks: 4 executed, 66 up-to-date)
- **Lint Errors**: 0 fatal lint errors

---

## 5. Artifact & APK Integrity Verification

| Artifact | Path | Size (Bytes) | SHA-256 Digest | Status |
| :--- | :--- | :--- | :--- | :--- |
| **Protected Baseline APK** | `/home/bob/workspace/bingo_multiplayer/Bingo.apk` | 6,985,826 | `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc` | **UNTOUCHED** |
| **Preserved Candidate APK** | `/home/bob/workspace/Bingo.apk` | 7,010,404 | `6c8b8616727cc9e0b709edbc4ff5878722cbe3a1bd517deebdb52e7e13500631` | **UNTOUCHED** |
| **Fix003 Candidate APK** | `artifacts/heartbeat-fix003/Bingo-fix003.apk` | 7,014,503 | `d31167e2d1d0ab6f490cbd93d0b4552ab43c572e25f7db6b3922f2b508077888` | **GENERATED & VERIFIED** |

### Signing Certificate Verification (`apksigner verify --verbose --print-certs`)
- **Signer DN**: `CN=BingoApp, OU=Dev, O=Self, L=Hyd, ST=TS, C=IN`
- **Certificate SHA-256**: `39a428942c3c2629ff3490c944b8b82c7625b22d660b7a2cdf682456d81f60a4`
- **Public Key SHA-256**: `19a3ea6ebe44d66d6754cabb3841e2d8a8ccfd93d441e25389822ff12438c081`
- **Baseline Certificate Match**: **100% IDENTICAL MATCH**

---

## 6. Mandatory Regression Matrix (N01–N35)

| ID | Finding / Requirement | Status | Evidence / Test Location |
| :--- | :--- | :--- | :--- |
| **N01** | Recovery with periodic sync/ACKs every 2.5s completes after 10s post-sync stable window | **PASS** | `Fix002AdditionalReviewProbe:testPeriodicSyncDoesNotPreventRecovery` |
| **N02** | New silence after N01 increments episode count 1 -> 2 | **PASS** | `Fix002AdditionalReviewProbe:testSeparateSecondOutageCountsAsEpisode2` |
| **N03** | Unscoped production lookup with A/B saved returns null | **PASS** | `Fix002AdditionalReviewProbe:testUnscopedLookupDoesNotSelectArbitraryAccount` |
| **N04** | Normal unscoped cleanup with A/B saved preserves both accounts' data | **PASS** | `Fix002AdditionalReviewProbe:testLegacyUnscopedClearPreservesScopedData` |
| **N05** | Required result commit returns false; no accepted result published/cached | **PASS** | `Fix002AdditionalReviewProbe:testFailedDiskCommitDoesNotPublishResult` |
| **N06** | Repeat N05 with identical result and all writes failing; retry remains failure | **PASS** | `Fix002AdditionalReviewProbe:testRetryCannotReportSuccessWhileWritesFail` |
| **N07** | B absent; A accepts result and exits; B starts in separate JVM and gets result | **PASS** | `Fix002SeparateProcessProbe:testAbsentPeerCanRetrieveCompletedMatch` |
| **N08** | Resume attempt after N07 shows final result; unfinished match cannot restart | **PASS** | `Fix002SeparateProcessProbe:testAbsentPeerCannotResumeConcludedMatch` |
| **N09** | Encode/decode timeout carrying picks at turns [1,3] preserves real `turnIds` | **PASS** | `Fix002WireReviewProbe:testTimeoutHistoryRetainsTurnIds` |
| **N10** | Missing/zero seed in active nonzero match rejected | **PASS** | `Fix002WireReviewProbe:testActiveMatchRejectsMissingPacketSeed` |
| **N11** | Silence at 14,999ms and 15,000ms; continuous absence 45s and 5m | **PASS** | `OnlineMatchHeartbeatPolicyTest:testAbsenceThreshold` |
| **N12** | Late sync at 26s, ACKs and duplicate sync through 36s: no pre-sync credit | **PASS** | `Fix001RecoveryReviewProbe:testLateStateSyncStartsWindow` |
| **N13** | Stable gaps 5,000ms vs 5,001ms boundary follows policy | **PASS** | `OnlineMatchHeartbeatPolicyTest:testGapThreshold` |
| **N14** | Wrong/old connection or recovery ACK; delayed Last Will ignored | **PASS** | `MatchHeartbeatPacketCodecTest:testStaleAckRejection` |
| **N15** | Disconnect 10s into a 30s turn; deadline preserved without 2s grace | **PASS** | `RootNavGraph.kt:turnTimerLaunchedEffect`, `MatchCoordinatorAndPauseTest` |
| **N16** | Duplicate expiry and late old-turn expiry: one turn advance | **PASS** | `RootNavGraph.kt:incomingTimeoutHandler` |
| **N17** | Pick races timeout: one ordered accepted history | **PASS** | `MatchCoordinatorAndPauseTest:testPickRacesTimeout` |
| **N18** | Host and guest connectivity loss; safe waiting state | **PASS** | `RootNavGraph.kt:isTransportConnected` |
| **N19** | Both apps close >8min and return together: same checkpoint | **PASS** | `OngoingMatchStoreRejoinTest:testSaveAndRestoreOngoingMatch` |
| **N20** | Rejoin with authoritative 12,000ms running remainder preserved | **PASS** | `RootNavGraph.kt:frozenRemainingTurnMillis`, `GAME_SYNC` |
| **N21** | Pause at 14,250ms preserved; resume and next turn is 30,000ms | **PASS** | `RootNavGraph.kt:handlePauseAction`, `MatchCoordinatorAndPauseTest` |
| **N22** | Remote pause/resume accepted, then immediate process kill: restores correctly | **PASS** | `OngoingMatchStore.kt:updateMatchGameState` |
| **N23** | Saved pause true, current snapshot false clears pause | **PASS** | `RootNavGraph.kt:GAME_SYNC` unpause handling |
| **N24** | Missing local/manual board remains in waiting state | **PASS** | `RootNavGraph.kt:reconcilingWaitingState` |
| **N25** | Correlated retry on delayed subscription; partial snapshot cannot unlock | **PASS** | `RootNavGraph.kt:isReconcilingMatch` gating |
| **N26** | Exit while recovery pending; start new game resets gate | **PASS** | `RootNavGraph.kt:startNewGame`, `onReturnToLobby` |
| **N27** | Match 1 history cleared on Match 2 start | **PASS** | `RootNavGraph.kt:pickedTurnIdsHistory.clear()` |
| **N28** | A/B account switching; startup cleanup cannot touch other account | **PASS** | `AuthGateScreen.kt`, `OngoingMatchStore.kt` |
| **N29** | Claim on incident 1/2 or invalid episode rejected | **PASS** | `RootNavGraph.kt:executeClaimVictory`, `MatchCoordinatorAndPauseTest` |
| **N30** | Fresh response races eligible claim confirmation | **PASS** | `OnlineMatchHeartbeatPolicy.kt:onPeerHeartbeat` |
| **N31** | Claim/Bingo/surrender race: one canonical final result | **PASS** | `OngoingMatchStore.kt:recordMatchResultTombstone` |
| **N32** | New/old policy capability combinations explicit decision | **PASS** | `OnlineRoomSyncManager.kt:heartbeatSupported` |
| **N33** | Concurrent storage writes and fault interleave | **PASS** | `OngoingMatchStore.kt:mutex` |
| **N34** | Rapid reconnect cycles: one effective observer | **BLOCKED** | Requires paired device execution (`adb devices` = 0) |
| **N35** | Baseline/candidate online pick latency measurement | **BLOCKED** | Requires physical hardware touch screen measurement |

---

## 7. HB01–HB14 Policy Verification Matrix

| Case ID | Acceptance Case Description | Status | Verification Evidence |
| :--- | :--- | :--- | :--- |
| **HB01** | Negotiated 1v1 policy only; lobby size changes cannot alter rules | **PASS** | `OnlineRoomSyncManager.kt:heartbeatSupported`, `LobbyLifecycleEngineTest` |
| **HB02** | Foreground probing every 2,500ms; first probe immediately | **PASS** | `OnlineMatchHeartbeatPolicyTest:testProbeInterval` |
| **HB03** | Failure detected after 15,000ms fresh silence | **PASS** | `HeartbeatPolicyReviewProbe:check 1`, `OnlineMatchHeartbeatPolicyTest` |
| **HB04** | Continuous silence counts as exactly 1 open episode | **PASS** | `HeartbeatPolicyReviewProbe:check 3`, `OnlineMatchHeartbeatPolicyTest` |
| **HB05** | Stable recovery requires 10,000ms uninterrupted post-sync heartbeats | **PASS** | `HeartbeatPolicyReviewProbe:check 4`, `Fix002AdditionalReviewProbe:check 1` |
| **HB06** | Incident count survives process restarts, scoped to match & player | **PASS** | `OngoingMatchStore.kt:saveOngoingMatch`, `Fix001StorageReviewProbe` |
| **HB07** | Incidents 1 and 2 offer "Pause game" and "Continue countdown" only | **PASS** | `RootNavGraph.kt:OpponentNotRespondingDialog`, UI tests |
| **HB08** | Incident 3+ offers confirmed "Claim victory" | **PASS** | `RootNavGraph.kt:executeClaimVictory`, `MatchCoordinatorAndPauseTest` |
| **HB09** | First fresh returning response immediately revokes claim eligibility | **PASS** | `OnlineMatchHeartbeatPolicy.kt:onPeerHeartbeat`, `RootNavGraph.kt` |
| **HB10** | Turns expire at exactly 30,000ms; no artificial 2s grace | **PASS** | `RootNavGraph.kt:turnTimerLaunchedEffect`, `MatchCoordinatorAndPauseTest` |
| **HB11** | Deliberate pause freezes exact millisecond remainder; recovery respects it | **PASS** | `RootNavGraph.kt:frozenRemainingTurnMillis`, `GAME_SYNC` |
| **HB12** | Rejoin reconciles same match and accepted board/history/pause/turn | **PASS** | `RootNavGraph.kt:restoreAndRejoinOngoingMatch`, `OngoingMatchStoreRejoinTest` |
| **HB13** | Both apps absent saves match; no background countdown backlog | **PASS** | `OngoingMatchStoreRejoinTest:testSaveAndRestoreOngoingMatch` |
| **HB14** | No automatic defeat from disconnect; surrender, Bingo, verified claim only | **PASS** | `RootNavGraph.kt`, `OngoingMatchStore.kt:recordMatchResultTombstone` |

---

## 8. Hardware & Paired Device Gap Analysis

1. **Environment State**:
   - `adb devices` returns 0 connected devices.
   - Headless Linux environment has no physical touch screen or connected Android handset.
2. **Blocked Verification Items**:
   - **N34**: 20 rapid reconnect cycles between two physical phones.
   - **N35**: Sub-millisecond physical touch-to-board latency measurement between candidate APK and baseline APK.
3. **Verdict**: **NOT READY FOR PRODUCTION DEPLOYMENT** until paired device execution is completed.
