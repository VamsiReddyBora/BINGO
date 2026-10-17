# Bingo Multiplayer — Consolidated Code Review

Reviewed: **17 October 2026, 14:03 IST (UTC+05:30)**.  
Repository: `/home/bob/workspace/bingo_multiplayer`  
Branch: `pre-release-ver-1.5`; HEAD: `fd2eee5`, **including the current uncommitted source and fixes**.  
Requirements reference: [GameRules.md](GameRules.md).

## Outcome

**This review identifies 22 open findings: 21 implementation defects and one explicitly required but unimplemented host-Exit outcome.** There are **10 P1 findings and 12 P2 findings**. An additional failing account-restore test is documented separately; its precise cause remains unresolved.

This is the consolidated inventory from this review, with no three-issue cutoff. It is not a claim that every possible bug in the application has been discovered. Findings below identify their evidence: source tracing, execution of production helpers, or both. **None is presented as a new physical-device/emulator reproduction.** Some defects need packet loss, delayed delivery, restart or a particular event order; they are not necessarily visible in a normal match.

**No gameplay source, existing tests, rules, APK, release configuration or Git history was changed.** Only this report was added to the repository. Evidence and temporary probes were saved outside it. All 306 pre-existing tracked/untracked files were checked against their initial hashes.

### Test results from this review

| Check | Result | Meaning |
| --- | --- | --- |
| Existing full JVM suite: `./gradlew :app:testDebugUnitTest --rerun --console=plain` | **630 tests, 55 suites: 629 passed, 1 failed, 0 errors/skips** | The current suite is not completely green. The failure is V1 below. |
| Additional external review probes | **15 passed** | These assertions reproduce defects or confirm production-helper behavior used by the affected callbacks. Passing means the stated defect observation was reproduced, not that it was fixed. |
| Callback/source-flow checks | **17 checks confirmed** | Source assertions locate wiring and missing guards. These do not execute Compose or Android lifecycle callbacks. |
| Live MQTT, Kevin/Stuart emulator match, Android process death, FCM delivery and visual verification | **Not run in this review** | These remain device/integration validation work. |
| APK build | **Not performed** | The existing release APK was not changed. |

The full suite includes both production-code tests and older simulation/model tests. Its total must not be described as 630 complete end-to-end application scenarios.

## Rules deliberately preserved

These are **not findings** and must not be changed as an incidental fix:

- Group host absence remains **60 seconds total**, including the initial 10-second buffer and subsequent 50-second HOLD.
- Online 1v1 retains its separate host-wait behavior; the group limit must not be copied into 1v1.
- In-game Exit is permanent. Backgrounding, network loss, a crash and swiping away the app are temporary absence.
- The group's existing inactivity decision, early skip, pause/extension, elimination and ready-countdown policies remain baseline policies. A shorter group inactivity skip is not, by itself, a violation of the separate 1v1 full-turn rule.
- The visible in-game door/Exit control opens the surrender confirmation and calls `onSurrender`. It does **not** use the temporary `onBackToMenu` handler during an unfinished online match. The separate Return to Lobby behavior has not been newly redefined.
- Existing Bingo winner/tie/runner selection, immediate legal pick feedback, MQTT gameplay, AI and Nearby behavior remain protected. Nearby socket departure behavior is not automatically an online defect.
- The host-Exit continuation requirement needs a reviewed control design. This report neither selects nor implements controller transfer or a new backend.

## Complete finding index

P1 means high priority because the path can end a valid match incorrectly, corrupt accepted state, lose essential recovery, or violate an important participation rule. P2 means medium priority, generally affecting timing, recovery reliability or communication in a narrower case. These priorities are impact assessments, not claims about how often a user will encounter them.

