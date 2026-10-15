# HeartbeatImplementationPlanFix001

Prepared **2026-10-15 10:55 IST**. Copy this entire prompt to the implementing agent.

## Instruction

Fix the current Bingo 1v1 heartbeat implementation against **all 14 findings, HB01–HB14**, in `HeartbeatImplementationReview.md`. Complete this as **one delivery**. You may divide the work into internal, tested commits; do not present a new phased roadmap or stop after implementing helpers that are not connected to the app.

The current implementation is **NOT READY**. Its 346 passing JVM tests do not prove its claimed device behavior. Independent checks against the production reducer reproduced five failed assertions. Keep the useful transport/policy/storage work, correct its integration and add tests that exercise actual production paths.

This prompt supplements `HeartbeatImplementationPlan.md`; it does not replace the agreed behavior. If earlier journal/report statements conflict with the review, investigate the source and evidence. Do not repeat unsupported PASS/READY claims.

## Read these inputs first

- `/home/bob/workspace/HeartbeatImplementationPlan.md` — agreed feature specification and 44 acceptance cases.
- `/home/bob/workspace/HeartbeatImplementationReview.md` — bugs, locations, effects and reproductions.
- `/home/bob/workspace/heartbeat-review-evidence/` — production-class probe, initial failures, runner, actual test log/XMLs and review manifest.
- Current candidate's `AGENT_HANDOFF.md`, `artifacts/heartbeat-1v1/CHANGELOG.md` and `TEST_REPORT.md` — implementation history; their claims need correction.

The review's line numbers refer to candidate commit **`fc8bdf0ef460e29dc2e8e410fe41ae81ffd606bd`**. Re-find by symbol after edits. `RoomMessagePacket` is declared inside `OnlineRoomSyncManager.kt`; do not assume there is a separate production `RoomMessagePacket.kt` file.

## Preserve both checkpoints and work in isolation

| Reference | Purpose |
| --- | --- |
| `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9`, branch `1.4.1` | Protected restored/clean baseline; never change or force-push it |
| `fc8bdf0ef460e29dc2e8e410fe41ae81ffd606bd`, branch `feature/heartbeat-1v1` | Reviewed pre-fix candidate; preserve it for comparison |
| `c01252767d96185e503f9b9dcefa11f1cc1fc02d`, tag `v1.4.1` | Older original release; do not confuse it with the restored baseline |

Inspect current HEAD, worktrees and dirty files. If the candidate has advanced, inspect those changes and adapt the fixes without erasing work or reintroducing already fixed bugs. Otherwise create the repair worktree from the exact reviewed candidate:

```bash
git -C /home/bob/workspace/bingo_multiplayer worktree add -b fix/heartbeat-fix001 /home/bob/workspace/bingo_heartbeat_fix001 fc8bdf0ef460e29dc2e8e410fe41ae81ffd606bd
```

If a branch/path already exists, inspect and preserve it; use an unused name when needed. Work only in the repair checkout. Do not reset another checkout, delete untracked files, rewrite history or push `main`, `1.4.1` or release tags. A release build does not authorize publication; the older repository rule to push builds to `main` is overridden by the user's baseline-protection instruction.

Preserve `/home/bob/workspace/bingo_multiplayer/Bingo.apk`, SHA-256 **`6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc`**. The shared `/home/bob/workspace/Bingo.apk` was already overwritten with the earlier candidate; record its current hash and leave it alone during this repair. Deliver a separate Fix001 APK inside the repair worktree's artifacts.

Preserve AI, LAN, online group games, lobbies, social features, QR, statistics, scorecards, chat/typing, settings, sounds and signing configuration. New policy activation must be based on the immutable two-player online match and an explicitly negotiated policy version, never on a lobby list shrinking to two players.

## Behavior that must remain exact

