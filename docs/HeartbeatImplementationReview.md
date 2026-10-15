# Bingo heartbeat implementation review

Reviewed **2026-10-15 10:45 IST** against the agreed `HeartbeatImplementationPlan.md`.

**Verdict: NOT READY.** Some heartbeat transport, UI and storage helpers were added, but the required behavior is not correctly connected across match startup, turns, recovery and results. The implementation's blanket PASS/READY claims are not supported by its tests.

| Review item | Value |
| --- | --- |
| Candidate | `/home/bob/workspace/bingo_heartbeat_work`, branch `feature/heartbeat-1v1` |
| Reviewed commit | `fc8bdf0ef460e29dc2e8e410fe41ae81ffd606bd` |
| Protected baseline | `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9`, branch `1.4.1` |
| Actual JVM rerun | **346 tests / 34 suites**, zero failures/errors/skips; completed successfully in 16 seconds |
| Independent production-reducer checks | **Five failed assertions across three focused scenarios** |
| Changes made during review | External review/evidence files only; no application source fixes, commits, pushes or APK installations |

The examples below are source-confirmed failure paths unless explicitly marked as executed. Device/network scenarios have **not** been reproduced on two emulators during this review. A passing JVM build does not resolve these source-level defects.

## Findings

### HB01 — P1: Normal automatic-board matches never start the heartbeat feature

**Locations:** [startNewGame](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:1009), [manual-board heartbeat start](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:1249), [host automatic-board start](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:3727).

`startNewGame()` stops the match heartbeat and never starts it again. The new startup call is inside `startCountdownAndInitiateTurn()`, which belongs to the manual-board readiness path. Ordinary automatic-board matches on both host and guest enter `startNewGame()` directly.

**Effect:** Start a normal online match and disconnect a player. The new 15-second monitor, interruption counter and popup are inactive. `is1v1MatchActive` is also false, so the transport's new Last Will protection does not apply.

**Fix:** Centralize activation after match identity/immutable participants and subscription are ready, and invoke it for automatic, manual, rematch and all recovery entry points. Stop/start must belong to a defined match lifecycle, with one effective monitor and no duplicate jobs.

### HB02 — P1: Three missed turns can still automatically award a forfeit

**Location:** [TURN_TIMEOUT handler](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:1894), especially the automatic-result condition at line 1921.

The timer's local fast-skip path was bypassed for online 1v1, but the incoming `TURN_TIMEOUT` handler still marks a player disconnected, increments `consecutiveMissedTurns` and ends the match when `activeRemaining.size <= 1 && misses >= 3`. It clears the recovery snapshot and records a win without a claim confirmation.

**Effect:** Even a connected player who simply misses three turns can trigger the old outcome. This is independent of the agreed three **separate interruption episodes** and violates manual claiming.

**Fix:** Remove/bypass this automatic-result path for the negotiated new 1v1 policy. Keep timeout handling as one turn transition. Use the heartbeat episode record exclusively for claim eligibility, and audit every other legacy departure/result path under the same policy guard.

### HB03 — P1: Incident counts and open episodes are not persisted/restored by live gameplay

**Locations:** [availability decision collector](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:4274), [rejoin creates a new policy](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:3286), [storage fields/update](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt:105).

Availability decisions update only remembered UI variables. The only live call passing `committedEpisodeCount` to storage is the pause helper. No production caller saves the episode ID/open flag or calls `restoreCheckpoint()`. Rejoin copies a saved count into a UI variable and then creates a fresh policy whose internal count is zero. Picks also omit actual `turnIds`, despite the new storage field.

**Effect:** Let the observer count two separate outages, do not press Pause, and restart it: the count is lost. Even a count saved through Pause is not restored into the policy that decides future incidents/claims. Episode identity and skipped-turn attribution are not recoverable as specified.

**Fix:** Persist the actual policy checkpoint on every accepted availability change, not only a UI count on pause. Restore it into the policy before observation/claims. Store per-player records, actual pick turn IDs and shared revisions, and include them in recovery snapshots. Check write results; current callers ignore save failures. Perform durable disk writes off the UI thread.

### HB04 — P1: Recovery can count one unstable absence twice, or leave an episode open forever

**Locations:** [watchdog recovery completion](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicy.kt:238), [SYNCING fresh response](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicy.kt:315).

**Executed reproduction A:** Open an outage, accept one fresh response and mark sync complete. Send no further responses. At 10 seconds, `onTick()` closes the episode as recovered because it checks elapsed recovery duration without checking the last-heartbeat gap. At 15 seconds since that lone response, it counts another episode. The required maximum gap is five seconds.

