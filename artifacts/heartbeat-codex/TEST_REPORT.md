# Heartbeat repair — verification report

Prepared: **2026-10-15T17:09:07+05:30**. Candidate branch: `fix/heartbeat-codex`.

**Overall: NOT READY for the complete requested behavior.** Fixed-host safety and recovery have production-code corrections and measured evidence. Guest gameplay/claim/surrender confirmation while the original host is unavailable remains BLOCKED. Android paired-device UI verification and baseline latency measurement remain unrun.

## Executed checks

| Check | Result | Evidence |
|---|---|---|
| Fresh final full unit run | **398 tests / 39 suites; 0 failures, errors or skips** | `evidence/final-unit-tests.log`, `evidence/unit-test-results/` |
| Application build, lint and release | Successful; **0 lint errors/fatal, 34 warnings**. Full build also ran 396 tests before the two test-only boundary additions. | `evidence/final-build.log`, `evidence/lint-results-debug.xml` |
| Newly added production regressions | **36 test methods**; 34 in implementation +2 exact heartbeat boundary follow-up tests | [Exact names](evidence/RegressionTestIndex.md) |
| Real production clients over live MQTT | Negotiation, eight alternating picks and retained final recovery in an independent returning JVM after winner JVM exit pass | `evidence/mqtt-writer.log`, `mqtt-reader.log`, `mqtt-results.json` |
| Retained storage semantics | Existing subscription resubscribe returns exact record flagged RETAIN | `evidence/mqtt-retained-readback.log` |
| APK | Release build/signature verify succeeds; same signing certificate as baseline | `evidence/apk-verification.txt`, `ReviewManifest.json` |
| Baseline |159/159 source fingerprints unchanged; protected/shared APK hashes unchanged | `ReviewManifest.json` |
| Android gameplay | **NOT RUN**: existing AVDs failed to reach usable boot-complete UI without KVM | `evidence/device-attempt.md`, emulator logs |

Application implementation commit: `d4abaac26a06004b97b97a26fb936907ef782126`. Test-only follow-up: `194b34b186f7d18af29b1d9e1dc9365593ae6930`. No application source changed after the signed APK build. Historical intermediate failing logs remain, clearly named; they are not final test results.

## Evidence scope and reproduction

The new tests exercise actual production coordinator, client, policy, wire codec, ongoing store and statistics repository classes through explicit injected clocks/transports/preferences. The rest of the existing suite also passes, but some older simulation tests use models: their count alone does not prove Android wiring. Compose/OS behavior is source-reviewed and compiled, not executed here.

The live probe uses `LiveMqttRecoveryProbe.kt` with the real `ReliableMatchClient`, JSON serialization and Paho. Writer and returning-reader run as separate JVM processes with independent local device directories. Only MQTT transfers the final result. The third outage is a prepared fixture, not three real Android disconnects. Unique verification topics were cleaned up; no real user was invited and no existing room data was changed.

Run from this checkout (SDK/credentials must be configured as in the project; never print them):

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease :app:exportCodexVerificationClasspath \
  -I artifacts/heartbeat-codex/verify-classpath.init.gradle \
  --rerun-tasks --offline --console=plain --max-workers=2
