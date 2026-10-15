# Heartbeat Fix002 — independent code review

Reviewed: 2026-10-15 13:30:39 IST (UTC+05:30). Candidate: `326231022f1bdf305e715b31f9c6625c55fca61b`, branch `fix/heartbeat-fix002`, directory `/home/bob/workspace/bingo_heartbeat_fix002`.

**Verdict: the required behavior is not ready. Fix002 contains useful repairs, but all eight previous finding areas still have unresolved parts. The remaining blockers include reproduced code defects, not just missing device verification. Do not merge this candidate into the protected baseline yet.**

This review made no changes to application source, branches, APKs or deployments. Review scripts and evidence are stored separately.

## Verification performed

| Check | Actual result |
|---|---|
| Production unit test task, rerun with `./gradlew testDebugUnitTest --rerun --offline --console=plain --max-workers=2` | **362 tests, 34 suites, 0 failures, 0 errors, 0 skipped** |
| Original policy review probe | 5 assertions pass |
| Fix001 recovery review probe | 2 assertions pass |
| Fix001 storage review probe | 5 assertions pass; its two devices share one JVM/singleton |
| Additional policy/storage checks against actual production classes | **6 failed assertions** |
| Independent winner and returning-player JVMs | Winner's local write succeeds; **2 returning-player assertions fail** |
| Production codec and match-scope checks | **2 failed assertions** |
| Real paired Android device/emulator gameplay, screenshots and latency | **NOT RUN** in this review |
| Protected baseline source fingerprints | **159/159 unchanged** |
| Protected baseline APK | SHA-256 remains `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc` |
| Candidate tracked working tree after review | Clean |

The 12 earlier probe assertions pass. **Ten additional contract assertions fail.** Those failures are assertions about production components and are not ten independently counted bugs. Their grouping and effects are explained below. These are component tests and source tracing, not a claim of end-to-end Android simulation.

The earlier storage probe switches between two preferences objects inside one singleton. Its comment about the winner's process exiting does not actually terminate a process. A production static cache makes that test pass. The new checks launch the winner and absent player in separate JVMs, so they cannot accidentally share process memory. They do not simulate an MQTT broker; inspection of the actual result record/lookup paths confirms that those paths contain no shared result transport or retrieval.

## Remaining findings, with locations and fixes

### F006 — P1: periodic game synchronization prevents recovery from completing

**Locations:** [2.5-second GAME_SYNC broadcaster](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:1675), [every GAME_SYNC marks synchronized](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2426), [GAME_SYNC_ACK handler](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2441), [recovery start reset](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicy.kt:381).

Fix002 correctly invalidates an old synchronization on an outage and starts a stability window after synchronization. However, `onStateSynchronized()` resets `recoveryStartedAt = now` on **every** call while recovering. Both apps broadcast GAME_SYNC every 2.5 seconds, and receiving GAME_SYNC or its new ACK calls this method again. Stable heartbeat responses cannot accumulate the required 10 seconds while these messages keep resetting the window.

**Executed reproduction:** first outage → fresh response → state synchronized → 20 seconds of regular 2.5-second syncs and fresh heartbeat responses. Actual state remains `RECOVERING`, episode open. A subsequent 15-second outage leaves the count at **1 instead of 2**. This can prevent the third-incident claim option from ever becoming eligible under normal periodic synchronization.

**Fix:** bind synchronization to the current recovery attempt and accepted state revision. Start the post-sync stability clock once for that attempt. Duplicate snapshots/ACKs for the already accepted state must not restart it. Only a new outage, failed reconciliation or excessive verified heartbeat gap should invalidate/reset it. Validate ACK match, participant and recovery identity before changing policy state. Test with the actual periodic sync cadence, not one isolated sync call.

### F002 — P1: final results are still unavailable across phones; failed commits become accepted results

**Locations:** [static cache](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt:87), [record result](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt:274), [lookup result](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt:313), [outgoing MQTT publish is not retained](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt:650).

`sharedMatchResults` is a `ConcurrentHashMap` inside one Android process. It is neither cloud storage nor cross-device persistence. Result lookup reads local SharedPreferences and this map. If A claims while B is absent and A closes before B returns, B cannot obtain that result from these paths. Ordinary result packets are non-retained. The same-JVM storage test passes because both fake devices use the same singleton cache.

**Executed reproduction:** winner process records its local result and exits. A separate returning-player process has its own unfinished saved match. Actual lookup returns **no result**, and the unfinished match remains recoverable.

There is also a new write-failure bug. Recording places the result into the map **before** `commit()`. With an editor that always fails commits, the first call returns false, but lookup returns the cached winner. Retrying the identical write then returns **true with zero persisted keys**. This lets callers mistake a failed write for durable success and delete active state/update results on a retry.