**Executed reproduction B:** Open an outage, lose/restore local health, synchronize, then supply fresh responses every 2.5 seconds for 20 seconds. The `SYNCING` response path changes state straight to `RESPONSIVE` while leaving `isEpisodeOpen=true`. Subsequent healthy responses never close it; the next independent outage remains count 1 instead of becoming count 2.

**Fix:** Have one recovery-completion predicate used by tick, response and sync paths. It must require completed sync and a continuously valid heartbeat window, including the current silence gap. Local restoration with an existing episode must enter that recovery path and eventually close/re-arm it correctly.

### HB05 — P1: No first response can leave the observer in SYNCING forever

**Locations:** [default policy construction/start](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt:176), [SYNCING watchdog branch](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/domain/engine/OnlineMatchHeartbeatPolicy.kt:257).

`startMatchHeartbeat()` constructs the default `SYNCING` policy but never initializes its observation window. `onTick()` does nothing in that state. Local health restoration also enters `SYNCING`, and `onStateSynchronized()` alone does not leave it.

**Executed observation:** The production policy construction used by startup remains `SYNCING`, count 0, after a 15-second tick without a peer response.

**Effect:** If the peer disappears before the first heartbeat, or remains absent when the observer reconnects, a healthy observer may never show the required popup. The observer depends on first receiving the very peer response that is missing.

**Fix:** Define a bounded observation start once local health, accepted match identity and the agreed startup/recovery checkpoint are established. A known participant's absence can then reach 15 seconds without manufacturing an infinitely old timestamp or requiring a response to begin measuring silence.

### HB06 — P1: The actual rejoin entry points omit recovery or cancel its heartbeat

**Locations:** [main-menu rejoin](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2853), [lobby rejoin](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:3470), [connectToRoom](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt:391).

The main-menu rejoin callback still restores the baseline subset: it sets `turnTimer=30`, does not restore the new pause/incident policy state and never starts the match heartbeat. The lobby callback starts the heartbeat at line 3474, then calls `connectToRoom()` at line 3497. That method first calls `disconnect()`, which stops and clears the just-created policy/jobs.

**Effect:** The user's normal recovery buttons can return to an unmonitored game, lose a deliberate pause or reset the countdown. Adding a working helper did not integrate these real entry points.

**Fix:** Use one recovery routine from every rejoin route: connect/subscribe, obtain and validate snapshot, restore policy/timing/pause, start monitoring, then enable play. Avoid starting monitoring before a connection operation that destroys it. Add tests through these entry points.

### HB07 — P1: Independent local timers and competing controllers remain

**Locations:** [countdown effect](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:4142), [fallback timeout authorization](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:4249), [competing sync responders](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2667).

The countdown still decrements a local integer after `delay(1000)`, starts at 30 after relevant effect restarts, and lets the peer advance after an extra two-second fallback. `activeRemaining.size <= 2` authorizes either 1v1 phone. Local-unhealthy decisions disable only claiming; they do not stop picks/timeouts. Added epoch/revision/turn-ID fields are not used to enforce ownership or accept ordered gameplay commits.

**Effect:** Network-separated phones can independently progress from different histories. Foreground/rejoin and timer scheduling can produce different remaining times; an unavailable turn can last 32 seconds on the peer's fallback path. Neither both-offline suspension nor safe controller takeover is established.

**Fix:** Implement one enforceable controller/commit order and deadline-based turns. Bind timeout requests to the exact match/turn/revision, serialize pick/timeout/result races, and reject obsolete owners. If available infrastructure cannot enforce ownership, suspend unsafe mutations and mark takeover BLOCKED as the plan requires.

### HB08 — P1: Resuming a paused game gives the remote phone a fresh 30 seconds

**Locations:** [GAME_RESUMED receiver](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2219), [timer initialization](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:4145).

The receiver assigns the packet's remainder to `turnTimer`, then clears `frozenRemainingTurnSeconds`. Changing `isGamePaused` restarts the timer effect, which sees no frozen value and overwrites the received remainder with 30. The local resume path retains the frozen value, so the two phones differ.

**Source-derived reproduction:** Pause with 12 seconds remaining, then resume. The resuming phone consumes the frozen 12; the receiving phone's effect initializes to 30. Pausing is also second-rounded rather than preserving exact milliseconds.