| ID | Priority | Finding |
| --- | --- | --- |
| R01 | P1 | Intentional group host Exit ends the match instead of allowing remaining participants to continue — required implementation gap. |
| R02 | P1 | Group Exit/Surrender can be discarded by the immediate transport disconnect, with no durable pending departure. |
| R03 | P1 | A group surrender counts temporarily disconnected players as permanently gone and can award a premature win. |
| R04 | P1 | Host recovery through `GAME_SYNC` bypasses the pause/finished guards applied to direct moves. |
| R05 | P1 | Group final results are not durably recoverable after the host process closes. |
| R06 | P1 | A backgrounded group host can continue advancing turns while guests are in host HOLD. |
| R07 | P2 | Restoring an unpaused group host resets the saved turn remainder to 30 seconds. |
| R08 | P1 | Host inactivity/manual/elimination skips do not persist their full turn transition. |
| R09 | P2 | Group host-return countdown can enable gameplay before authoritative state synchronization. |
| R10 | P2 | A fresh host reply arriving after 60 seconds can beat the watchdog and bypass the limit. |
| R11 | P1 | An uncancelled manual-board start job can reopen gameplay after the room/match was cancelled. |
| R12 | P1 | Late or invalid `BOARD_READY` data can overwrite marked boards or throw from the incoming callback. |
| R13 | P1 | Unscoped lobby controls can remove a player or change settings during an active/new match. |
| R14 | P2 | Temporary Return to Lobby can advance the screen without updating the inactivity watcher. |
| R15 | P2 | Group chat sent while a player is offline has no history-recovery route. |
| R16 | P2 | Delayed group/Nearby chat can enter a later match. |
| R17 | P2 | Group chat deduplication drops legitimate messages and uses colliding timestamp IDs. |
| R18 | P2 | An unverified/delayed group rejoin announcement can display a false green reconnection notice. |
| R19 | P2 | Cloud start fallback can choose a different first player from the host's start packet. |
| R20 | P2 | A group recovery record expires after eight minutes even when the match is still active. |
| R21 | P2 | Restarting a group guest resets the existing host-absence allowance. |
| R22 | P2 | Account backup can report cloud success when it has stored only an in-memory copy. |

## Findings, locations and corrections

All `Root` references below mean [RootNavGraph.kt](app/src/main/java/com/bingo/multiplayer/presentation/navigation/RootNavGraph.kt). Network files are under `app/src/main/java/com/bingo/multiplayer/domain/network/`; engine files are under `app/src/main/java/com/bingo/multiplayer/domain/engine/`. Line numbers refer to the reviewed working tree and will move after editing.

### R01 — P1 — Permanent host Exit does not permit group continuation

**Type:** Required implementation gap, explicitly acknowledged in GameRules section 3; not a newly introduced regression.  
**Locations:** Root **5596–5637** (`onSurrender`), **3497–3520** (`HOST_LEFT`); `OnlineRoomSyncManager.kt:1463` (`disconnect`).

Kevin hosts a three-player match with Stuart and Bob. Kevin confirms the visible Exit action. The host path publishes `HOST_LEFT`, closes the room and disconnects. Receiving online group guests set `isGameOver = true`, clear recovery and show “Host has left the match. Match ended.” Stuart and Bob cannot continue together.

**Required correction:** Implement the separately reviewed single-controller continuation design for an accepted permanent host departure. Retain the same match identity, boards, history and eligible roster. Do not disguise this as a temporary host absence or extend the 60-second waiting rule. This report does not authorize an architectural implementation.

**Evidence/checks:** Direct source flow from the real Exit confirmation to the host handler. Test current/non-current host Exit, pause, duplicate delivery, two or more remaining participants, one remaining participant, and a group shrinking to two without switching protocols.

### R02 — P1 — Immediate disconnect can discard a permanent departure

**Locations:** Root **5596–5637** and **6163–6200**; `OnlineRoomSyncManager.kt:796–828`, **1463–1465**.

A non-host confirms Surrender & Exit in a group match. `broadcastPacket` queues an IO coroutine, capturing the current room generation. The handler then records the local loss, clears recovery and calls `disconnectRoom()`. `disconnect()` immediately changes that generation. If the queued publisher runs afterward, its generation guard returns before publishing. A disconnected transport can also prevent the send. The remaining players receive no accepted permanent departure, and there is no persisted departure outbox to retry after a restart.

**Required correction:** Persist a match/account-scoped pending permanent departure before leaving. Arrange confirmed, idempotent delivery and authoritative acceptance before retiring that transport, or retry the pending departure through a durable recovery route. Preserve the existing generation guard for unrelated stale traffic. Do not infer successful publication merely from calling a function returning `Unit`.

**Evidence/checks:** Source-confirmed asynchronous ordering race, not a claimed live-broker reproduction. Test a paused IO dispatcher, offline Exit, disconnect immediately after confirm, process death before acknowledgement, duplicate acknowledgements and rematch isolation.

### R03 — P1 — A temporary outage can become a win when somebody else surrenders

**Locations:** Root **3374–3398**, especially `activeRemaining` at **3385**; `isPlayerDisconnected` at **290–293**.