python3 artifacts/heartbeat-codex/run_live_mqtt.py
```

Preserve previous live-probe outputs before rerunning: the runner replaces its own `mqtt-*.log` and fixtures. The original review probes that depended on a fake shared local file are not used as cross-phone proof. Their earlier failing evidence remains outside this checkout. Failed-publish/recovery cases now test the actual client protocol.

## Measured latency

The live test observed request-to-both-clients acceptance over eight alternating picks: **median 503ms, p95 554ms**, raw `[508,554,201,503,201,503,251,553]`ms. Resolution 50ms; p95 is the nearest-rank maximum for eight samples. This measures headless workspace→broker→clients, not physical input/render latency. No baseline comparison was performed, and no assertion of unchanged latency is made. Guest consistency requires an authoritative round trip.

## Original 44-case mapping

`PASS (reducer/component/storage/live MQTT)` applies only to that evidence layer. `PARTIAL` means some assertions executed while the complete requested scenario remains unrun or incomplete. `SOURCE ONLY` is inspection, not a behavioral pass. `BLOCKED` identifies missing functionality. No table entry substitutes for paired-device evidence.

| ID | Required scenario | Status | Actual evidence / remaining check |
|---|---|---|---|
| H01 | Healthy heartbeat traffic | PARTIAL | Heartbeat H01 reducer passes; Android probe-job/collector count and popup absence unrun. |
| H02 | Silence 14,999 ms, then 15,000 ms | PASS (reducer) | Heartbeat H02: 14,999/15,000ms fake-clock boundary. |
| H03 | Healthy 2.5-second replies with occasional dropped probes | PASS (reducer) | Heartbeat H03: freshness duration, not missed-packet counting. |
| H04 | Continuous silence 45 seconds and five minutes | PASS (reducer) | Heartbeat H04: one episode at 45s/five minutes; coordinator has no auto-result after 20 expired turns. |
| H05 | Two separate outages after stable recovery | PASS (reducer) | Heartbeat H05 plus duplicateSyncDuringLateRecoveryDoesNotMoveTheStableWindowInEitherCallbackOrder. |
| H06 | Third separate outage | PARTIAL / BLOCKED | Heartbeat H06 and committed-episode claim regression pass for the host; guest claim blocked, UI unrun. |
| H07 | Return before 15 seconds | PARTIAL | Heartbeat H07 reducer passes; Android popup presentation unrun. |
| H08 | Return, synchronize, disappear before 10 stable seconds | PASS (reducer) | Heartbeat H08 keeps one incident through unstable recovery. |
| H09 | Recovery stable-gap > 5 seconds | PASS (reducer) | Heartbeat H09 and exact 5,000/5,001ms gap regression pass. |
| H10 | Duplicate/old/queued heartbeat, wrong match/account/generation | PARTIAL | Heartbeat codec/context, stale application ACK and incomplete/protocol rejection tests pass; all Android queued-message paths unrun. |
| H11 | New connection with old Last Will arriving late | PARTIAL | Generation invalidation codec test passes; actual delayed Android Last Will delivery unrun. |
| H12 | Local device loses network / broker stops responding | PARTIAL | Heartbeat H12, failedPublicationKeepsPendingStateAndBlocksMutationsUntilBrokerRecovery; real network-toggle UI unrun. |
| H13 | Broker-wide outage and return | PARTIAL | Injected transport/publication failure reconciles; broker-wide service outage/restart unrun. |
| H14 | Slow cloud request taking > 15 seconds | SOURCE ONLY | Gameplay/heartbeat uses MQTT and cloud calls are outside the controller; no >15s cloud-delay runtime injection. |
| H15 | Repeat all outage cases with original host and guest swapped | BLOCKED | Host continues with guest absent. Guest waits when host absent; no ownership transfer implemented. |
| T01 | Disconnect 10 seconds into a 30-second turn | PARTIAL / BLOCKED | Production monotonic timing/pause tests pass with host active; host-absent guest countdown cannot continue. |
| T02 | Disconnect with < 15 seconds remaining | PARTIAL / BLOCKED | Expiry uses coordinator deadline independently of diagnostics; phone lifecycle fixture unrun, host availability blocked. |
| T03 | Several unavailable turns | PARTIAL / BLOCKED | unavailablePlayerKeepsItsNormalTurnsWithoutAutomaticDefeat passes 20 expiries; assumes active original host. |
| T04 | Pick and timeout arrive at same boundary | PASS (component) | pickTimeoutRaceHasOneAcceptedOutcomeInBothOrders. |
| T05 | Delayed/duplicate timeout after next turn begins | PASS (component) | duplicateTimeoutNeverAdvancesTheNextTurn. |
| T06 | Several skipped turns followed by picks and rejoin | PASS (component) | fullHistoryKeepsRealTurnsAndRejectsMissingBoardOrStalePause; storage/wire turn identity tests. |
| P01 | Pause with exactly 12,000 ms left | PARTIAL | Exact state tested at 14,250ms across coordinator/wire, stronger precision than whole seconds; paired 12,000ms screen fixture unrun. |
| P02 | Open/dismiss popup or select Continue | SOURCE ONLY | Popup/dismiss has no accepted mutation; Continue/Resume enters coordinator. Android popup interaction unrun. |
| P03 | Return while deliberately paused | PARTIAL | exactPauseSurvivesWireAndResumeWithoutThirtySecondReset and lateUnpausedSnapshotCannotClearADeliberatePause; Android restore UI unrun. |
| P04 | Duplicate/stale pause/resume requests | PASS (component) | Stale/duplicate sequence and pause regressions reject old accepted context. |
| C01 | Claim on incidents 1 or 2, including forged/stale UI request | PASS (component) | claimRequiresTheActualCurrentThirdOpenEpisodeAndHealthyOwner; forgedClaimAndStaleSyncAckCannotCreateAFinalResult. |
| C02 | Eligible third-incident claim, confirmed once | PARTIAL / BLOCKED | Host claim + durable result + atomic statistics tested at component level; live probe uses a prepared third-episode fixture. Guest claim blocked. |
| C03 | Fresh recovery races with claim confirmation | PARTIAL | Heartbeat C03 and committed-episode fresh/unreachable guards pass; Android confirmation race unrun. |
| C04 | Claim races with Bingo/surrender; duplicate delivery/restart | PARTIAL | Canonical terminal/replay and statistics restart tests pass; complete RootNavGraph race/kill UI unrun. |
| R01 | Home, app switching, screen lock, swipe-away | NOT RUN | No usable Android UI: home/switch/lock/swipe actions not executed on candidate. |
| R02 | OS process kill and Android force-stop | NOT RUN | No candidate Android OS-kill/force-stop test. Separate JVM restart is not Android lifecycle proof. |
| R03 | Kill between save, publish and acknowledgment | PARTIAL | Pending publication/acceptance failure and retry tested in production client; actual OS kill at every boundary unrun. |
| R04 | Both apps close; reopen after > 8 minutes | PARTIAL | Durable 1v1 no-TTL test and restartedOwnerSuspendsInsteadOfReplayingTimeoutBacklog pass; paired >8min Android run unrun. |
| R05 | Both reopen together / one returns with an older snapshot | PARTIAL | Separate-store recovery, monotonic snapshot and no-promotion tests pass; simultaneous Android cold start unrun. |
| R06 | Controller and observer become separated/asymmetric delivery | BLOCKED | guestCannotPromoteItselfWhenHostStopsResponding proves safety wait. Availability during host absence remains missing. |
| R07 | Board/manual board, picks, chat and pause after reconnect | PARTIAL | Complete boards, actual turns, chat and pause tests pass; manually entered boards/UI restore unrun. |
| R08 | Restart after incident 2 / incident 3 | PARTIAL | OngoingMatchStoreRejoinTest committed episode persistence and policy recovery pass; full process/observer wiring unrun on Android. |
| R09 | Switch accounts; rematch; late old-match packets | PARTIAL | Account isolation, no ownerless adoption, late checkpoint/match and protocol context tests pass; navigation/account switching UI unrun. |
| R10 | Duplicate collectors/recomposition and rapid reconnect cycles | PARTIAL | twentyReconnectCyclesForEachRoleKeepOneHistoryAndNeverInventAResult: 40 protocol cycles; Compose recomposition and Android jobs unrun. |
| R11 | Wall-clock changes and device reboot | PARTIAL | Heartbeat R11 and restarted-owner monotonic remainder pass; real device reboot unrun. |
| G01 | Normal online picks/Bingo/explicit surrender | PARTIAL | completeLegitimateBingoMatchUsesTheAcceptedWinningPickAndCannotRestart and surrenderAndConflictingTerminalActionsProduceOneResult pass; Android UI unrun. |
| G02 | AI, LAN, group lobby/game and all restored social/UI features | PARTIAL | Existing regression suite passes; representative AI/LAN/group/social device smoke remains unrun. |
| G03 | Mixed old/new clients | PARTIAL | ReliablePolicyAgreementTest capability/codec regressions pass; runtime HELLO rejects missing version. Mixed APK UI start unrun. |
| G04 | Latency | NOT RUN | Eight live MQTT samples exist; no same-setup baseline/candidate physical tap benchmark. |

## Fix003 additional 35-case mapping

| ID | Required scenario | Status | Actual evidence / remaining check |
|---|---|---|---|
| N01 | Recovery with full/partial sync and ACKs every 2.5s for 20s | PASS (reducer) | duplicateSyncDuringLateRecoveryDoesNotMoveTheStableWindowInEitherCallbackOrder; protocol ACK regression separate. |
| N02 | New silence after N01, then another complete recovery/outage | PASS (reducer) | Same regression demonstrates incidents 1→2→3 after stable recovery; continuous outage test keeps one. |
| N03 | Unscoped production lookup with A/B saved | PASS (storage) | Unscoped lookup rejected in ownerlessHostCheckpointIsNotAdoptedByGuestAndLegacyDataIsPreserved; scoped isolation tests also pass. |
| N04 | Normal cleanup with A/B saved | PASS (storage) | scopedCleanupNeverRemovesTheOtherAccountsCheckpoint. |
| N05 | Required result commit returns false | PASS (component) | failedRequiredCommitBlocksFurtherMutationAndLeavesAcceptedState; publication/acceptance fault and stats-receipt failure tests. |
| N06 | Repeat N05 with identical result and all writes failing | PASS (component) | Publication fault remains pending and blocks repeated requests; atomic preference failure remains retryable without a false receipt. |
| N07 | B absent; A accepts result and exits; B starts separately | PASS (live MQTT) | Separate writer and reader JVMs, separate local directories; writer exits before independent reader recovers retained final. |
| N08 | Resume attempt after N07 | PASS (live MQTT) | Reader attempts PICK after final recovery; final and accepted picks unchanged. |
| N09 | Encode/decode timeout carrying picks at turns [1,3] | PASS (component) | Actual turn [1,3] production coordinator history plus existing JSON timeout/turnIds wire tests. |
| N10 | Missing/zero seed or match identity in a new-policy session | PARTIAL | Production receiver rejects missing room/seed/protocol; explicit wrong-protocol/incomplete tests pass. Not all malformed fields independently parameterized. |
| N11 | Silence at 14,999ms and 15,000ms; absence 45s and 5min | PASS (reducer) | Heartbeat H02/H04 exact threshold and long continuous silence tests. |
| N12 | Late sync at 26s, ACKs and duplicate sync through 36s | PASS (reducer) | Late synchronization fixture: sync at26s, duplicate sync/ACK callbacks, open at35,999ms and complete at36,000ms, both callback orders. |
| N13 | Stable gaps 5,000ms versus 5,001ms | PASS (reducer) | recoveryGapOf5000MillisPreservesStabilityBut5001MillisRestartsIt. |
| N14 | Wrong/old connection or recovery ACK; delayed Last Will | PARTIAL | Generation/ACK tests pass; delayed actual Last Will Android test unrun. |
| N15 | Disconnect 10s into a 30s turn; repeat at <15s remaining | PARTIAL / BLOCKED | Monotonic deadline verified in coordinator; Android timed-disconnect fixtures unrun and guest continuation blocked. |
| N16 | Duplicate expiry and late old-turn expiry | PASS (component) | duplicateTimeoutNeverAdvancesTheNextTurn: duplicate accepted ID idempotent, distinct stale ID rejected, next turn30,000ms. |
| N17 | Pick races timeout under both delivery orderings | PASS (component) | pickTimeoutRaceHasOneAcceptedOutcomeInBothOrders. |
| N18 | Host and guest each lose connectivity; asymmetric partition | BLOCKED | Fixed-host safety verified; no fenced transfer or host-absent guest availability. |
| N19 | Both apps close >8min and return together | PARTIAL | No-TTL storage and restarted-owner no-backlog tests; simultaneous >8min Android cold return unrun. |
| N20 | Rejoin with authoritative 12,000ms running remainder | PASS (component) | restartedOwnerSuspendsInsteadOfReplayingTimeoutBacklog restores12,000ms after600,000ms inactive. |
| N21 | Pause at 14,250ms; resume and next normal turn | PASS (component) | pauseResumePreserves14250MillisAndNextTurnIsExactly30000 plus actual wire pause/resume regression. |
| N22 | Remote pause/resume accepted, then immediate process kill | PARTIAL | Every accepted remote state persists before unlock; pause wire/storage tests pass. Immediate Android process kill unrun. |
| N23 | Saved pause true, current snapshot false; reverse it | PASS (component) | Wire pause/resume, lateUnpausedSnapshotCannotClearADeliberatePause, duplicate-sequence conflict rejection. |
| N24 | Missing local/manual board and messages sent during absence | PARTIAL | chatUsesAcceptedStableIdsAndSurvivesASeparateReturningClient and inconsistentSavedBoardRemainsWaitingInsteadOfCrashingOrGeneratingANewBoard; manual board Android scenario unrun. |
| N25 | Subscription delayed; request/reply/ACK dropped or duplicated | PARTIAL | Correlated stale/delayed/duplicate ACK, publication retry and missing snapshot cases pass; full subscription-delay/drop matrix unrun. |
| N26 | Exit while recovery pending; start AI/LAN/new online/rematch | PARTIAL | close/leave cancels client; stale match/account storage guard tested. Actual menu→AI/LAN/rematch navigation unrun. |
| N27 | Match 1 history then match 2; skipped turns before picks | PARTIAL | Actual skipped-turn IDs and guarded match writes tested; complete Android rematch/restart flow unrun. |
| N28 | A/B account switching, startup cleanup and late A callback | PASS (storage) | ReliablePersistenceTest and account-bound ReliableMatchStatisticsTest; no adoption, other-account cleanup isolation, late old-match write rejected. |
| N29 | Claim incidents 1/2, invented third or stale episode | PASS (component) | Committed current third-episode and forged/stale claim regressions reject invented evidence. |
| N30 | First fresh response races eligible claim confirmation | PARTIAL | Fresh-response policy revocation and coordinator episode guards tested; RootNavGraph confirmation ordering not device tested. |
| N31 | Claim/Bingo/surrender race, conflict and restart/replay | PARTIAL | Canonical Bingo/surrender/claim and statistics idempotency components pass; Android crash/race matrix unrun. |
| N32 | New/old policy capability combinations | PASS (component) | Both ReliablePolicyAgreementTest methods plus actual protocol negotiation; old defaults unsupported. |
| N33 | Pause/availability/chat/pick writes and storage fault interleave | PARTIAL | Publication/acceptance faults, conflicting duplicate state, checkpoint CAS and stats memory-on-failed-commit tested; exhaustive interleavings unrun. |
| N34 | Recomposition/rapid reconnect, repeated 20 times per role | PARTIAL | 40 actual client-protocol reconnect cycles pass (20 per role); recomposition and full Android observers unrun. |
| N35 | Same-setup baseline/candidate online pick latency | NOT RUN | Real MQTT median/p95 recorded; same-device baseline touch benchmark unavailable. |

## Required next verification

Supply the MQTT authority/ownership capability needed for host-absent guest play, then execute the full paired-device simulation plan with both roles. Record action timestamps, both screenshots, accepted revision/turn and exact remainder; include notification actions, timer/button colors, chat/manual board preservation, process kills, offline recovery, surrender/Bingo, statistics and the same-setup baseline latency comparison. AVD attempts and component cycles do not close these items. Runtime transition-trace evidence from actual Android devices has not been collected.

The [repair review](RepairReview.md) explains the remaining functional blocker and maps every review finding to its actual implementation. The [ownership/storage assessment](OWNERSHIP_AND_STORAGE.md) states infrastructure assumptions and retention limits.
