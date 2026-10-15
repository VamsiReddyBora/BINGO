# Heartbeat Fix001 re-review

Reviewed **2026-10-15 (Asia/Kolkata)**. Candidate: **`fa3b1ff`** on `fix/heartbeat-fix001`, `/home/bob/workspace/bingo_heartbeat_fix001`. Comparison: reviewed pre-fix candidate `fc8bdf0`. Protected baseline: `a13d39e`, branch `1.4.1`.

**Verdict: improved, but NOT READY. There are remaining code defects, beyond the acknowledged missing device tests.** The statement that all HB01–HB14 code-level repairs are complete is inaccurate.

## What was verified

| Check | Actual result |
| --- | --- |
| Full JVM suite, forced task rerun | **355 tests, 34 suites; zero failures/errors/skips**, successful in 16 seconds |
| Original independent production-policy probe | **All five assertions pass**; the original five observed violations no longer reproduce |
| Additional production recovery-policy probe | **Two failed assertions** |
| Additional production storage probe | **Four failed assertions**, one successful local-write assertion |
| Two-device/physical network execution in this review | **Not run** |
| Application source changes by this review | **None**; only external review/probe/evidence files were created |

The new storage probe calls the actual production store with separate fake SharedPreferences instances to represent independent device storage. It establishes the store's local behavior; it is not a claim of device process-kill or network simulation. Source inspection confirms no shared result-retrieval mechanism completes the absent-peer scenario.

## Confirmed improvements

- Automatic-board start now calls the shared heartbeat activation helper; manual start uses that helper too.
- The specific incoming-timeout three-missed-turn automatic-forfeit path now has an online 1v1 bypass.
- The five earlier policy failures around initial silence and unstable/local recovery are corrected for their original reproduction sequences.
- Rejoin callbacks now share a routine, and connection setup precedes attaching monitoring, fixing the earlier immediate heartbeat teardown.
- The remote resume receiver preserves its frozen seconds until the timer consumes them, fixing the specific 12-versus-30-second reset.
- The `|| isClaimVictoryEligible` override was removed from local claim acceptance.
- Generic non-heartbeat traffic no longer directly calls `onFreshPeerResponse()`.
- Availability transitions now write episode metadata, rejoin restores a policy checkpoint, and scoped storage/local final-result helpers were added.
- The new report explicitly labels paired-device and latency checks unrun. However, it still overstates production integration coverage and several repairs.

These are source-level confirmations and focused test results, not an end-to-end approval.

## Remaining findings

### F001 — P1: Shared turn ownership and deadline timing are still not implemented

**Original findings:** HB07, part of HB08.

**Locations:** [timer initialization](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:4032), [peer timeout fallback](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:4131), [competing synchronization responders](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2871).

The timer still initializes an integer to 30, decrements after `delay(1000)`, and allows peer advancement after an extra two-second fallback. `isHosting || isActingHost || activeRemaining.size <= 2` still permits either 1v1 phone to make decisions. Epoch/revision/turn-ID fields are not used to enforce a controller or commit order in navigation gameplay. Local-unhealthy decisions still disable only claiming, not picks or timeouts.

**Effect:** Both disconnected phones can continue independently; both-offline suspension and safe controller takeover are not established. Rejoin/effect restarts can reset time, and the absent peer's turn can take 32 seconds on the fallback path. A deterministic participant order does not prevent two different histories for the same turn.

**Required fix:** One enforceable controller/ordered commit path, exact match/turn/revision-bound actions, deadline-based timing and a visible safety wait when ownership cannot be established. Do not mark takeover fixed because old deterministic order helpers exist. This is a remaining implementation defect, not merely a device-test blocker.

### F002 — P1: Final results remain local and overwritable

**Original finding:** HB11.

**Locations:** [local result storage](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt:257), [result lookup](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt:286), [claim records stats before checking durability](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:822).