Kevin, Stuart and Bob are participants. Bob temporarily loses connectivity. Stuart intentionally exits. The surrender receipt correctly removes Stuart permanently, but the result branch filters the roster using `!isPlayerDisconnected`, excluding Bob too. Kevin is counted as the sole remaining player and can receive a forfeit win even though Kevin and Bob remain eligible participants.

**Required correction:** Determine the final remaining roster from permanent departures and applicable explicit elimination decisions. Temporary availability must remain separate. Let the original controller accept and publish the result; guests must not independently finalize different winners using different local availability lists.

**Evidence:** Production reducer probe: `eligible=[host, B]`, while the callback's availability filter produces `screenCount=1`. Test each ordering of temporary outage, surrender, elimination and duplicate receipt, including zero/one/two eligible participants.

### R04 — P1 — Snapshot move recovery accepts moves while paused or after finish

**Locations:** Root **2829–2902**, **2904–2927**; `LobbyLifecycleEngine.kt:551–628` (`reconcileRecoveredPicks`).

Stuart picks just as Kevin pauses the group match. The direct `PICK_NUMBER` path can reject the move because gameplay is frozen. Stuart's later periodic `GAME_SYNC` still carries his optimistic pick. The host recovery branch checks turn/number ownership but has no pause, suspension or finished-state guard before applying accepted recovery picks. It can add the number and advance the turn during the pause. After a final result, the same branch can still mutate boards/history before reaching its later `if (!isGameOver)` condition.

**Required correction:** Apply the same active-match, freeze and terminal-state constraints to every path that accepts a move. During pause or after completion, return the accepted snapshot without importing speculative moves. Preserve legitimate recovery of a still-open, playable turn.

**Evidence:** The real recovery helper accepts one proposed open-turn pick and advances to turn 3; source checks confirm its callback invokes it without these guards. This combines helper execution with source tracing, not Compose execution. Test delayed direct pick plus later sync during pause, final-result immutability and normal recovery.

### R05 — P1 — Group final result disappears from recovery after host process death

**Locations:** Root **882–959**, **3109–3111**, terminal group handlers; `OnlineRoomSyncManager.kt:524–526`, **796–828**; `OngoingMatchStore.kt:318`.

Kevin accepts a valid group Bingo while Stuart is offline. Group completion clears the active checkpoint. Its full terminal `GAME_SYNC` uses the ordinary non-retained room publisher and a clean MQTT session. If Kevin's process closes before Stuart receives the result, there is no persisted full group terminal snapshot from which Kevin can answer after restarting. Local statistics are not the shared final result.

**Required correction:** Persist an immutable, match-scoped complete terminal result before clearing active recovery. Provide an authenticated/validated MQTT recovery route for that accepted result after process death, with result deduplication. Preserve the original authority model; do not turn an unavailable player into a new winner. Include no-winner terminal closures in the design.

**Evidence:** Production store probe confirms that active clear leaves neither an active group checkpoint nor a result tombstone; source confirms non-retained publication and no equivalent group terminal storage. **Online 1v1 already has a separate retained-result path and is not this finding.** Test offline completion, lost final packets, crash before/after publish, restart and rematch.

### R06 — P1 — Backgrounded group host continues controlling an apparently frozen match

**Locations:** Root **554–614**, **2146–2179**, **4904–5079**, **5094–5110**; `MultiplayerMatchLifecyclePolicy.kt:286`.

Kevin backgrounds the app while Android keeps his process/coroutines running. Group fresh-presence replies stop because they require foreground, so guests enter HOLD after 10 seconds. The host's group turn loop, periodic state publisher and inactivity watchdog are not suspended by foreground/local transport health. The host can still close turns and publish state while guests display a frozen host-wait screen. If Android suspends the process, behavior differs again.

**Required correction:** Suspend group controller mutations when its local lifecycle/transport cannot support active participation, save its frozen accepted position and reconcile recovery before enabling it again. Coordinate this with the existing guest HOLD/ready behavior. Keep the confirmed 60-second policy and avoid replaying background timeouts.

**Evidence:** Source-confirmed missing lifecycle guard; actual Android scheduling was not exercised. Test background host with process still running, process suspended, network-only loss, current/non-current host turns, pause and return before/after the deadline. Verify authoritative turn IDs, not just the guest's displayed timer.

### R07 — P2 — Group host restart gives the current turn a new 30 seconds