**Fix:** use a genuinely shared, recoverable accepted-result record with canonical match/result identity and conflict protection. Preserve MQTT gameplay. A retained record may provide recovery only if broker retention, access and single-writer/commit requirements are actually satisfied; a broker or local map alone does not resolve conflicting winners. If the available infrastructure cannot meet the guarantee, retain recoverable state, enter an explicit waiting condition and report this requirement **BLOCKED**. Publish/cache success only after the required durable commit succeeds; failed writes and retries must remain failures. Verify independent processes/devices and process death, not just switched preferences in one process.

Sequential conflicting local writes are now rejected, and the claim handlers check the storage Boolean before immediate UI/stat updates. Those improvements do not fix shared recovery or the poisoned-cache retry.

### F001 — P1: turns still lack a shared deadline and enforceable single commit order

**Locations:** [timer initialization/loop](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:4113), [local-player timeout](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:4205), [host/acting-host timeout](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:4213), [turn mutation](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:1573), [timeout acceptance](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2095).

The timer still starts at `30` or an integer frozen remainder, calls `delay(1000)` and subtracts one. There is no shared turn deadline in this path. Coroutine scheduling delays stretch turns. Local MQTT disconnection freezes only that phone's counter; the opponent may continue advancing. Reconnecting does not reconcile that clock before the counter resumes.

Both the current player and the host can issue expiry decisions. A guest also becomes acting host when its local view says the host is gone. No enforced controller epoch, accepted revision or atomic turn commit orders these writers. `controllerEpoch` and `matchRevision` fields exist, but this execution path does not enforce them. A number picked close to expiry and an independently generated timeout can therefore conflict; a local host flag does not establish an authoritative shared history. The socket connection check is also weaker than the foreground/subscription/receive-health policy.

**Fix:** one accepted transition per exact match/turn, ordered through an enforceable owner/commit mechanism; bind moves, timeout and pause/resume to that identity/revision. Derive the displayed remainder from the accepted deadline and a monotonic local projection. Reconcile before resuming mutations after transport uncertainty. If safe ownership cannot be established, show a safety wait instead of automatically promoting from a heartbeat observation. Keep the new removal of the 1v1 two-second grace and the previous automatic-forfeit guards.

This is a source-path finding. This review does not claim to have executed the Android timeout/pick race or measured device countdown drift.

### F004 — P1: rejoin unlocks after incomplete synchronization and still restores the wrong time/state

**Locations:** [restore timer](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:1377), [board restore/wait flag](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:1402), [fixed-delay rejoin requests](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:1452), [REJOIN_GAME snapshot](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2275), [snapshot application](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2358), [unconditional unlock](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2426), [richer SYNC_REQUEST response](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2950).

The new reconciliation flag blocks picks and timers initially. But any accepted GAME_SYNC reaches `isReconcilingMatch = false`; the handler does not require a complete authoritative response for the current recovery attempt or successful persistence. Periodic GAME_SYNC and the REJOIN_GAME response still omit the new timing/pause/turn-ID fields. Even the richer SYNC_REQUEST response is only partially applied:

- Unpaused `remainingTurnMillis` is ignored. Restoring an unpaused match starts at **30 seconds**; a peer with 12 seconds left does not transfer those 12 seconds to this path.
- Only `isPaused=true` is applied. An accepted current snapshot with `isPaused=false` does not clear an old saved pause.
- Complete board/layout data and chat history are not supplied by these snapshots. If the local board is missing, the toast says to wait, but later history-only GAME_SYNC still unlocks without restoring that missing board. The opponent-board fallback still generates a board.
- Incident checkpoint metadata is not fully exchanged/applied. A self-reported count field is not the shared episode record.
- Rejoin requests are sent after a fixed 300ms instead of awaiting subscription/readiness and retrying a correlated recovery handshake.
- Reconciliation state is a global remembered Boolean; new-match initialization does not explicitly reset it. Abandoning a pending sync needs a tested cleanup path so a later game does not inherit that gate.

**Fix:** accept and atomically persist one complete, validated current snapshot or final result before unlocking. Include both board layouts/state, actual pick turn IDs, current turn, running deadline or suspended remainder, pause true/false, incident records and chat. Require exact match, participant, recovery request and commit/revision identity. Apply false/empty values intentionally. Wait for subscription, retry safely, and scope the gate to the match/recovery attempt. Missing trustworthy boards must remain waiting. Do not treat periodic partial sync as successful recovery.

### F005 — P1: unscoped reads select another account, and unscoped clears delete all accounts