**Fix:** Restore the accepted deadline/remaining-duration state atomically and render both timers from it. Do not clear the restored timing input before the timer consumes it. Test the real receiver and effect lifecycle, not local test variables.

### HB09 — P1: A rejected claim can still win through stale UI eligibility

**Location:** [executeClaimVictory](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:811).

The condition is `result is ClaimResult.Accepted || isClaimVictoryEligible`. Therefore rejection by the live policy, or even a missing policy, can be overridden by the remembered UI flag. Heartbeat decisions travel asynchronously from IO callbacks to that flag.

**Effect:** The peer responds and the policy revokes eligibility, but a confirmation processed before the UI collector catches up still records a win. This is the precise recovery-versus-claim race the plan forbids.

**Fix:** Only an accepted serialized claim commit may finish a match. Remove the UI fallback and validate current match, episode, count, actor, local health, expected revision and existing result in the same ordered operation.

### HB10 — P1: Any received CLAIM_VICTORY packet is treated as a valid defeat

**Location:** [CLAIM_VICTORY receiver](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:2230).

The handler accepts the packet for an unfinished online **or LAN** game without verifying seed/match ID, episode ID/count, controller, revision or negotiated policy. The sender does not populate the required identity fields either. Legacy peers are never required to negotiate support before start.

**Effect:** A delayed claim from an earlier match in the same room can end a rematch. A claim with fewer than three incidents is not rejected by the receiver. The new result behavior leaks into LAN scope.

**Fix:** Scope the handler to the negotiated online 1v1 policy, require a valid final commit for the active match/episode/controller/revision and deduplicate by stable action/result ID. Check both peers' capability before starting a match under these rules.

### HB11 — P1: A victory claimed against an offline opponent is not a durable recoverable result

**Locations:** [claim clears ongoing state and publishes](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:822), [clean MQTT session](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt:430), [non-retained publication](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt:661).

The claimer records local career/history, deletes the active checkpoint and sends a non-retained claim packet. The opponent is intentionally offline, and its clean MQTT connection does not provide a durable result inbox. An in-memory `GAME_SYNC` response can announce the result while the claimer stays running, but there is no final match checkpoint/tombstone restored after that claimer also exits.

**Effect:** Claim victory, close the winning app, then reopen the absent player's app. The available recovery path can still find its unfinished saved match rather than the accepted final result. Career history alone is not read as a final authority for that active match.

**Fix:** Durably commit an immutable final result keyed by match ID before clearing active recovery state; make it available to reconnecting peers and retain deduplication/result identity across restart. Await actual persistence, and synchronize the final result before permitting resumed moves.

### HB12 — P1: Ordinary stale packets bypass heartbeat freshness verification

**Location:** [unconditional peer activity fallback](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomSyncManager.kt:1167).

After the dedicated challenge/ACK handler, almost any other packet from the peer calls `onFreshPeerResponse()`. That includes ordinary `HEARTBEAT`, room metadata, delayed gameplay and `GAME_SYNC`, without challenge validation or an active-match check at this point. `GAME_SYNC` also marks synchronization complete before the navigation handler validates/applies it.

**Effect:** Queued old traffic can clear the unavailable warning or revoke an eligible claim while the peer is still absent. A rejected old-match sync can still change availability/synchronization state. The dedicated five-second challenge check does not protect this fallback.

**Fix:** Only a verified current-connection challenge response establishes liveness. Notify state synchronization only after the active snapshot has been validated, applied and persisted. Do not use generic peer traffic or stale session metadata as a shortcut.

### HB13 — P2: Account isolation is optional in the helper and unused by the app

**Locations:** [optional account check](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/domain/network/OngoingMatchStore.kt:144), [unscoped read in lobby](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:3411), [automatic-board initial save](/home/bob/workspace/bingo_heartbeat_work/app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt:1148).

The store uses one global `active_match_data` key. `getOngoingMatch(..., accountUid)` validates an account only when a non-null UID is supplied; production readers omit it. Automatic-board initial saves leave `accountUid` blank, and the filter permits blank ownership even when a UID is supplied.

**Effect:** Signing into another account can expose/rejoin the previous account's unfinished match on that installation. The account-isolation unit test passes only because it explicitly supplies the argument that the app does not pass.

**Fix:** Make ownership explicit on every save/read/update/clear, use account-scoped storage and validate participant identity before rejoin. Define a deliberate legacy migration rather than accepting unidentified records for any account.

### HB14 — P2: The PASS/READY report overstates what its tests exercise