**Locations:** Root **1718–1742**, **1808–1840**; control persistence at **314–327**.

A group host has a saved unpaused remainder of 17 seconds and restarts after a crash. Restore uses `matchData.remainingTurnMillis` only when the match is still paused. Its `else` sets `turnTimer = 30`. The returning host can then distribute this reset time, overwriting the preserved remainder seen by waiting guests.

**Required correction:** Restore the saved accepted remainder for an unchanged turn in both paused and recovery-frozen states. Reconcile it with valid peer/controller checkpoints without inventing elapsed offline turns. Only a genuinely new turn gets 30 seconds.

**Evidence/checks:** Source-confirmed restore assignment, not a device timing measurement. Test saved remainders 30, 17, 1 and 0 seconds, manual pause expiring during closure, and crash versus ordinary foreground return. Coordinate with R06/R09.

### R08 — P1 — Some host skips publish a turn that their recovery record does not contain

**Locations:** Root **2275–2299**, **4909–4929**, **5401–5425**, **5485–5509**; `persistMultiplayerControl` at **314–327**; host sync wrapper **345–359**.

The inactivity auto-skip, Skip Turn button and eliminated-player skip paths change the screen's current player/turn and broadcast a timeout without saving the complete transition. Periodic host synchronization persists control revisions and remaining time, but omits `turnNumber` and `currentTurnPlayerId`. A crash can therefore restore A/turn 2 even though peers already accepted B/turn 3. Pick-only recovery cannot reconstruct a timeout-only gap reliably.

**Required correction:** Persist every accepted turn transition atomically, including turn/player, histories, roster, remainder and pause/frozen identity, before publishing it. Use the same complete transition path for pick, normal timeout, inactivity skip, manual skip and elimination.

**Evidence:** Production store probe using the control-save arguments restores `A/2` after the screen moves to `B/3`; the real recovery helper rejects B's later turn-3 pick against that old turn. Test process death immediately after each skip and before the next pick, with/without a pause and repeated timeout packets.

### R09 — P2 — Fresh presence alone can release group HOLD

**Locations:** `MultiplayerMatchLifecyclePolicy.kt:175–200`, **310–326**; Root **2398–2404**, **2194–2207**, **554–614**.

The returning host answers a fresh presence challenge, but its authoritative state packets are delayed or lost. `onHostHeartbeat` starts RETURNING immediately and the policy emits `HostResumed` after three seconds. The callback clears HOLD/ready state without requiring a newly accepted host snapshot. The normal foreground path also does not establish a group synchronization barrier. Gameplay can become enabled on a stale position.

**Required correction:** Require both verified host presence and accepted state reconciliation before enabling gameplay. Preserve the existing three-second ready countdown, but prevent a heartbeat-only return from authorizing moves. Do not confuse connectivity verification with board/turn synchronization.

**Evidence:** Production policy emits `HostResumed` after a reply and three seconds with no state-sync input; source tracing confirms no additional release barrier in the callback. Test presence delivered while all `GAME_SYNC` messages are withheld, then deliver the current snapshot and verify preserved remainder/turn.

### R10 — P2 — Late heartbeat can bypass the 60-second boundary

**Locations:** `MultiplayerMatchLifecyclePolicy.kt:175–186`, **289–312**; Root watchdog **5098–5103**.

Guests are in HOLD. A valid response is processed at 60,001 ms before the next 500 ms watchdog tick. `onHostHeartbeat` overwrites `lastHostHeartbeatAt` before comparing absence duration and changes HOLD to RETURNING. The next tick no longer sees the expired absence, and the match resumes. If the tick had run first, it would terminate.

**Required correction:** Evaluate the existing absence deadline before refreshing it. At or beyond the confirmed limit, keep the terminal decision immutable. A return verified before the limit may still complete its three-second ready countdown afterward.

**Evidence:** Production fake-clock probe resumes from a response at **60,001 ms**. Test 59,999/60,000/60,001 ms with reply-before-tick and tick-before-reply ordering, including a delayed UI thread.

### R11 — P1 — Manual start coroutine survives cancellation of its room

**Locations:** Root **1552–1658**, **4774–4804**, **3497–3520**, **3738–3748**; `ManualBoardDesignScreen.kt:92–125`.

The manual-board start countdown runs in Root's long-lived coroutine scope, with no stored cancellation job or captured account/room/match guard. A room departure/cancellation can happen while that job is pending—for example, the host leaves the waiting screen while the last board-ready exchange reaches another player and starts that player's countdown. A valid departure/removal can end the room, yet the old job later clears `isGameOver`, initializes state and navigates to Game.