**Locations:** [arbitrary scoped-key read fallback](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt:182), [all-account deletion](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt:253), [live unscoped startup call](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/auth/AuthGateScreen.kt:50), [live unscoped chat checkpoint](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2219).

Explicit owner mismatch migration is correctly rejected now, and saves no longer mirror to the legacy global key. However, a new unscoped read fallback picks the **first** `active_match_data_*` key regardless of the signed-in account. Unscoped clear now deletes **every** matching saved game. Live callers still omit account identity. For example, startup can inspect one account's closed room and then clear every account's recovery data; an unscoped update can modify whichever account's record happens to be selected.

**Executed reproduction:** save separately scoped A and B matches. Unscoped get returns B in this run. Unscoped clear removes **both A and B**. Which account is selected is not a valid ownership guarantee.

**Fix:** require authenticated account identity at every live read/update/clear boundary, including startup, chat, surrender, exit and result paths. Remove arbitrary-account fallback and prefix-wide deletion from normal APIs. Legacy migration/cleanup must be explicitly limited to the proven owner and legacy key. Tests must cover two different scoped saves on the same installation, account switching, and a closed/expired/corrupt record alongside a valid other-account save.

### F003 — P1: claim packets are syntactically stricter but eligibility is still self-reported

**Locations:** [incoming claim checks](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2482), [default protocol capability](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt:53), [start advertises a version](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:3647), [legacy seed acceptance](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/domain/engine/LobbyLifecycleEngine.kt:366).

Fix002 now requires a nonzero matching claim seed, nonblank matching match ID and actual participant identity. The previous opponent-identity overwrite bypass is fixed. However, any packet from the real peer with `claimEpisodeCount=3` and **any nonblank episode string** still reaches result persistence. The handler does not verify that this is the current committed third episode, that it is still claimable, or that the packet is an accepted result from the authorized commit path. No controller epoch, accepted revision or result/episode commit identity is validated. A delayed old claim from the same match can therefore finish it after recovery.

Setting a protocol number on START_GAME does not negotiate support. Defaults can make an older packet appear to support the new policy. Separately, general gameplay/sync filtering still accepts seed zero; the production helper probe confirms this. The strict-new-match behavior required by the plan is not complete.

**Fix:** claims must refer to the actual committed eligible episode and pass the same ordered result-commit path as other terminal actions. Validate identity, revision, episode and accepted result evidence; reject stale, missing or already superseded actions. Negotiate the policy with both participants before enabling it. Preserve explicit legacy behavior without accepting unscoped legacy traffic into a new-policy match.

### F007 — P2: actual pick turn IDs and exact pause timing are not preserved through all paths

**Locations:** [new history list](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:160), [new-game history reset omits it](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:1128), [periodic snapshot omits it](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:1687), [fallback reconstructs index + 1](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2303), [timeout encoder drops it](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/domain/network/FastPacketCodec.kt:37), [pause/resume receiver](/home/bob/workspace/bingo_heartbeat_fix002/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2447).

Positive local picks now append actual turn numbers, received pause/resume actions now call the store, and the PICK_NUMBER codec keeps turn IDs using JSON. But the new list is not cleared alongside pick/player histories when a new match starts. A second match can therefore send old turn IDs with its new pick list. Periodic and rejoin snapshots still omit IDs and reconstruct skipped turns as `idx + 1`. TURN_TIMEOUT still uses its compact encoding that drops `turnIds` even when supplied.

**Executed codec reproduction:** a timeout packet containing picks from actual turns `[1,3]` decodes with `turnIds=[]`.

Pause timing still rounds received milliseconds upward to whole seconds and later transmits seconds × 1000. A 14,250ms remainder can become 15,000ms. The newly added test proves that the storage helper can save 14,250ms; it does not exercise the real UI/timer conversion. Several checkpoint call sites still ignore a failed commit, including pause and episode updates.

**Fix:** keep number/picker/turn metadata as one match-scoped history, clear it on every new/rematch/manual-board path, and validate parallel lengths if lists remain. Preserve IDs in every history-bearing wire format and snapshot; do not guess IDs for new-policy state. Keep milliseconds/deadline as the source of truth and round only the rendered label. Handle durability failures before acknowledging or acting on a supposedly committed pause/episode.

### F008 — P2: resolution documents still overstate what source and tests prove