The new tombstone is stored only in that installation's SharedPreferences. Neither its record nor lookup supplies shared result recovery for the absent phone. The claim still sends a non-retained packet; if the winner closes before the loser returns, no live controller remains to supply its in-memory result. The result helper also unconditionally replaces an existing tombstone for the same seed, despite describing it as immutable. Claim handlers ignore its Boolean result and update career/history before ensuring final persistence.

**Executed evidence:** With separate winner/loser preferences, writing the winner's result leaves the loser with no final result and its unfinished saved match still available. A conflicting second result write returns true and changes the stored winner.

**Required fix:** An immutable accepted result with stable result ID, ownership/revision and durable recovery available to both participants; verify persistence before acknowledging the finish or deleting active state. Reject conflicting replacement and make statistics application idempotent. If existing infrastructure cannot provide the shared guarantee, report it BLOCKED instead of fixed.

### F003 — P1: Incoming claim validation still accepts missing identities and can trust a fabricated peer

**Original finding:** HB10.

**Locations:** [sender assignment before dispatch](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:1710), [CLAIM_VICTORY validation](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2415).

The receiver rejects a wrong seed only when seed is nonzero, and a wrong match ID only when it is nonblank. Therefore `seed=0`, blank match ID and a claimed count of 3 can still pass. Episode ID, actual committed episode/count, controller generation, revision and result ID are not verified. Policy capability is not negotiated before start.

The added peer check is also bypassed by existing dispatch logic: any nonlocal packet first assigns `opponentPlayerId = packet.playerId`, then the claim check accepts `packet.playerId == opponentPlayerId`. An unknown sender can satisfy that branch without belonging to immutable participants.

**Effect:** Stale/identity-less or unauthorized room traffic can still finish a current match. Checking a packet's self-reported count is not validation of an eligible committed claim.

**Required fix:** Require nonblank exact active-match identity and a validated final commit from the authorized controller. Check sender against immutable participants before changing any identity state. Require the episode/revision/result evidence, reject missing fields and negotiate policy support explicitly.

### F004 — P1: Unified rejoin still enables stale gameplay before synchronization

**Original findings:** HB06, HB03 and HB07.

**Locations:** [local restore and fresh timer](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:1354), [navigate before delayed sync request](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:1445), [SYNC_REQUEST response payload](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2878).

The common routine loads a local snapshot, sets `isMyTurn`, clears game-over flags, creates a 30-second timer for an unpaused match, navigates to Game and only then sends recovery requests after a fixed delay. It does not await connection/subscription or validated authoritative state before enabling picks. Missing board data still falls back to generating a new game/board.

The synchronization payload carries picked history/current turn/result metadata, but not current deadline/remainder, pause state, complete chat or incident checkpoint. Foreground recovery also still uses the older direct announcements instead of the unified confirmed flow.

**Effect:** A player can act on an obsolete turn before learning what the other player did. A returning phone can lose a remote pause, recover an inaccurate countdown or miss chat sent during absence. A helper being shared by three buttons does not establish complete recovery.

**Required fix:** An explicit recovering state that blocks mutations until the correct match snapshot/final result is validated, atomically applied and persisted. Synchronize all required state, including timing/pause/episodes/chat, and use the same flow for foreground/cold/rejoin entry points. Missing trustworthy data must remain waiting, never regenerate an ongoing match.

### F005 — P1: Account migration can copy A's explicitly owned match into B's account

**Original finding:** HB13.

**Locations:** [every save still mirrors to global key](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt:103), [migration ownership condition](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt:183).

New saves still write `active_match_data` as a global alias. When B has no scoped entry, migration accepts that global record if either the recorded owner matches B **or B appears among its participants**. Thus a record explicitly owned by A can be relabeled as owned by B, retaining A's board and perspective. Optional unscoped reads/updates/clears also remain in production.

**Executed evidence:** Save a match with owner `account_a` and participants A/B, then request it as B. The production store returns a record with owner `account_b` and creates B's scoped key from A's data. The existing outsider-account test misses this participant-account case.