**Locations:** [reported all-pass matrix](/home/bob/workspace/bingo_heartbeat_work/artifacts/heartbeat-1v1/TEST_REPORT.md:5), [pause tests](/home/bob/workspace/bingo_heartbeat_work/app/src/test/java/com/bingo/multiplayer/domain/network/MatchCoordinatorAndPauseTest.kt:143), [connection-generation test](/home/bob/workspace/bingo_heartbeat_work/app/src/test/java/com/bingo/multiplayer/domain/network/MatchHeartbeatPacketCodecTest.kt:98).

The four new suites contain 42 JVM tests. Many verify local assignments, packet round-trips, manually supplied storage data or copied conditions instead of invoking the live coordinator/navigation/transport path. For example, a pause test assigns an integer to a local variable; the generation test compares two UUIDs; the cold-launch test rereads fake SharedPreferences. The claim tests explicitly treat a lone response followed by ten silent seconds as successful recovery, encoding HB04's wrong behavior.

The report labels force-stop, broker failure, safe takeover, process-kill durability and measured latency PASS without two-device screenshots/runtime traces or latency sample statistics. Only the three metadata Markdown/JSON files are present in the heartbeat evidence directory, alongside an ignored candidate APK; there is no corresponding device-test evidence.

**Fix:** Keep genuine passing unit checks, but correct the matrix to distinguish unit coverage from NOT RUN/BLOCKED integration/device work. Replace copied/mirrored coordinator tests with production-path tests and fake transport/clock hooks. Execute the required paired-device cases and collect real timestamps, revisions, screenshots and latency samples before READY.

## Reproduced failures and rerun instructions

The review compiled a small Java probe against the candidate's actual compiled `OnlineMatchHeartbeatPolicy` class and injected its clock interface. It did not copy the implementation into a test model or modify `app/src`.

- [Probe source](heartbeat-review-evidence/HeartbeatPolicyReviewProbe.java)
- [Initial captured output](heartbeat-review-evidence/HeartbeatPolicyReviewProbe.out)
- [Rerun helper](heartbeat-review-evidence/run_probe.py)
- [Actual JVM rerun log](heartbeat-review-evidence/unit-test-rerun.log)
- [Copied JVM result XMLs](heartbeat-review-evidence/unit-test-results/)

Initial captured output:

```text
FAIL new policy notices first peer absence after 15s: state=SYNCING, episodes=0
FAIL 10s without recovery heartbeats does not close the episode: state=RESPONSIVE, episodeOpen=false
FAIL same unstable outage is still one episode: episodes=2
FAIL stable recovery after local reconnect closes the prior episode: state=RESPONSIVE, episodeOpen=true
FAIL next outage after stable local recovery increments the count: episodes=1
Contract violations reproduced: 5
```

The first check mirrors the construction used by the transport startup; its repair may belong in startup initialization rather than making every uninitialized policy assume a healthy peer observation.

After compiling the candidate's debug classes, rerun with:

```bash
python3 /home/bob/workspace/heartbeat-review-evidence/run_probe.py /home/bob/workspace/bingo_heartbeat_work
```

The helper exits nonzero on failed assertions. After changing method contracts, adapt the probe or add equivalent production-path regressions; retain this initial evidence. UI/startup, transport delivery and process-kill findings still require their own integration/device checks.

Actual suite command executed during review:

```bash
./gradlew testDebugUnitTest --rerun --offline --console=plain --max-workers=2
```

## Baseline and artifact preservation

Remote `1.4.1` still points to `a13d39e`; remote `main` still points to `c012527`. The original repository's tracked source is unchanged, and its baseline APK retains SHA-256 `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc`.

The shared `/home/bob/workspace/Bingo.apk` currently contains the **candidate**, SHA-256 `46b5038530ca19685581bb407bff648c85ba5ee6b5ea5470fc577066f688c8e0`, despite the plan asking to preserve that copy too. The original-repository baseline APK remains available for rollback. This review did not replace either APK.

## Recommended correction order within the existing feature branch

Fix startup/rejoin lifecycle and observation initialization, retire all new-policy automatic-forfeit paths, correct and durably integrate episode recovery, then enforce shared turn/pause/claim acceptance and durable results. Correct the test report immediately and add regression tests that exercise these actual paths. Preserve the backup branch and continue on the feature worktree.

The dedicated probe transport, monotonic clock abstraction, codec support and non-expiring local storage helper are useful foundations. Their integration needs correction before the agreed feature is ready.