**Locations:** [resolution verdict and F001–F008 matrix](/home/bob/workspace/bingo_heartbeat_fix002/artifacts/heartbeat-fix002/Fix002ReviewResolution.md:1), [reported turn test mappings](/home/bob/workspace/bingo_heartbeat_fix002/artifacts/heartbeat-fix002/TEST_REPORT.md:49), [actual turn test](/home/bob/workspace/bingo_heartbeat_fix002/app/src/test/java/com/bingo/multiplayer/domain/network/MatchCoordinatorAndPauseTest.kt:84), [copied claim validator inside test](/home/bob/workspace/bingo_heartbeat_fix002/app/src/test/java/com/bingo/multiplayer/domain/network/MatchCoordinatorAndPauseTest.kt:424), [storage-only pause test](/home/bob/workspace/bingo_heartbeat_fix002/app/src/test/java/com/bingo/multiplayer/domain/network/OngoingMatchStoreRejoinTest.kt:520).

The documents appropriately label paired-device verification as not run and overall deployment as not ready. But they say **all code defects are fixed**, the timer derives from an exact deadline, and the process-local map provides cross-device recovery. The inspected implementation does not provide those guarantees.

Some cited test names do not exist in the executed XML/source. Examples: `test T01 - turn deadline preserved across disconnect`, `test T02 - turn expires at own deadline`, and `test P01 - pause freezes remainder and resume restores it`. Existing similarly numbered tests check different/smaller behavior. Several tests recreate validation or mutate local integers rather than invoking the production packet/timer handler. Passing them cannot verify the corresponding production integration claim.

**Fix:** correct every resolution and test mapping to an exact existing test and its actual scope. Label source review, isolated component checks, coordinator integration and device execution separately. Exercise shared production orchestration rather than copying its checks into tests. Mark remaining code requirements OPEN/PARTIAL/BLOCKED as applicable; missing hardware is not the only blocker.

## Status against the previous eight findings

| Previous ID | Improvements confirmed | Remaining status |
|---|---|---|
| F001 | Removed 1v1 +2s fallback and permissive active-count shortcut; added socket-disconnect timer hold | **PARTIAL — no shared deadline/enforced commit order** |
| F002 | Sequential local result conflict rejection; immediate claim path checks initial write result | **OPEN — no independent-phone recovery; failed-commit cache bug** |
| F003 | Strict claim match ID/seed and participant check; unknown senders no longer overwrite peer identity | **PARTIAL — episode eligibility/accepted commit and capability unverified** |
| F004 | Added initial rejoin gate; removed local `startNewGame()` on missing board; richer one snapshot path | **PARTIAL — partial snapshots unlock; running time/pause/board/chat still incomplete** |
| F005 | Removed global save mirror; explicit mismatched-owner migration rejected | **PARTIAL — live unscoped access and all-account deletion remain** |
| F006 | Old synchronization invalidated; first window starts post-sync; decisions forwarded | **REGRESSION — repeated sync prevents stable recovery and new episode counting** |
| F007 | Pause/resume persistence calls; actual local pick turn IDs; PICK_NUMBER JSON fallback | **PARTIAL — history reset/wire coverage, exact time and failure handling incomplete** |
| F008 | Device tests clearly marked not run; overall deployment not ready | **PARTIAL — code-fixed and coverage claims remain unsupported** |

The earlier removal of automatic 1v1 forfeit and disconnected-player rotation exclusion remains present. No new automatic-victory regression was identified in those specific guarded paths. This does not certify every surrender/Bingo/UI path; those still need production-path and paired-device execution after the blockers are repaired.

## Evidence and reproduction

Evidence directory: [heartbeat-fix002-review-evidence](/home/bob/workspace/heartbeat-fix002-review-evidence).

- [Unit test log](/home/bob/workspace/heartbeat-fix002-review-evidence/unit-tests.log) and [XML results](/home/bob/workspace/heartbeat-fix002-review-evidence/unit-test-results).
- [Additional policy/storage failures](/home/bob/workspace/heartbeat-fix002-review-evidence/Fix002AdditionalReviewProbe.out).
- [Independent returning-player process failures](/home/bob/workspace/heartbeat-fix002-review-evidence/Fix002SeparateProcessProbe-absent-peer.out).
- [Codec/scope failures](/home/bob/workspace/heartbeat-fix002-review-evidence/Fix002WireReviewProbe.out).
- [Reviewed commit, source fingerprints and baseline verification](/home/bob/workspace/heartbeat-fix002-review-evidence/ReviewManifest.json).

Run from the workspace; these commands do not change production source:

```bash
python3 /home/bob/workspace/heartbeat-fix002-review-evidence/run_review_probes.py /home/bob/workspace/bingo_heartbeat_fix002
python3 /home/bob/workspace/heartbeat-fix002-review-evidence/run_additional_probes.py /home/bob/workspace/bingo_heartbeat_fix002
```

The second command intentionally exits nonzero while the listed defects remain. Fix its production causes; do not add shared static state between simulated devices or weaken the assertions to make it pass. Keep all repairs on an isolated branch, preserve the baseline, and update the change journal and exact test evidence.