**Required fix:** An explicit owner mismatch must reject migration. Only legacy records lacking an owner may use separately proven perspective/ownership migration. Require account identity at every storage boundary and eliminate the global alias as a live shared write target. Do not adopt another participant's local board as the signed-in player's board.

### F006 — P1: Recovery still closes before the agreed synchronization/stability requirements

**Original finding:** HB04.

**Locations:** [recovery begins on first response](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicy.kt:300), [sync completion](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicy.kt:359), [transport discards synchronization decision](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt:250).

The original gap checks are improved. However, the stability start remains the first ACK, not completion of synchronization. If ACKs are stable for 10 seconds while synchronization is pending, the final `onStateSynchronized()` immediately closes the episode instead of beginning the required 10-second post-sync window. A prior synchronization flag is also not invalidated for a new peer outage, so ACKs alone can close it without reconciling that outage's match state.

**Executed evidence:** Both scenarios close to `RESPONSIVE`, `episodeOpen=false`, while the new recovery requirement is still unmet. These are two additional failed assertions against the production reducer, after all five original checks passed.

There is an integration problem too: `notifyStateSynchronized()` discards the decision returned by the policy. If that call closes recovery, it emits no `RecoveryCompleted` event, so the decision-based UI/checkpoint collector can miss the transition.

**Required fix:** Track synchronization completion for the current recovery generation, invalidate old synchronization when required, start the stable window after current sync completes, and publish/persist every accepted transition through one event path. Verify the observer that sends recovery state can also receive the acknowledgment needed to complete its own recovery policy.

### F007 — P1: Received pause state and timing/turn metadata are still not durably recovered

**Original findings:** HB03, HB08 and HB12.

**Locations:** [pause/resume receiver](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2393), [availability-only checkpoint update](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:4193), [history reconstructs turn from pick index](/home/bob/workspace/bingo_heartbeat_fix001/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2283).

Remote pause/resume changes update only in-memory UI fields; those handlers do not save the accepted pause/timing state. Availability checkpoints update only policy fields. Normal pick saves do not populate actual `turnIds`, and reconstruction still uses `idx + 1`, losing the true turn number after skips. Timing is still integer seconds, and save failures remain ignored in live calls, with synchronous preference commits running on the UI path.

**Source-derived reproduction:** A pauses the match, B receives it, then B is killed before any other state write. B's saved match can still be unpaused. Rejoin loads that checkpoint, and the sync response does not carry the missing pause state. The same pattern applies to received resume.

**Required fix:** Persist every accepted state change, including received pause/resume and exact timing/pick-turn metadata, through one durable state writer. Restore and synchronize that state, perform disk I/O off the UI thread, check failures and withhold acceptance on failed durability.

### F008 — P2: Fix001 still labels missing production-path tests as integration PASS

**Original finding:** HB14.

**Locations:** [resolution claims](/home/bob/workspace/bingo_heartbeat_fix001/artifacts/heartbeat-fix001/Fix001ReviewResolution.md:24), [count-only claim test](/home/bob/workspace/bingo_heartbeat_fix001/app/src/test/java/com/bingo/multiplayer/domain/network/MatchCoordinatorAndPauseTest.kt:405), [unchanged local pause tests](/home/bob/workspace/bingo_heartbeat_fix001/app/src/test/java/com/bingo/multiplayer/domain/network/MatchCoordinatorAndPauseTest.kt:143).

The report now correctly marks device work unrun, but still marks HB07 fixed and production-path integration PASS. There is no new production coordinator enforcing ownership, and tests still exercise local assignments, manually invoked policy methods and copied validation predicates. The new HB10 test compares a packet's count with 3; it does not call the real receiver, so it misses F003. The new freshness test creates a local challenges map and explicitly does not feed generic traffic into transport.

The report's claim that all recovery paths are verified by JUnit/Robolectric is also unsupported: the test sources/build do not contain a Robolectric integration setup exercising these UI flows.