**Required correction:** Bind the start job to the exact account, room, seed and setup phase. Cancel it on room/match cancellation or navigation out of that setup. Revalidate identity and terminal state after suspensions and before saving/navigating. The countdown overlay itself should not be described as an always-accessible Exit control; this finding concerns cancellation/event races.

**Evidence/checks:** Source-confirmed job lifetime and unconditional post-delay writes; not an emulator reproduction. Test room removal/host departure at each countdown value, a last-ready-versus-leave race, account change and starting another room while the old job is pending.

### R12 — P1 — Board-ready handling can replace accepted boards and lacks payload validation

**Locations:** Root **2592–2609**; `ManualBoardEngine.kt:128–155`; `FastPacketCodec.kt:69–76`.

`BOARD_READY` checks the seed but does not restrict application to the manual setup phase or reject a board already accepted for that participant. A delayed duplicate arriving after play starts rebuilds an unmarked board and replaces `allPlayerBoards[playerId]`/`opponentBoard`, losing that board's accepted marks. When all boards are still present, `isLocalBoardReady` remains true and `countdownSeconds` has returned to -1, it can also call `startCountdownAndInitiateTurn` again from the active game and reset turn/start state after another countdown. Separately, a nonempty short payload passes the callback's check: `buildBoard(listOf(1,2,3), 5)` throws because it indexes 25 entries. The Root incoming collector does not catch that exception at this boundary.

**Required correction:** Validate participant, expected setup phase, size, length, uniqueness and complete number range before construction. Accept each layout once for the match; duplicates must be idempotent, and a conflicting/late layout must never replace an active board. Keep valid manual-board functionality.

**Evidence:** Production engine probe changes one marked cell to zero after rebuilding; the short payload throws `IndexOutOfBoundsException`. Source confirms the unsafe assignment/call. This is **not proof of the cause of the user's earlier unexplained app closure**. Test valid duplicates after several picks, conflicting layouts, malformed lengths/ranges and rejoin.

### R13 — P1 — Lobby control packets can affect active/new matches

**Locations:** `MultiplayerLifecyclePacketGate.kt:11–17`, **59–62**; `FastPacketCodec.kt:100`; Root **3674–3684**, **3738–3748**; `OnlineRoomSyncManager.kt:1283–1288`.

`KICK_PLAYER` is outside the scoped/host-only control set. Its compact encoding discards room/seed metadata. An old kick can therefore pass the active-match gate, clear the target's recovery record, disconnect them and navigate to the menu. The receive branch also lacks original-host validation. This boundary affects active online 1v1 as well as group matches. Similarly, delayed `SETTINGS_UPDATE` is accepted by a guest during gameplay and changes manual/dynamic/board-size state without a lobby-phase or host guard.

**Required correction:** Give room controls an explicit room-session identity and original host authority. Preserve their identity on the wire and validate phase before *any* registry/UI mutation. Lobby kicks/settings must not apply to an active match or a later room; keep the distinct, intentional in-match elimination feature.

**Evidence:** Real codec/gate probe: old kick encodes as `K|A|host`, decodes with seed 0 and passes the seed-42 active gate. Source tracing confirms the destructive handler and settings assignment. Test delayed old-room controls, same-room rematch, non-host sender, and genuine lobby removal.

### R14 — P2 — Temporary departure leaves the policy watching the previous turn

**Locations:** Root **3408–3484**, especially **3461–3465**; `MultiplayerMatchLifecyclePolicy.kt:244` and `isCurrentTurnAction`.

A returns to the lobby during A/turn 2. The temporary departure branch advances the screen to B/turn 3 without updating the lifecycle watcher or applying the full frozen/persisted transition. The watcher still observes A/2. The previously added action fence correctly rejects stale A decisions, so this is **not** a claim that the old extra-skip bug remains. However, B's own inactivity decision is not started correctly.

**Required correction:** Keep the separately defined temporary Return to Lobby behavior, but apply any accepted turn change through the complete state/policy/frozen-time/persistence path. Do not turn this control into permanent Exit without a separate rule decision.

**Evidence:** Production policy probe: `screen=B/3 policy=A/2`; stale actions are rejected but B is not monitored. Test B becoming unavailable next, pause/restore and replayed departure events. This consolidates earlier finding N4.