- Send a fresh match heartbeat probe every **2,500 ms**, including while deliberately paused. Send the first probe immediately after the connection/subscription and match context are ready.
- Declare the known peer unavailable after **15,000 ms** without a verified fresh response, while the observer has a healthy foreground/broker/subscription receive path.
- Count **one incident per continuous outage**, per affected player and match. Forty-five seconds offline is one incident, not three.
- Re-arm for another incident only after completed state synchronization followed by **10,000 ms** of stable fresh heartbeats, with no gap greater than **5,000 ms**. Preserve the cumulative count across rejoin/restart.
- Incidents 1 and 2 offer **Pause game / Continue countdown**. Incident 3 and later also offer **Claim victory**, only while that opponent remains unavailable and the observer is healthy.
- A fresh verified response immediately revokes claim eligibility. Rejoining does not automatically resume a deliberate pause.
- Disconnection and missed turns never automatically award a result. Explicit surrender, legitimate Bingo and an explicitly accepted eligible claim remain valid finish paths.
- Each turn is **30,000 ms**; timeout advances it exactly once. Pause freezes the exact remainder. Recovery, dialogs, duplicate packets and background transitions must not reset or accelerate the countdown.
- Both absent: preserve accepted state and suspend advancement. No backlog of unattended timeout events is replayed on return.
- No switch to AI during online recovery. No silent eight-minute deletion of unfinished new-policy 1v1 matches.

## Required repairs, mapped to the review

All production paths below are relative to `app/src/main/java/com/bingo/multiplayer/`.

### HB01 — Activate the monitor for every real match-start path

**Locations:** `presentation/navigation/RootNavGraph.kt`: `startNewGame()` near 1009, manual countdown near 1249, host automatic start near 3727; guest `START_GAME` handling.

`startNewGame()` currently stops monitoring without restarting it. Centralize match attachment/activation after connection readiness and immutable participant identity are established. Invoke it for automatic boards, manual boards and rematches on both phones. Starting the same active match must be idempotent. Teardown cancels only that match's old jobs/callbacks.

**Required regression:** Start a normal automatic-board match through the production start adapter, without manually invoking heartbeat setup from the test. Stop the peer's responses: one incident and popup after the qualified 15 seconds. Repeat for manual mode, host/guest and rematch. Assert one effective probe/watchdog job, not merely a packet object.

### HB02 — Remove every new-policy automatic-forfeit path

**Locations:** `RootNavGraph.kt`: incoming `TURN_TIMEOUT` near 1894–1932, countdown/departure handlers, `calculateNextTurnPlayerId()`, outcome evaluation.

For negotiated online 1v1, bypass the remaining `activeRemaining.size <= 1 && misses >= 3` result path and any equivalent transport-loss result logic. Keep both immutable participants in turn order. A missed turn is not a heartbeat incident. Do not use temporary availability to remove a participant from ordinary victory qualification or manufacture a result.

**Required regression:** Deliver at least three missed turns through the actual timeout handler with no valid claim: no game over, no career/history write, no recovery-state deletion. Also repeat a long single outage and verify its incident count stays one. Existing group/LAN policies must retain their prior scope.

### HB03 — Persist and restore the actual policy checkpoint

**Locations:** `RootNavGraph.kt` availability collector near 4274, pause helper near 705, pick/checkpoint calls and rejoin code; `domain/network/OngoingMatchStore.kt`.

Commit availability changes through the same serialized match state as gameplay, then durably store per-player count, episode ID/open flag and recovery state. Include actual pick turn IDs, pause/timing state, match identity, revision and controller generation. Restore the policy itself before claims/observation; setting a remembered UI count is insufficient.

Handle failed writes explicitly. Do not acknowledge unsaved progress, silently ignore a false `commit()` result or perform blocking disk persistence on the UI thread. Ensure update APIs can deliberately clear nullable episode/pause fields rather than always keeping the old value through `?:`.

**Required regression:** Create two real availability incidents without pressing Pause, persist/recreate the production coordinator and verify count 2/open episode identity. A later independent outage becomes 3. Kill around save/publish/ACK boundaries, inject write failure and verify no acknowledged progress disappears. Save picks after skipped turns and preserve their actual turn IDs.

### HB04 — Correct the recovery state machine

**Locations:** `domain/engine/OnlineMatchHeartbeatPolicy.kt`: recovery completion near 238, response handling near 291–319, local restoration and state-sync methods.

Use one completion predicate across tick, ACK and sync callbacks. Ten elapsed seconds alone do not prove stability. Require current synchronization, stable verified responses for the full window and a current gap no greater than five seconds. Start that window no earlier than completion of state synchronization. Reset it when continuity breaks.

When local health returns with an open episode, retain its identity/count and route fresh responses through recovery. Do not jump to `RESPONSIVE` while leaving an episode permanently open. Do not restore old elapsed timestamps across unrelated process/boot generations.