**Required fix:** Update the resolution matrix to reflect partial/unfixed code behavior now. Extract or expose real production adapters/coordinator for clock/transport/storage injection, then exercise the actual startup, receiver, rejoin and persistence paths. Keep unit successes scoped accurately; do not infer integration correctness from them.

## HB01–HB14 status after Fix001

| Original ID | Re-review status | Scope of conclusion |
| --- | --- | --- |
| HB01 | **Fixed in source; device verification pending** | Automatic/manual activation helper is now wired. |
| HB02 | **Fixed for the reported timeout path; device verification pending** | The incoming three-missed-turn result path is guarded for 1v1. |
| HB03 | **Partial** | Episode writes/restoration added; complete durable/shared checkpoint handling is still missing. See F004/F007. |
| HB04 | **Partial** | Original five-check probe passes; two further recovery assertions fail. See F006. |
| HB05 | **Original failure fixed in reducer** | First silence no longer remains in SYNCING forever. This does not prove full startup readiness. |
| HB06 | **Partial** | Common routine and connection/monitor order fixed; recovery-before-play contract remains unmet. See F004. |
| HB07 | **Not fixed** | Independent timers/authorization paths remain. See F001. |
| HB08 | **Partial** | Remote remainder reset corrected in seconds; exact durable synchronized timing is absent. See F001/F007. |
| HB09 | **Reported UI override fixed** | Local claim no longer succeeds solely from stale UI eligibility. Broader claim authority remains unresolved. |
| HB10 | **Partial** | Online 1v1/count/optional seed checks added, but missing identity and peer validation remain unsafe. See F003. |
| HB11 | **Partial; required absent-peer recovery fails** | Local final record added, but shared/immutable final recovery is not solved. See F002. |
| HB12 | **Generic freshness bypass fixed; sync integration partial** | Generic packet shortcut removed; current sync lifecycle/decision propagation still needs correction. See F006/F007. |
| HB13 | **Partial; production store failure reproduced** | Scoped keys added, but migration can adopt another account's record. See F005. |
| HB14 | **Partial** | Device test labels improved; all-code-fixed/integration claims still overstate evidence. See F008. |

## Evidence and repeatability

- [Original five-check probe output on Fix001](heartbeat-fix001-review-evidence/HeartbeatPolicyReviewProbe.out)
- [Additional recovery failures](heartbeat-fix001-review-evidence/Fix001RecoveryReviewProbe.out)
- [Additional storage failures](heartbeat-fix001-review-evidence/Fix001StorageReviewProbe.out)
- [Recovery probe source](heartbeat-fix001-review-evidence/Fix001RecoveryReviewProbe.java)
- [Storage probe source](heartbeat-fix001-review-evidence/Fix001StorageReviewProbe.java)
- [Probe runner](heartbeat-fix001-review-evidence/run_review_probes.py)
- [JVM rerun log](heartbeat-fix001-review-evidence/unit-test-rerun.log)
- [Test XML results](heartbeat-fix001-review-evidence/unit-test-results/)
- [Review/source manifest](heartbeat-fix001-review-evidence/ReviewManifest.json)

Compile the candidate's debug/unit-test classes, then run:

```bash
python3 /home/bob/workspace/heartbeat-fix001-review-evidence/run_review_probes.py /home/bob/workspace/bingo_heartbeat_fix001
```

The runner invokes the compiled production policy/store, exits nonzero for the reproduced failures and saves outputs. The source-only findings still need their own production-path/device regressions; these probes are not substitutes for them.

## Review limits and baseline

This re-review did not install or play the APK, run paired devices, measure latency, fix source, publish anything or alter protected branches. A successful unit suite and the repaired original probe are useful progress; the remaining code defects prevent approval independently of the missing device work.

Keep working from the repair branch and retain both earlier checkpoints. The restored baseline remains the rollback reference. Correct the resolution report before asking another session to rely on its all-HB-fixed statement.