### R15 — P2 — Group rejoin does not recover missed chat

**Locations:** `RoomMessagePacket` in `OnlineRoomSyncManager.kt:26–86`; ordinary publisher **796–828**; Root **2724–2757**, **2762–2827**, **2829–3140**, **3702–3734**.

Stuart goes offline. Kevin and Bob exchange group chat while the match continues. Stuart rejoins and receives current picks/turn through `GAME_SYNC`, but group packets carry no complete chat history and the recovery handlers do not merge missed messages. Local `OngoingMatchStore` history contains only messages that Stuart already received. Clean-session, non-retained publication cannot supply that missing history.

**Required correction:** Include accepted, match-scoped chat history or an acknowledged history cursor/delta in group recovery. Merge by stable message identity while preserving accepted order and the current centered text-only status styling. Keep history bounded without silently deleting unseen messages during ordinary rejoin.

**Evidence/checks:** Source-confirmed absence of a group history transfer path. 1v1 V2 already carries accepted chat and is separate. Test offline user/system messages, restart, duplicate catch-up and delayed delivery.

### R16 — P2 — Old chat enters the next match

**Locations:** Root **364–384**, **2724–2757**, **5545–5580**; `MultiplayerLifecyclePacketGate.scopedTypes`.

User and system chat in group/Nearby sends no required match identity. Incoming chat is appended without a seed/participant guard. A message delayed from seed 41 can enter seed 42 in the same room, including an obsolete connection/status message. This does not itself change the board.

**Required correction:** Stamp the original match identity before enqueue/retries and validate it, room and sender before appending/persisting. Define any lobby chat separately; do not allow an unscoped fallback into active-match chat.

**Evidence:** Production packet gate admits seed-41 chat in the seed-42 context; the callback lacks a later match check. Test rematch, restart, delayed system notices and old-room delivery. This consolidates earlier finding N5.

### R17 — P2 — Text/time-based chat deduplication loses legitimate messages

**Locations:** Root **2730–2740**; `GameScreen.kt:312–319`; `InGameChatSpace.kt:134`.

Stuart says “Hi” and Bob says “Hi” within five seconds. Root considers the second message already present because its comparison ignores sender/message identity. Its system-message condition can also collapse separate players' reconnection messages. Incoming IDs are just `packet.timestamp`, so two different messages with the same sender-clock millisecond collide; the screen can drop one, and restored duplicate keys are unsafe for its keyed chat list.

**Required correction:** Use a stable, sender-qualified or globally unique message ID, retained across retries. Deduplicate that ID within the match, not the text or a broad reconnection substring. Keep separate users' messages and distinct repeated phrases. Use the same identity in persistence and UI keys.

**Evidence:** External source-expression probe reproduces the different-sender text drop and same-timestamp ID collision. Compose duplicate-key behavior was not executed. Test two senders using identical text, identical timestamps, retry duplicates and multiple recovery notices.

### R18 — P2 — A rejoin announcement is treated as proof of reconnection

**Locations:** Root **2762–2796**, **599–609**; `MultiplayerLifecyclePacketGate.kt:59–76`.

A queued `REJOIN_GAME` announcement arrives after the sender has disappeared again. The group callback calls `markPlayerReconnected`, clears missed-turn state and adds a green “reconnected” message without a verified fresh response or synchronized state. The packet gate's fresh-proof sequence fence applies to departure types, not this rejoin notice. The actual presence policy can still say disconnected, causing contradictory UI.

**Required correction:** Let announcements trigger verification/sync. Produce connected status from the verified presence and synchronization transition, scoped to the current episode/session. A permanently departed player must not generate a participating-player reconnection notice.

**Evidence:** Real gate accepts a sequence-2 rejoin announcement even after sequence-100 fresh proof for the same instance; source shows unconditional status presentation. The existing fresh-presence gate remains useful and must not be replaced by generic packet receipt. Test delayed/duplicate announcements, rapid background/return and permanent departures.

### R19 — P2 — Fallback start can assign the wrong first turn

**Locations:** Root **4421–4434**, **4481–4487**, **2485–2491**, **1442–1456**; `OnlineRoomSyncManager.kt:943–956`; `ManualBoardEngine.kt:162–165`.

The normal host start chooses `determineRandomFirstTurn(seed, sortedIds)` and puts that UID in its start packet. The cloud fallback synthesizes a start packet without that UID. The guest then uses the first entry of `generateDeterministicTurnOrder`, which is a different selection algorithm. If the direct start packet is missed/delayed, a group guest can initially show or act on the wrong first player until authoritative correction.