**Required regressions:** One fresh ACK plus sync followed by ten silent seconds must not close the episode; at 15 seconds it remains the same incident. After local health loss/restoration, sync and 20 seconds of responses every 2.5 seconds must close/re-arm the old episode; a later qualified outage increments exactly once. Add the exact five-second and 10-second boundary cases.

### HB05 — Establish an observation window without requiring the missing response

**Locations:** `OnlineRoomSyncManager.kt` `startMatchHeartbeat()` near 176; policy `SYNCING` and local-health restoration.

The current policy can stay `SYNCING` forever if no first ACK arrives. Initialize a bounded observation window after proving local health and accepted match context. A known participant can then become unavailable after 15 seconds even if it never answers a first probe. Keep genuinely unknown/unsynchronized match data in an explicit waiting state; do not make all uninitialized policy objects assume they are healthy.

**Required regression:** Enter via the real transport/coordinator startup and via local reconnection with a valid accepted checkpoint. Keep the broker receive path healthy but send no peer ACK. Verify the qualified 15-second outcome. A test that manually calls an initializer the app never invokes does not satisfy this requirement.

### HB06 — Unify every rejoin entry point and connection lifecycle

**Locations:** `RootNavGraph.kt`: main-menu rejoin near 2853, join-room recovery near 3258, lobby rejoin near 3470; foreground recovery near 420; `OnlineRoomSyncManager.connectToRoom()` near 391.

All routes must call one production recovery routine: identify account/match, connect and subscribe, reconcile the accepted checkpoint/final result, restore policy and timing, attach monitoring, then enable legal moves. The main-menu path currently omits the new state. The lobby path starts monitoring before `connectToRoom()`, whose initial `disconnect()` immediately destroys it.

Fence asynchronous callbacks by the active connection/match generation. Do not publish a success/reconnected notification merely because foregrounding or MQTT connection succeeded. Preserve a deliberate pause and do not regenerate a missing board or start an unverified match.

**Required regressions:** Exercise the actual main-menu, lobby, join-room and foreground recovery adapters. Verify monitoring survives connection setup and restores pause, remainder, counters, board, picks and chat. Recomposition and repeated reconnects leave one active session.

### HB07 — Enforce one owner, one turn sequence and actual deadlines

**Locations:** `RootNavGraph.kt`: timer near 4142, fallback authorization near 4249, `SYNC_REQUEST` near 2667, `executePick()`; packet epoch/revision fields.

Replace the new-policy local-decrement/fallback authority with a serialized production match coordinator. Timer rendering must derive from one accepted deadline; UI effects cannot create deadlines. Tag actions/commits with match ID, policy, controller identity/epoch, expected revision, turn ID and stable action ID. Deduplicate exact transitions and reject obsolete owners. Preserve immutable pick history and actual turn IDs through sync.

Remove the `activeRemaining.size <= 2` shortcut and equivalent overlapping authorization for this policy. An unavailable observer must suspend unsafe picks/timeouts/results, not only disable Claim victory. After both players close, resume from a known accepted suspended checkpoint without replaying old timeout jobs. Never compare monotonic values directly across phones or boots.

**Infrastructure boundary:** Heartbeat silence is not an exclusive ownership grant. Audit whether available infrastructure can enforce a single controller and final commit order during takeover. A local epoch increment, deterministic tie-break or ordinary retained MQTT record alone is insufficient. Do not add/deploy an unapproved authority service, assume cloud GET/update is atomic or silently introduce per-move HTTP. If this guarantee is unavailable, implement the visible safety wait, record takeover/dependent cases as **BLOCKED**, finish independent repairs and report **NOT READY** for the full feature. Do not conceal the missing guarantee with another fallback timer.

**Required regressions:** Two production coordinators under asymmetric delivery, both reconnecting, old-owner packets, delayed timeout and a pick at expiry. Exactly one accepted outcome per turn and no conflicting histories/results. Verify an unavailable turn is 30 seconds, not the old 32-second peer fallback.

### HB08 — Preserve the same paused remainder on both phones

**Locations:** `RootNavGraph.kt`: `handlePauseAction()` near 671, `GAME_RESUMED` near 2219 and timer initialization near 4145; `GameScreen.kt` interruption actions.

The receiver currently sets a remainder, clears its frozen value and then restarts an effect that overwrites it with 30. Apply one accepted pause/resume state containing the turn/revision and exact millisecond remainder/deadline. Both local and remote paths must consume that state consistently; no stale resume may affect a later turn. UI is display/request only.

Ensure Continue countdown dismisses without resetting time; if offered while paused, it must either explicitly resume through the accepted action path or use an accurate label. Make interruption actions accessible after dismissal. Recovery does not auto-resume a deliberate pause.

**Required regression:** Pause at exactly 12,000 ms, wait, resume and exercise the actual receiver/UI lifecycle: both states resume at the same remainder, and the next turn gets 30,000 ms. Repeat at zero/near-zero remainder and with duplicate/stale pause/resume packets. Fake-clock assertions must be exact; real device displays should agree within one second after sync.

### HB09 — Remove the stale UI override from victory claims

**Location:** `RootNavGraph.kt` `executeClaimVictory()` near 811–838; `OnlineRoomSyncManager.claimMatchVictory()`.

Remove `|| isClaimVictoryEligible`. UI eligibility never authorizes a result. Only a current accepted serialized claim commit may finish the match. Validate actor, exact match/episode, count >= 3, current peer unavailability, current local health, expected revision and no existing result. Do not stop monitoring or mutate statistics before that decision is accepted and saved.

**Required regression:** Keep the UI flag true, deliver a fresh verified peer response, then process confirmation before UI catches up. Claim must be rejected with no side effects. Also reject missing policy, unhealthy observer and duplicate confirmation. Test the actual claim handler, not only `canClaimVictory()`.

### HB10 — Scope and validate incoming claims and policy capability

**Location:** `RootNavGraph.kt` `CLAIM_VICTORY` near 2230; outgoing claims; `RoomMessagePacket` and `FastPacketCodec`.

Only the negotiated online two-player policy may process a claim result. Require the active match identity, valid episode/count, authorized controller/epoch, monotonic revision and stable result/action ID. Validate the committed result, not an arbitrary peer request. Reject late claims from prior matches, malformed/insufficient claims, LAN/group traffic and unsupported clients.

Negotiate capability explicitly before `START_GAME`; a default `protocolVersion=1` on legacy decoding is not capability evidence. Ensure compact `P|`, `T|`, `H|` and new packet encodings preserve every field the new policy relies on, using a tested versioned format or JSON fallback. Do not force unrelated legacy modes onto new semantics.

**Required regressions:** Deliver old-match, wrong-peer, wrong-epoch, low-count, duplicate and unseeded/identity-less claims through the actual packet handler. None may finish the game. A valid final commit is accepted once. An old/new client pair cannot silently start a match advertised as using the new rules.

### HB11 — Make accepted final results recoverable after both apps close

**Locations:** `executeClaimVictory()`, checkpoint deletion near 823, clean MQTT session/publication in `OnlineRoomSyncManager.kt` and recovery snapshot/result handling.

Do not delete the only recovery record and rely on a non-retained packet sent while the loser is offline. Durably store an immutable final result keyed by match ID, with result ID, winner/reason and accepted epoch/revision, before notifying UI/clearing active status. Preserve/reconcile it across app restart and make it available to an absent participant on return. Keep result delivery/application idempotent and statistics/history exactly once.

Use an existing durable store only after verifying its capability. A local career record that recovery never reads, an offline cache write or an unverified claim that data is in the cloud is insufficient. Ordinary MQTT retention alone does not enforce result ownership or immutability. If required durability/coordination is unavailable, identify the blocked dependency; never claim the result is safely shared.

**Required regression:** Peer offline, third eligible incident, confirmed claim, winner also closes, then loser returns. Loser receives the same final result and cannot restart the unfinished match. Repeat both restarts/delivery attempts; total statistics change once. Race with Bingo/surrender/recovery in the same ordered decision path.

### HB12 — Remove the generic packet shortcut around heartbeat freshness

**Location:** `OnlineRoomSyncManager.kt` near 1167–1177.

Remove the fallback that calls `onFreshPeerResponse()` for arbitrary peer traffic. Ordinary heartbeat, presence, room metadata, delayed gameplay or rejected old-match `GAME_SYNC` cannot establish current liveness. Validate dedicated challenge ACKs by match, intended peer, active connection generation, outstanding challenge, sequence and age <= 5,000 ms; consume each challenge once.