**Required correction:** Use one canonical first-turn value and immutable participant list across direct start, fallback, manual start and recovery. Carry the host's accepted first turn in fallback metadata or derive it identically. Prevent initial moves until authoritative setup is established. Keep the existing randomized rotation policy.

**Evidence:** Production helpers with seed **1** and the probe roster choose `host=A` versus `fallback=B`. 1v1 V2 subsequently synchronizes authoritative state, so the uncorrected initial group path is the principal gameplay concern. Test deliberately dropping the direct start, delayed duplicate start and manual/dynamic boards.

### R20 — P2 — Eight-minute group TTL removes a still-valid rejoin opportunity

**Locations:** `OngoingMatchStore.kt:91`, **295–306**.

A non-host is unavailable for nine minutes while the host and another participant continue a long group match. Loading the local ongoing record deletes it solely because its last local update is older than eight minutes. The match can still be valid, but the stored boards/match identity and standard rejoin entry disappear. The latest rules do not define eight minutes as permanent departure.

**Required correction:** Distinguish stale local cache age from authoritative match completion. Verify the match/result before deleting an eligible participant's recovery record, or agree an explicit expiry policy separately. Keep terminal cleanup and account isolation.

**Evidence:** Real store probe retains an equally old 1v1 record but deletes the group record. Test a still-active long group match, completed/closed room, unavailable lookup and delayed final result. Do not change the confirmed 60-second *host* absence limit to address a *non-host's* cache expiry.

### R21 — P2 — Group guest restart restarts the host-absence clock

**Locations:** Root **1808–1840**; `MultiplayerMatchLifecyclePolicy.kt:65–68`; `OngoingMatchData` in `OngoingMatchStore.kt:19–65`.

Kevin has been absent for 50 seconds. Stuart restarts while Bob remains in the match. Restore constructs a new policy whose `lastHostHeartbeatAt` is “now”; the prior HOLD/absence context is not saved/restored. At the original 60-second deadline Bob terminates, while Stuart still has another 50 seconds of allowance and can later appear to resume a match Bob has already ended.

**Required correction:** Preserve match-scoped absence/terminal context across restoration and reconcile the shared terminal state before reopening play. Do not let restoring a UI silently grant a fresh absence budget. Handle clock/process-restart boundaries explicitly, retaining the confirmed total limit.

**Evidence:** Production fake-clock probe reaches the original policy's deadline while the recreated policy reports **50 seconds remaining**. Source confirms restoration does not import previous host-absence state. Test one guest restarting at 9/35/50/59 seconds, another remaining active, and host return around the original boundary.

### R22 — P2 — Cloud backup returns success for a memory-only fallback

**Additional account finding found while investigating the suite failure.**  
**Locations:** `AccountSessionManager.kt:252–326`, **334–342**.

If cloud update/create does not succeed, `backupUserDataSync` can still put the backup into `backupMemoryCache` and return `success || backupMemoryCache.containsKey(gid)`. This reports true even when the only new copy is volatile memory. The asynchronous caller sees success and skips its failure retry. An app uninstall/process loss can then lose that unsaved state despite apparent backup success.

**Required correction:** Separate durable cloud confirmation from local/cache acceptance. Preserve offline usability and the memory cache, but maintain a durable retryable backup job and report cloud success only for a confirmed remote write. Ensure stale queued backups cannot overwrite newer confirmed data.

**Evidence/checks:** Source-confirmed false-success path; no live service outage was induced. Test failed update/create, process restart before retry and eventual confirmed upload using an injected storage gateway. **This has not been proven to be the cause of V1's restored-count failure.** Do not claim that fixing it alone resolves that test.

## V1 — Unresolved full-suite account-restore failure

Test: `AuthRepositoryTest.testCloudBackupAndRestore_survivesAppUninstallAndWipe`.  
Location: `app/src/test/java/com/bingo/multiplayer/domain/repository/AuthRepositoryTest.kt:178`.

The test saves a profile after two wins, performs backup and restores through a fresh repository. The current full run reports **expected games played 2, restored 0**. The same failure occurred intermittently in the preceding fix batch, whose later isolated/full reruns passed. It failed again in this review. It is therefore an unresolved validation problem; a previous green rerun did not establish a fix.