Mark state synchronized only after the active snapshot is validated, applied and durably saved by the coordinator. A transport callback receiving `GAME_SYNC` is too early. A broker echo must itself be fresh, not a delayed uncorrelated PING that refreshes observation indefinitely.

**Required regression:** Withhold valid ACKs while replaying old metadata, old connection messages, old-match sync and expired/duplicate ACKs. None clears unavailability or revokes a claim. A valid fresh ACK immediately revokes eligibility, independently of how long board synchronization takes.

### HB13 — Enforce account scoping at every storage boundary

**Locations:** `OngoingMatchStore.kt` near 144; `RootNavGraph.kt` start/read/update/clear calls; `presentation/menu/MainMenuScreen.kt`, `presentation/auth/AuthGateScreen.kt` and other store consumers.

Use account-scoped keys or an equivalent enforced namespace. Make ownership mandatory for save/read/update/clear and all live callers; do not retain an optional parameter that production omits. Save the owner UID on automatic as well as manual match creation. Identify participants by validated account identity, not display name.

Define a compatible legacy migration: do not blindly attach a blank-owner record to whichever account is currently signed in. Migrate only when participant/account ownership is established; otherwise leave that record quarantined from another account's UI without deleting unrelated data. Preserve the ability to install the protected baseline without clearing user data; document any schema downgrade limits.

**Required regression:** Account A saves an unfinished match, signs out; B signs in. B cannot see, resume, overwrite or clear A's match. A returns and recovers it. Exercise real callers rather than manually passing a UID only from a test.

### HB14 — Replace unsupported PASS claims with actual evidence

**Locations:** `artifacts/heartbeat-1v1/TEST_REPORT.md`, `CHANGELOG.md`, `AGENT_HANDOFF.md` and the four new test suites.

Preserve historical reports in Git, mark the unsupported claims as superseded and immediately set current readiness to NOT READY while repairs are underway. Do not silently erase failed attempts. Maintain an HB01–HB14 resolution table with changed symbols, regression test IDs, commands, actual results and evidence paths.

Replace tests that only assign local variables, compare UUIDs, copy validation conditions or construct ideal snapshots with tests invoking the actual production coordinator, transport/storage adapters and lifecycle entry points. Keep useful codec/calculation tests but label their scope. Correct tests that bless one ACK followed by ten silent seconds as stable recovery.

**Required evidence:** Device/network/force-stop/takeover/latency cases are `NOT RUN` or `BLOCKED` until executed. A successful JVM suite alone cannot mark them PASS. Include two-device screenshots, event logs/revisions and measured latency samples where those cases require them.

## Verification requirements in this delivery

First preserve the existing failures and add regressions for each repaired path. Use fake monotonic clocks, fake transport and failing durable-store adapters connected to the **production** coordinator. Do not create a second test-only match engine that merely demonstrates the desired rules.

Reproduce the independent checks before fixing their corresponding behavior:

```bash
python3 /home/bob/workspace/heartbeat-review-evidence/run_probe.py /home/bob/workspace/bingo_heartbeat_work
```

After repairs, run equivalent checks against the repair worktree's freshly compiled classes:

```bash
python3 /home/bob/workspace/heartbeat-review-evidence/run_probe.py /home/bob/workspace/bingo_heartbeat_fix001
```

The original first assertion constructs the policy exactly as the old startup did. If observation initialization correctly moves into the manager/coordinator, replace that assertion with a regression exercising **that real startup path**. Do not weaken its requirement, manually fake successful initialization or make an unobserved peer healthy just to satisfy the probe. Preserve the original captured output.

An exact healthy/recovery fixture for the fake-clock tests can be:

| Observer time | Expected accepted policy state |
| --- | --- |
| 0 ms | Valid known match and healthy local observation begin |
| 14,999 ms with no peer ACK | No incident yet |
| 15,000 ms | Episode 1 opens once |
| 16,000 ms | Valid fresh ACK and completed sync; claim disabled; recovery window begins |
| ACKs at 18,500 / 21,000 / 23,500 / 26,000 ms | Episode closes at 26,000 ms, after 10 stable seconds |
| No ACK until 41,000 ms | Episode 2 opens once |

If those intermediate recovery ACKs are absent, episode 1 must **not** close at 26,000 ms; at 31,000 ms the silence warning belongs to that same episode.

Run the original plan's 44 cases, plus the HB-specific regressions here. Cover both roles, automatic/manual boards, each rejoin route, short/long/repeated interruptions, local and broker outages, asymmetric delivery, stale packets, claim races and both apps closing. Repeat rapid recovery cycles at least 20 times per role. Use Kevin/Stuart test accounts and automated taps so screenshots do not consume gameplay turns; do not invite `@bob` or reset personal data.

Run appropriate tests per internal chunk, then the full unit suite/lint/release build in the repair worktree:

```bash
./gradlew testDebugUnitTest --rerun --offline --console=plain --max-workers=2
./gradlew lintDebug assembleRelease --offline --console=plain --max-workers=2
```

Use online dependency resolution if caches are absent. Require no unexplained baseline test regressions and zero lint errors. Explain any new warnings. Confirm APK signing certificate matches the baseline. Measure baseline and Fix001 tap-to-accepted-pick latency on the same device/network setup; report sample count, median and p95, rather than inferring speed from non-blocking code.

## Mandatory change journal, evidence and rollback

Before code edits, create `artifacts/heartbeat-fix001/CHANGELOG.md`, `BASELINE_MANIFEST.json` and `Fix001ReviewResolution.md`. Record both baseline/candidate commit IDs, current feature branch, actual starting HEAD, protected hashes and proposed file inventory. Copy this prompt and the review into the repair worktree's `docs/` for continuity.

Append an intention before each logical edit chunk and an outcome after testing:

```markdown
### FIX001-001 — <purpose>
- Started / completed: <IST timestamps>
- Checkpoint before edit: <commit SHA>
- Review IDs: <HB numbers>
- Files / symbols: <exact locations>
- Actual old behavior / replacement: <specific change>
- Integration callers: <all start/rejoin/receive paths wired>
- Scope and compatibility: <why other game modes and old data remain safe>
- Tests: <command, result, test IDs, evidence; include failed attempts>
- Remaining dependencies: <specific issue or none>
- Rollback: <checkpoint/targeted revert and storage considerations>
```

Commit tested chunks locally and preserve an ordered commit list. Do not stage unrelated files or secrets. If a chunk fails, save its diff, status, untracked files and logs before reverting it. Corrective commits are preferable to history rewriting. Keep both earlier worktrees untouched.

Collect bounded Kevin/Stuart JSONL traces with match, connection, episode/action/result IDs, controller epoch, revision, turn ID, remaining milliseconds, local health and acceptance/rejection reason. Each actual device test needs expected/observed behavior, action timestamps, both screenshots and related trace ranges. Log failures honestly; do not fill the matrix with hardcoded PASS values.

Safe independent return to the protected baseline, using an unused directory:

```bash
git -C /home/bob/workspace/bingo_multiplayer worktree add --detach /home/bob/workspace/bingo_fix001_baseline_check a13d39edb0ff6a67d272cac187f1ed9fb294f9e9
```

The preserved original-repository baseline APK provides the installation rollback. Keep signing identity and user data; do not uninstall/clear data as a repair shortcut. For source rollback within the repair branch, use targeted `git revert` after preserving uncommitted/untracked evidence. Never reset or clean the original baseline checkout.

## Final delivery and readiness decision

Deliver the repaired feature source, local commit list, candidate Fix001 APK with size/version/SHA-256/signature comparison, updated handoff, append-only change journal and `Fix001ReviewResolution.md`.

The resolution report must have a row for **every HB01–HB14**, with status `FIXED`, `FAIL`, `BLOCKED` or `NOT RUN`, exact changed symbols and real evidence. Report separate results for unit tests, production-path integration, paired-device execution and latency. The final message must state which requirements work and which remain unmet.

Use **READY** only when the agreed behavior is implemented, safe ownership/result durability are proven and mandatory acceptance cases have real passing evidence. Otherwise state **NOT READY** with precise remaining blockers and retain all completed safe fixes. A larger passing test count, successful APK build or a popup screenshot alone cannot establish readiness.

Do not modify the protected baseline, publish anything or claim complete reliability based on the previous report. Fix the real paths, show the evidence and leave a clear handoff for the next session.