The precise cause needs isolation. The test includes asynchronous backup work, cloud/cache fallback and a fixed username shared by its generated test identities. Those are investigation leads, not established causes. Do not count all of them as separate proven bugs or blame this on heartbeat changes without evidence.

**Next investigation:** Control the storage gateway and coroutine scheduling; inspect exactly which profile/version each backup and restore uses; distinguish same-process cache restoration from restoration after a genuinely fresh process; then separately validate service integration. Save failure evidence before rerunning. No passing rerun was used to hide the failing result from this review.

## Earlier findings that were not counted again

The current source and corresponding production tests retain the prior repairs for:

- Rejection of stale/partial host snapshots before board mutation and separation of accepted versus pending picks.
- Monotonic pause control revisions, non-extending equal-revision samples and preservation of versioned pause-extension data.
- Direct pick/timeout/result/surrender identity checks and rejection of duplicate/closed-turn transitions.
- Explicit surrender receipts, current/non-current turn handling and stale inactivity-action fencing.
- Fresh group presence nonce/generation verification, retired app-instance rejection and transport-LEAVE isolation.
- Pending guest Bingo requiring authoritative host confirmation, including the later-host-pick/pending-history race.
- The separate 1v1 retained snapshot/result protocol, host-only pause controls and host-wait recovery tests.

R04 concerns a different snapshot-recovery entry point; R08/R14 concern incomplete screen/policy/persistence wiring; R18 concerns status presentation from a rejoin announcement. They do not mean the guarded direct-packet reducers or fresh-response verifier have been removed or proven ineffective.

## Coverage and remaining integration checks

| Area inspected | Review conclusion / remaining work |
| --- | --- |
| Lobby ready/start, compatibility exchange and cloud start fallback | Traced start gating and protocol-version paths. R13/R19 remain. No new proof of the old “same APK needs update” failure in this review. |
| Generated/manual boards and initial countdown | Reviewed generation, readiness, start callbacks and incoming setup data. R11/R12 remain. |
| Online 1v1 picks, timeout sequence, pause, host waiting, retained final and chat | Reviewed coordinator/client and integration, with existing production tests exercised. No additional standalone 1v1 protocol defect established in this pass; R13's room-control boundary still affects it. Device qualification is outstanding. |
| Group gameplay, results, temporary absence and permanent Exit | R01–R14 and R21 identify the remaining rule/application problems. Preserved group timing/elimination policies were not relabeled bugs. |
| Rejoin, process restoration and final delivery | R05/R07/R08/R09/R20/R21 remain. Test crash windows using real processes and separate local stores. |
| Chat/status UI and recovery | R15–R18 remain. Check loss/reorder/duplicate behavior and verify actual screenshots after correction. |
| Friends, statistics and notifications at gameplay boundaries | Checked invite/busy-state boundary, relevant notification policy/ledger, chat/status wiring and account persistence paths encountered here. R22/V1 require account work. This is not a fresh complete external FCM/backend, OAuth, broker-ACL or social-feature audit. |
| AI/Nearby regression boundary | Existing suite exercised; Nearby departure exception and separate AI route were preserved. No emulator certification of these modes. |

Device validation must include at least Kevin and Stuart for 1v1, and **a third independent participant for group behavior**. Two emulators alone cannot validate every 3–8 player scenario. Use controllable packet delay/loss, fresh installations with separate stores, actual process kills and synchronized evidence of turn/player/remaining time on every participant. The older screenshot-based simulation report is not evidence that these current source paths were executed.

## Evidence and handoff

Evidence directory outside the source repository:

`/home/bob/workspace/bingo-comprehensive-review-evidence-20261017`

It contains the initial Git status and 306-file hashes, full unit-test log/XML, external `ComprehensiveReviewProbe.kt`, its compile/output logs and source-flow assertion script/results. The final unchanged-file verification is saved there too. Probes use production engines, reducer, gate, codec and store; the chat-dedup probe evaluates the callback's source expression, and callback omissions remain source findings. No probe is claimed to run the Android UI.

Historical open findings are consolidated here: **N6 → R05; N4 → R14; N5 → R16**. Earlier batch reports remain historical records, not a separate hidden queue.

For future fixes, keep this as one inventory. Address P1 impact first, but do not silently change the rules or restart a three-at-a-time reporting loop. Record each ID as fixed only after the real integration path and its stated failure scenario are verified. The latest confirmed rules remain the acceptance criteria; this report alone does not authorize changing game policy, adding a backend or implementing controller transfer.
