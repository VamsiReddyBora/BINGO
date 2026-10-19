# Simplified Game Rules — Source Compliance Review

**Reviewed:** 18 October 2026, 18:18 IST (UTC+05:30)  
**Repository:** `bingo_multiplayer`  
**Branch:** `pre-release-ver-1.5`  
**HEAD:** `a5a089899fbface4f0db59e9fe5bb8efb0d4f911`  
**Specification:** `SimplifiedGameRules.md`, updated 18 October 2026, 17:54 IST.  
**Reviewed version:** The actual working tree, including your uncommitted changes, rather than HEAD alone.

## Conclusion

**The source does not yet meet the documented simplified rules.** The board engine and parts of the new turn flow are correct, but active handlers still mix the new rules with older host authority, missed-turn penalties, departure handling and recovery behavior.

This report records **30 source-confirmed findings**: **24 P1** findings affecting results, turn consistency or recoverability, and **6 P2** findings covering presence, preparation, UI, signaling and chat recovery. These are grouped by distinct failure mechanism, not by the number of affected lines or screens. Some findings can be repaired together. This is one consolidated review, not a new three-issue batch.

- **P1:** Fix before claiming that the simplified multiplayer rules are implemented. A listed scenario can lose state, grant the wrong result, remove an eligible player, or desynchronize a turn.
- **P2:** A documented presentation or related feature is incomplete, or the implementation retains behavior inconsistent with the stated design.
- There are also **policy questions**, listed separately. They are not instructions to remove intentional features.

**No application source, rules, tests in the repository, or APK were modified during this review.** The only repository addition is this report. Existing changes belong to the working tree that was reviewed.

## What was checked

The review followed the actual screen callbacks and incoming packet handlers through match creation, automatic/manual boards, local and remote picks, every timeout path, foreground/background transitions, MQTT loss/reconnect, explicit exit, group continuation, result calculation, snapshot reconciliation, persistence, rejoin, rematch, status rendering, chat restoration and sound.

The active application path is:

`MainMenu/Lobby → RootNavGraph → OnlineRoomSyncManager → MQTT → RootNavGraph handlers → gameplay reducer / snapshot reconciler → local store and GameScreen`

The code still uses the cloud room registry for room metadata and recovery-card validation. The old reliable-match client/coordinator is not the running game controller in this path. Its tests cannot establish compliance of the current screen callbacks.

The source inventory records 31 files containing 20,483 lines. This is an inventory of related files, not a claim that every unrelated UI or account feature in those files was re-certified. Active rule-related branches, shared helpers and their call sites were traced; unused legacy paths were identified separately.

## Rule-by-rule result

| Documented behavior | Review result | Evidence / findings |
| --- | --- | --- |
| N lines on an N×N board | Implemented in board engines | `Models.kt:133`, `BingoEngine.kt:15`, `ManualBoardEngine.kt:158`; production probe covers sizes 5–10. Some result text still says “5 Lines”. |
| A validated pick marks every board, including offline players | Implemented in the marking helpers; end-to-end eligibility remains incomplete | R07, R13, R24, R26. |
| Caller below target must lose to another player completing their target | Correct in `evaluateMatchOutcome`; not reliable through all finalization paths | R07, R11–R13, R22. |
| Caller wins a simultaneous completion; others are runners | Partial | Evaluator applies caller priority; result handlers can turn the runner into a second winner: R12. |
| Backgrounding / signal loss / process death never causes surrender or automatic defeat | Fails | R01–R04, R15. |
| No interruption Pause/Continue/Claim decision dialogs | Old interruption dialogs have no live callers | Do not confuse this with the still-live manual pause feature; see policy questions. A forfeit-disconnection dialog can still be reached through R02. |
| Online turn starts with 30 seconds; already-away/offline turn starts with 5 seconds | Partial | The timer contains 30/5, but phones do not share a consistent status/budget: R06, R09, R25. |
| Leaving during an existing 30-second turn preserves the countdown | Fails in some paths | R03, R05, R06. |
| Returning during an originally 30-second turn preserves its remainder | Fails | R05, R08. |
| Returning before a 5-second turn expires grants 30 seconds exactly once | Partial | Reset exists, but is neither restricted to absence-started turns nor limited to one promotion: R05. |
| At zero, pass once without auto-picking or penalizing the player | Partial | No auto-pick in the active timeout path; receiver penalties, authority mismatch and immediate departure passes remain: R02, R03, R07, R09. |
| Temporary absence never removes a player from future rotations | Partial | Normal rotation now excludes explicit surrender IDs rather than temporary offline IDs; other departure paths still remove players: R01, R14, R15. |
| Explicit 1v1 exit awards the other player victory | Implemented on successful delivery; delivery is not durable | R17, R18. |
| Group exit permanently removes only that player; remaining eligible players continue | Fails in recovery and host-exit cases | R14–R18. |
| Current-player group surrender advances once, with the next player's correct budget | Reducer advances once; full flow partial | Reducer duplicate protection works; R06, R14–R17 remain. |
| Five minutes of player inactivity expires the match without a winner | Not implemented as specified | R10. |
| Activity keeps an arbitrarily long match recoverable | Not reliable | R10, R16, R23. |
| Everyone closing the app still permits recovery within five minutes | Not reliable | R01, R08, R10, R21, R23. |
| Rejoin restores the board, current turn and correct remaining time without replaying turns | Partial | R07, R08, R19–R23, R25. |
| Accepted results survive return/restart | Partial | R11, R21, R22, R23. |
| Rematch only while room exists; closed room offers only Main Menu | Fails | R28. |
| Correct online/away/offline colors and return/pass messages | Partial | R25, R27. |
| No heartbeat/controller dependency | Not achieved | R07, R29. Dedicated old watchdog is stopped, but regular room loops remain. |
| Online gameplay sound only on local cell tap; no turn/disconnect alarms | Source path is consistent | Remote packet handlers and countdown do not call the pick sound. Existing haptics are not prohibited by these rules. AI automatic picks share the sound helper, outside this online review conclusion. |

## Confirmed findings

### R01 — P1: Temporary transport disconnect is still interpreted as permanent exit

**Locations:** `OnlineRoomSyncManager.kt:485`, `:1503–1530`; `RootNavGraph.kt:3011–3050`, `:3115–3137`.

`disconnect()` sends `LEAVE` with `transportOnly = true`. The live `LEAVE/HOST_LEFT` receiver never checks that flag and treats it as intentional departure. In 1v1 this awards an immediate forfeit victory; in groups it adds the player to the permanent surrender set. Connecting/replacing a room session calls `disconnect()`, so this message is not limited to the confirmed Exit button.

**Scenario:** Kevin restores/replaces his connection to rejoin. A transport-only leave from his previous connection reaches Stuart. Stuart can finish the match as though Kevin explicitly surrendered.

**Correction:** Separate transport availability from a confirmed, match-scoped permanent departure. Only an explicit exit receipt may alter eligibility or award surrender victory. Do not blindly reject every LWT: LWT remains valid availability evidence, subject to connection identity and freshness checks.

### R02 — P1: Three missed turns can still award an automatic group forfeit

**Locations:** `RootNavGraph.kt:2190–2206`, `:2963–2981`, and the resulting dialog at `:5268`.

The `TURN_TIMEOUT` and group `PLAYER_DISCONNECTED` handlers still use missed-turn counts and the number of connected players to set `isGameOver`, write a loss/win, and display “You win by forfeit”. This is active code, not an unused watchdog.

**Scenario:** In a three-player match, Kevin and Stuart lose connectivity. After the recorded misses accumulate, Bob can be awarded victory although neither player surrendered and nobody completed Bingo.

**Correction:** Remove disconnection/missed-turn result paths from the running match flow. A timeout should close one turn only. Use Bingo or explicit departures leaving one eligible participant to determine a winner; use inactivity expiry to abandon a match without a winner.

### R03 — P1: Group disconnect packets skip the current turn immediately

**Locations:** `RootNavGraph.kt:2927–3007`, especially `:2963`, `:2981–3005`.

For groups, `PLAYER_DISCONNECTED` immediately increments the turn and publishes a timeout when the disconnected player owns the current turn. It does not preserve the running 30-second remainder or wait for an absence-started 5-second budget. Replayed disconnect events have no turn identity to make this transition idempotent.

**Scenario:** Stuart's turn has 18 seconds left when his socket closes. Other players advance immediately instead of showing the remaining 18-to-0 countdown.

**Correction:** Availability changes must update presence only. Let the existing turn record reach its deadline and close once. Choose the short budget only when a later turn starts with that player already absent.

### R04 — P1: Lifecycle/control packets can act on the wrong match or after newer events

**Locations:** `RootNavGraph.kt:1893–1916`, `:2297`, `:2678–2740`, `:2849–2925`, `:2927–3235`, `:3237–3245`; `MultiplayerLifecyclePacketGate.kt:37`.

The root dispatcher validates `gameplayTypes`, but does not call `MultiplayerLifecyclePacketGate.accepts`. Presence, rejoin and leave/disconnect branches therefore lack a consistent match/session/instance/freshness fence. Pause/resume checks the seed but not control revision/order. `LOBBY_EXPIRED` disconnects and navigates away even during gameplay.

**Scenario:** An older connection's delayed offline event arrives after Stuart reconnects, turning him red again. An old same-room host-left or lobby-expiry event can remove or eject current players. A delayed pause can freeze a game after a newer resume.

**Correction:** Validate all state-changing incoming controls before applying them: room, match, participant, current connection instance and appropriate monotonic event identity. Limit lobby controls to the lobby. Preserve the new peer policy rather than reinstating the old controller/watchdog just to reuse its gate.

### R05 — P1: Welcome-back resets originally 30-second turns and can repeat indefinitely

**Locations:** `RootNavGraph.kt:4498–4517`.

`wasAwayOrOffline` becomes true whenever the player leaves during any turn. Returning then resets the timer to 30. There is no record that the turn originally started with 5 seconds, or that its one welcome-back promotion was already consumed.

**Scenario:** Stuart starts with 30 seconds, leaves at 18 and returns at 12. Kevin's screen grants 30 instead of preserving 12. Stuart can repeat background/foreground changes to keep obtaining fresh time.

**Correction:** Store the selected budget on the turn, with a one-use absence-budget promotion flag. Originally 30-second turns preserve their deadline. Only a still-open turn that started with 5 seconds can receive one fresh 30-second deadline.

### R06 — P1: Devices do not share one consistent turn clock or starting presence

**Locations:** `RootNavGraph.kt:4478–4496`, `:4503–4506`, `:4532–4560`; foreground persistence at `:610–613`.

The local picker is always treated as ONLINE, regardless of actual local availability. Only remote presence selects 5 seconds. The host's countdown explicitly stops ticking while the host app is backgrounded, while other phones continue. Each device starts its own seconds counter without a shared turn deadline or agreed starting budget.

**Scenario:** Kevin backgrounds the host app. His local countdown freezes while Stuart's runs. On a later absent player's turn, one phone can use 30 seconds and another 5. Foregrounding and host snapshots then expose conflicting turns and remaining times.

**Correction:** Represent each turn with shared identity, selected budget and deadline, including a deterministic rule for accepting a close. Derive the UI countdown from that record. Host role must not freeze the clock. This recommendation does not require adding an external referee or changing the documented gameplay policy.

### R07 — P1: Recovery, group result acceptance and timeout validation still depend on a host

**Locations:** `RootNavGraph.kt:1582–1606`, `:1708`, `:2162–2169`, `:2544–2555`, `:4534–4559`; `MultiplayerGameplayReducer.kt:118`; `MultiplayerResultAuthority.kt:5`; `MultiplayerSnapshotReconciler.kt:49`.

Guest snapshots are accepted only from the host. Group results require host confirmation in several paths. The UI's acting-host/fallback timeout can advance locally, but `allowPeerTimeouts` is false online, and the reducer rejects a timeout from a peer who is neither the host nor the skipped player.

**Scenario:** Kevin is offline; Stuart and Bob are active. Bob passes Kevin's expired turn locally, but another phone can reject that pass. If Stuart's pick completes an offline participant's board, some guests wait for Kevin before accepting the result. Two returning guests cannot synchronize directly with each other while Kevin remains closed.

**Correction:** Apply one agreed peer acceptance rule across local execution, remote validation, recovery and finalization. A fallback that runs locally must be valid remotely. This is a mismatch with the current equal-peer specification, not a request to reintroduce the previous fixed controller.

### R08 — P1: Restored/synchronized remaining time is discarded, and clocks run before catch-up

**Locations:** `RootNavGraph.kt:1499–1525`, `:2623–2631`, `:4465–4496`, `:4820`; warm-resume path `:518–580`.

Restore reads `remainingTurnMillis`, but the game timer effect normally replaces it with a fresh 30/5 value because the unpaused recovery path does not populate the matching frozen-turn fields. A newly applied snapshot can have the same problem when the effect restarts. The timer also ignores `isReconcilingMatch`; warm foreground resume does not set that guard before requesting state.

**Scenario:** A saved or synchronized turn has 17 seconds left. Re-entering the screen starts a fresh 30. A cold guest can time out an old local turn while waiting for sync; a warm-returning player can tap before learning about picks made while away.

**Correction:** Restore/apply the current shared turn and remainder as one state transition. Catch up before enabling gameplay transitions, including automatic timeout transitions. Do not use a UI-effect restart as a new-turn event or grant missed turns again.

### R09 — P1: A healthy player's ordinary timeout changes their connection status

**Locations:** `MultiplayerGameplayReducer.kt:121–123`; `RootNavGraph.kt:2181–2193`, `:1876–1880`.

Every accepted remote timeout adds the skipped player to `disconnectedPlayerIds` and increments missed turns. The locally executed timeout does not apply the same presence mutation. A player who simply does not choose a number can become red on other phones and receive later short turns or contribute to R02.

**Scenario:** Stuart stays online but lets one turn expire. Kevin's phone now treats Stuart as disconnected, while Stuart's phone still treats himself as online.

**Correction:** Make turn expiry a pure pass. Availability must come from availability observations, not failure to pick. Keep any diagnostic missed-turn count separate from eligibility, turn budget and result decisions. A production-helper probe confirms this mutation.

### R10 — P1: The five-minute player-inactivity lifetime is missing

**Locations:** `OngoingMatchStore.kt:96`, `:218–255`, `:303–310`; `OnlineRoomRegistry.kt:314–320`, `:447`, `:495`; `LobbyScreen.kt:139`, `:177–203`; active timer `RootNavGraph.kt:4465`.

There is no active-game five-minute player-activity expiry. The local store uses 8 minutes for groups and 24 hours for 1v1, measured from checkpoint updates. Automatic sync writes refresh that time. The registry has separate host-heartbeat age rules. The lobby's 300-second countdown plus extension dialog runs in the lobby, not as the documented match lifetime.

**Scenario:** Everyone closes the app and an abandoned 1v1 remains stored well beyond five minutes. Conversely, guest activity cannot consistently replace the original host's metadata heartbeat. Automatic traffic can keep local records fresh without player activity.

**Correction:** Add one match-scoped last-player-activity/expiry model and propagate it through recovery metadata. Apply the specified five-minute inactivity window consistently. Do not count ticks, automatic passes, pings, heartbeats or snapshot retransmission as player activity. Expiry must produce no winner or forfeit. A production-store probe confirms that six-minute inactive records survive in both modes.

### R11 — P1: Result packets can finish a match without valid Bingo or surrender evidence

**Locations:** `MultiplayerGameplayReducer.kt:144–173`; `MultiplayerSnapshotReconciler.kt:75–82`; `RootNavGraph.kt:2588–2600`, `:2752–2761`.

The reducer treats `GAME_OVER` or any nonempty reason as Bingo verification. The reconciler accepts finished/reason-bearing winners without requiring a valid participant board or sole-survivor surrender evidence. Root's `shouldForceGameOver` can bypass even a rejected reducer result. Pending local Bingo can also drive snapshot finalization before accepted winning history is established.

**Scenario:** A delayed or inconsistent participant packet says “Bingo” with no winning board. The match still ends. A host snapshot can name an unknown winner with `isMatchFinished = true`, and the helper confirms it.

**Correction:** Validate the accepted winning pick/history and eligible winner against the actual board-size target, or validate explicit departure receipts leaving one eligible player. Treat packet type, free-text reason and finished flag as claims, not proof. Remove the forced-result bypass. Multiple production probes and the existing failed test confirm this finding.

### R12 — P1: A simultaneous-completion runner can be recorded as another winner

**Locations:** `RootNavGraph.kt:2590–2593`, `:2765–2768`.

Both result paths calculate local victory using “winner ID is me **or my board has Bingo**”. That contradicts caller priority whenever a non-calling player's board also reaches the target.

**Scenario:** Kevin's pick completes both boards. The accepted winner ID is Kevin. Stuart receives the result with his own board at target, so his phone sets `didPlayerWin = true` rather than runner-up, affecting the screen and saved statistics.

**Correction:** Derive victory strictly from the accepted winner identity. Evaluate the local eligible board and accepted runner list only after determining that the local player is not the winner.

### R13 — P1: Offline winner/runner eligibility is removed in downstream result handling

**Locations:** `MultiplayerGameplayReducer.kt:151–155`; `RootNavGraph.kt:1748–1757`, `:2165`, `:2591–2592`, `:2618`, `:2766–2789`; `GameScreen.kt:793–838`, `:1039–1058`.

The board evaluator includes offline participants, but result validation, runner construction and local result classification still filter `isPlayerDisconnected`. The results UI gives OFFLINE precedence over winner/runner status and suppresses their crown/runner badge. Explicit surrender and temporary offline are conflated.

**Scenario:** Kevin's pick completes offline Stuart's target. A helper can reject Stuart's claim because he is disconnected; result review can show OFFLINE instead of Stuart's legitimate win. An offline simultaneous finisher can disappear from the runner list.

**Correction:** Eligibility should depend on explicit permanent departure, not current availability. Render the accepted winner/runner outcome independently, with connection status as secondary information. A production probe confirms the reducer rejects a disconnected legitimate winner.

### R14 — P1: Permanent surrender receipts are not propagated or persisted by the live flow

**Locations:** `RootNavGraph.kt:1593–1607`, `:1813–1845`, `:1878–1880`, `:2556–2557`, `:2803–2807`, `:1552`; `MultiplayerSnapshotReconciler.kt:65`, `:96`.

The surrender reducer correctly updates the in-memory permanent set. The caller then updates only the checkpoint's turn number. Active snapshot producers omit `surrenderedPlayerIds`. Applying a later snapshot replaces the root permanent set from the checkpoint, and restart restores only that checkpoint set.

**Scenario:** Stuart explicitly exits a group. Other players initially remove him. A subsequent host snapshot or process restart can lose the departure receipt, allowing Stuart back into rotation or rejoin handling.

**Correction:** Commit explicit departure receipts with the accepted turn state; include them in every relevant snapshot; merge them monotonically and persist them on each accepted state change. No heartbeat or rejoin event may undo a permanent exit.

### R15 — P1: LEAVE/HOST_LEFT sole-survivor checks count connected players instead of eligible players

**Locations:** `RootNavGraph.kt:3046–3060`, `:3135–3154`.

The newer `SURRENDER` branch counts permanent departures correctly, but older LEAVE/HOST_LEFT branches still filter temporarily disconnected players when deciding whether one participant remains.

**Scenario:** Kevin explicitly leaves a three-player match. Stuart is temporarily offline and Bob is active. Bob can receive a victory even though Stuart remains eligible and the match should continue.

**Correction:** Share the same permanent-eligibility reducer for every explicit exit. A temporarily absent participant must still count when deciding whether only one eligible player remains.

### R16 — P1: Host group exit still closes the room needed by continuing players

**Locations:** `RootNavGraph.kt:946–954`, `:3063–3082`, `:4799–4813`; `OnlineRoomSyncManager.kt:1526–1530`; `OnlineRoomRegistry.kt:447`, `:495`, `:702`.

Root avoids its explicit `closeRoom` call for a group, but manager `disconnect()` independently closes any room whose local player is host. The UI's acting-host change does not transfer the registry's host identity or the manager's local role consistently. Recording the exiting host's loss also schedules a WAITING/seed-zero metadata update while the group is continuing.

**Scenario:** Kevin explicitly leaves a four-player game. The remaining players may continue exchanging MQTT packets, but the registry becomes CLOSED or WAITING, so returning players lose the recovery path and room metadata no longer describes the ongoing match.

**Correction:** Make leaving a participant distinct from dissolving a room. Preserve PLAYING metadata, roster and recovery state while enough eligible players remain. Keep the host badge/lobby role separate from match eligibility and recovery ownership.

### R17 — P1: An explicit exit can be forgotten when publication fails

**Locations:** `RootNavGraph.kt:4773–4818`, `:5324–5370`; `OnlineRoomSyncManager.kt:841–867`.

Exit clears the local checkpoint and records a loss before completing delivery. The awaited MQTT call returns false when disconnected or on timeout; both callers ignore the result and disconnect anyway. MQTT publish acknowledgement is also not an application-level acceptance receipt from the other players.

**Scenario:** Stuart confirms Exit while his connection is down. His phone forgets the match, but Kevin and Bob never receive the permanent departure and continue reserving turns for Stuart.

**Correction:** Persist a match-scoped pending permanent-exit receipt before leaving the UI, retry it safely, and merge/deduplicate it on peers. Clear recoverable participation only after the permanent decision is durably recorded; do not recreate a rejoin opportunity for a player who already confirmed exit.

### R18 — P1: Delayed exit cleanup can disconnect a newly opened room

**Locations:** `RootNavGraph.kt:115–130`, `:4806–4813`, `:5356–5363`.

The exit coroutine awaits a publish and then calls the global `disconnectRoom()`. That function acts on the current connection and current room variables. The UI has already navigated to Main Menu and can start another room. There is no captured room/account/connection-generation check around the delayed cleanup.

**Scenario:** An exit acknowledgement is slow. Kevin immediately opens a new lobby. The previous match's coroutine finishes and disconnects the new lobby or schedules closing it.

**Correction:** Capture the session being exited and close only that session. Fence all delayed cleanup by room, match, account and connection generation, and prevent stale work from touching replacement state.

### R19 — P1: A rematch reuses the previous match's snapshot checkpoint

**Locations:** `RootNavGraph.kt:1105–1318`, `:1320–1423`, `:2032–2115`, `:4857–4948`; `MultiplayerSnapshotReconciler.kt:49–63`.

New-game initialization resets many fields but never resets `multiplayerSnapshotCheckpoint`. That checkpoint contains the previous turn floor, accepted history, timestamp/revision and permanent departures. The reconciler is correctly strict about those fields, so carrying them into a new match blocks valid early snapshots or contaminates subsequent saves.

**Scenario:** Match one reached turn 20. In match two, Stuart's checkpoint still has turn 20 and old picks. The new match's turn-1 snapshot is rejected despite having the correct new seed.

**Correction:** Initialize all match-scoped state together whenever a new seed is accepted, including checkpoints, permanent receipts and control ordering fields. Keep old final receipts separately. A production probe confirms an old checkpoint rejects the new match's snapshot.

### R20 — P1: Accepted snapshot checkpoints are not saved with the snapshot they protect

**Locations:** `RootNavGraph.kt:2556`, `:2643–2654`; contrast `saveGameplayState` at `:1849–1856`; `OngoingMatchStore.kt:183–255`.

The guest applies a new `application.checkpoint` in RAM, then saves boards, picks and turn without passing that checkpoint. Background control persistence also does not save the updated accepted-prefix metadata. Restart can therefore restore current boards alongside an older turn floor/history/receipt checkpoint.

**Scenario:** Stuart receives several accepted host snapshots, then Android kills his process before another path saves the checkpoint. His restored guard no longer represents the history already displayed and saved, weakening stale-state rejection and losing permanent departures.

**Correction:** Persist the accepted checkpoint, board/history, turn, timing, roster receipts and match identity atomically. Recovery must never restore a newer presentation with older acceptance metadata. Apply expected-match/account fences to asynchronous persistence.

### R21 — P1: Accepted final results are not consistently durable or replay-safe

**Locations:** `RootNavGraph.kt:880–955`, `:1433–1450`, `:1708–1720`, `:2145–2170`; `OngoingMatchStore.kt:294–300`; `OnlineRoomSyncManager.kt:373–386`, `:825–830`; `AuthRepository.kt:637–674`.

Normal Bingo completion clears the ongoing record without consistently saving a complete immutable result/tombstone. Completed seeds are held in RAM. The active tombstone check in the store hides/removes ordinary ongoing records before the restore branch can display their result. Live result-recording calls also omit the stable result ID even though the repository supports it.

Terminal messages are now retained, which is an improvement over the older implementation. However, retention is on the common room topic and can be a final PICK/BINGO packet rather than a complete accepted recovery state. A guest many turns behind cannot apply that last move through the normal open-turn validator without catch-up. A later match's retained final message replaces the previous match's retained message.

**Scenario:** Stuart was closed when Kevin won and Kevin then closes too. Stuart may have only an unfinished local checkpoint, encounter WAITING/closed metadata, or receive a last winning move he cannot apply. A crash between recording statistics and clearing the ongoing record can replay the same result under a new random history ID.

**Correction:** Persist an immutable match result with accepted history, roster/departures, winner/runners and stable account-scoped result identity before clearing active recovery state. Provide a route to display that result after restart. Recovery must obtain sufficient accepted state even when the winning phone is closed. Do not infer finality from packet text. A store probe confirms a tombstoned legacy match is absent from the normal ongoing lookup.

### R22 — P1: Host recovery of a winning pick can emit an invalid/incomplete final snapshot

**Locations:** `RootNavGraph.kt:2448–2506`; `MultiplayerSnapshotReconciler.kt:59`.

When recovered picks produce Bingo, the host sets the result but applies `recoveryResult.nextTurnNumber` only in the non-winning branch. The following sync includes a pick from the still-current turn, omits the result fields, and does not mark the snapshot finished. Periodic active sync then stops because `isGameOver` is true. Snapshot validation rejects a nonterminal history entry whose turn is not closed.

**Scenario:** Kevin missed Stuart's winning pick packet, then recovers it through GAME_SYNC. Kevin finishes locally, but another guest can reject the emitted sync and remain on the unfinished game.

**Correction:** Commit recovered picks and their resulting turn state before evaluating/publishing the final presentation. Emit a complete validated terminal state with the accepted winner/runners and closed-turn identity. A production probe reproduces the invalid-turn snapshot rejection and verifies that a properly closed turn is accepted.

### R23 — P1: A temporary room-lookup failure permanently deletes the rejoin checkpoint

**Locations:** `MainMenuScreen.kt:140–164`; `OnlineRoomRegistry.kt:187–218`, `:373–395`.

Main Menu clears the ongoing match whenever `getRoom()` returns null or a status other than PLAYING. Registry lookup returns nullable data and falls back between HTTP and MQTT; transport failures/timeouts can also produce null. The caller cannot distinguish confirmed deletion from unavailable metadata.

**Scenario:** Stuart reopens the app during a brief outage. Both metadata lookups fail. His valid saved board is deleted. Restoring internet does not bring back the rejoin card. WAITING metadata written after another player's result or host group exit can cause the same destructive cleanup before result recovery.

**Correction:** Use distinct lookup outcomes such as found, confirmed expired/closed, and temporarily unavailable. Preserve the local checkpoint on transport errors; retry and offer recovery once connected. Expire only from the specified inactivity decision or an accepted final/permanent-exit state.

### R24 — P1: Players sharing a display name can receive inconsistent boards

**Locations:** `LobbyLifecycleEngine.kt:379–407`; `RootNavGraph.kt:354–364`, `:1138`, `:1160–1176`.

`isPlayerMe(Player)` can return true for a different UID and username if the display name and host flag match. Automatic board generation uses it to select the local board; in a group, two guests can validly have the same display name.

**Scenario:** Guests Stuart and Bob both display “Alex”. On Stuart's phone, Bob's board can also be identified as the local board and overwrite the selected local card. Kevin and Stuart then disagree about Stuart's board and when it wins.

**Correction:** Match in-game identity by the immutable participant UID. Use display names only for presentation; any legacy alias migration must be explicit and unambiguous. A production-helper probe confirms distinct guests with the same display name are treated as the same player.

### R25 — P2: Presence does not reliably recover after network reconnect

**Locations:** `OnlineRoomSyncManager.kt:551–587`, `:639–683`, `:1163–1426`; `RootNavGraph.kt:530–539`, `:266–300`, `:2849–2925`.

Automatic MQTT reconnect sends JOIN/room heartbeat rather than the game ONLINE/rejoin transition consumed by Root. Those ordinary packets do not clear Root's disconnected state. In the other direction, foreground resume clears every participant's availability locally and fabricates fresh observations for all, even peers who remain absent. AWAY also does not clear a previously stored disconnected flag.

**Scenario:** Stuart loses and regains internet without leaving the foreground. Kevin may keep him red and give short turns until another event repairs presence. If Kevin resumes while Stuart is still offline, Kevin temporarily assumes Stuart is online and grants a full turn instead.

**Correction:** Reannounce the local current game presence after connection/subscription restoration, and recover peer status from current evidence rather than clearing all peers. Define ordered, mutually exclusive presence transitions and carry enough current presence in catch-up to choose consistent turn budgets. Do not infer a remote return from the local phone's resume.

### R26 — P2: Manual-board readiness uses different participant sets on different paths

**Locations:** `RootNavGraph.kt:2135–2142`, `:4258–4262`, `:4316–4329`, `:1330–1338`.

The incoming BOARD_READY path waits for every participant's board. The local Ready/waiting UI filters disconnected participants out of the expected set, while countdown initiation still uses the full roster. This can start a local game without all eligible cards or leave phones disagreeing about whether preparation is complete.

**Scenario:** During manual setup, Stuart disconnects before his board is delivered. Kevin presses Ready and counts only connected players, allowing his countdown to start. Stuart remains in the match roster, but Kevin has no accepted Stuart card to mark or evaluate when he returns.

**Correction:** Use one preparation roster and one all-required-boards condition across send, receive, waiting and countdown. Do not treat temporary absence as implicit roster removal. Preserve previously accepted manual boards and defer match start until the agreed preparation condition is satisfied.

### R27 — P2: Required banner colors and return/pass wording are incomplete

**Locations:** `GameScreen.kt:1067–1114`; avatar rings in `GameBottomBarComponents.kt:167–172`, `:352–358`.

Away/offline banner colors and remote avatar ring colors use the requested values. The normal online banner uses white/black instead of green. It does not display the required passing-in-N-seconds or welcome-back choosing message, and the returning state is not tied to the one-use turn promotion.

**Scenario:** Stuart reconnects into an absence-started turn. The timer changes, but the banner does not explain the welcome-back grant. An online choosing banner is not green as specified.

**Correction:** Render the documented banner text/color from the accepted presence and turn-budget/promotion state. Keep existing intentional flavor text unless it conflicts with an explicitly required state message.

### R28 — P2: Multiplayer results bypass the documented rematch/closed-room buttons

**Locations:** `GameScreen.kt:909–980`; `RootNavGraph.kt:4631–4638`, `:5034–5118`.

Root always supplies `onReturnToLobby` for online/Nearby games. GameScreen takes that branch before considering `isLobbyAvailable`, so it shows Return to Lobby instead of the specified rematch/Main Menu alternatives. The availability flag itself is calculated from local player/disconnect state, not confirmed room existence.

**Scenario:** The room was closed after a match. Results still show Return to Lobby. Alternatively, an offline participant hides availability even when the room exists. The intended “closed room → only Return to Main Menu” rule is not enforced.

**Correction:** Use a real room-lifecycle result to select results actions. If the room exists, offer the documented rematch action; if confirmed closed/dissolved, offer only Main Menu. Unknown/unreachable metadata must not be treated as confirmed closure.

### R29 — P2: Removing the dedicated watchdog did not remove periodic match signaling

**Locations:** `RootNavGraph.kt:1239–1240`, `:1813–1845`; `OnlineRoomSyncManager.kt:639–705`; additional social presence loop at `PresenceManager.kt:478–488`.

The dedicated match heartbeat/watchdog is stopped, but every active game still emits a full GAME_SYNC every 2.5 seconds. The room manager separately sends 2.5-second heartbeats, compatibility requests and cloud reconciliation, plus one-second ping traffic. Social presence has its own 2.5-second loop. The source therefore does not implement the stated zero-heartbeat design.

**Scenario:** Both players are idle in the game; full histories and room metadata continue to be exchanged even without picks, presence transitions or recovery requests.

**Correction:** Agree which existing lobby/ping/social features must remain, then make gameplay replication event-driven with bounded recovery requests. Avoid duplicating full-state polling. This review did not measure battery consumption or gameplay latency, so it does not attribute a measured delay to these loops or recommend silently removing friends presence.

### R30 — P2: Catch-up chat messages are saved but not shown in the mounted game screen

**Locations:** `RootNavGraph.kt:2633–2639`, `:4724–4726`; `GameScreen.kt:128`, `:189–194`, `:298–306`.

Root merges recovered chat into `matchChatHistory`, but GameScreen remembers its initial list and only applies updated lists when `useAcceptedChatHistory` is true. Root never supplies that flag, so it remains false. The single-message effect cannot deliver an entire recovered history and filters older messages. Snapshot chat objects also retain the sender phone's `isSelf` orientation.

**Scenario:** Stuart returns and the snapshot contains messages sent while he was away. They exist in Root/storage but do not appear in the currently mounted chat. After another mount they may appear with the other phone's self/remote alignment.

**Correction:** Feed the accepted merged history into the UI and deduplicate by stable message identity. Persist sender UID and derive alignment for the receiving account. This is a related recovery regression; the updated rules explicitly mention surrender chat, while the broader requirement to preserve chat comes from the existing feature expectations.

## Policy questions — do not treat these as permission to change behavior

1. **Several non-callers finish together, caller below target.** The document deliberately leaves this undecided. `RootNavGraph.kt:1047–1067` currently selects by line count and then turn-order distance. Record that existing behavior and obtain a rule decision before choosing a replacement. This is separate from the confirmed caller-priority bug R12.
2. **Manual pause.** Root's host-only manual pause actions (`:789–864`) and GameScreen's blocking paused dialog (`:539–634`) remain active. The old automatic interruption decision dialogs are unused. The rules prohibit interruption popups and broadly describe equal peers, but do not clearly say whether an intentional manual pause control should be removed. Preserve it until clarified; stale control ordering in R04 is a defect regardless.
3. **Everyone closed: exact clock progression.** Five-minute recoverability is clear. The rules do not explicitly specify whether, while nobody runs the app, turn deadlines continue to elapse and are replayed as passes on recovery, or whether the empty room freezes. Pick one documented policy before implementing that recovery boundary. Both choices must avoid missed-turn replay advantages and automatic defeat.
4. **What counts as player activity.** The specification asks to retain the application's normal activity meaning. The active-game implementation does not currently expose a single consistent human-activity timestamp. Confirm the accepted events, such as valid pick/chat/manual interaction, and whether passive foreground viewing counts. Automatic ticks and network traffic are explicitly excluded already.
5. **Append-only number log is not the entire match state.** With immutable starting cards, numbers reconstruct board marks. They cannot alone distinguish timeout-only turns, explicit departures, selected turn budgets, a welcome-back promotion, or inactivity expiry. Preserve a small ordered record/checkpoint for those decisions rather than pretending an unchanged number list determines every possible live state. This does not imply an external referee.
6. **Nearby/LAN scope.** Root shares the rules flow with Nearby, but `LanP2pSessionManager.kt:259–269`, `:360–369` converts socket EOF into LEAVE/HOST_LEFT, and Root can award host-disconnection victory. If the new rules apply to Nearby as well, this also needs adaptation. LAN reconnect persistence is explicitly rejected at `RootNavGraph.kt:1427–1431`. These mode differences are not included as additional online findings.
7. **Ongoing-card Dismiss.** `MainMenuScreen.kt:329` intentionally clears the local checkpoint without a permanent departure packet. Clarify whether Dismiss means merely hide the card or permanently abandon participation; do not silently convert the existing button into surrender.

## Behaviors confirmed correct in the reviewed paths

- Both automatic and manual boards set the Bingo target to board size; line detection covers rows, columns and diagonals.
- Validated direct picks mark every available participant board without excluding temporarily offline players.
- The outcome evaluator handles the simple “Kevin below target, Stuart completes target” case and caller priority correctly before the defective downstream result branches.
- Duplicate/closed-turn direct picks and timeout packets are rejected by the reducer using match and turn identity. These guards are valuable, though alternate lifecycle handlers bypass them.
- Normal rotation now passes only explicit surrender IDs as exclusions; an ordinary offline flag alone does not remove that player from this rotation helper.
- The surrender reducer rejects duplicate permanent exits and advances a surrendered current turn only once; surrender during someone else's turn preserves that turn's remainder.
- Active timeout execution passes using a sentinel and does not choose a random number.
- Received BOARD_READY is now restricted to manual setup, validates a complete card, and ignores an already accepted card. The older late/malformed-card issue should not be carried forward as an unchanged finding.
- Manual start jobs capture match/room/account, check the route around countdown waits and are canceled on leaving setup. The older unguarded manual-countdown finding is not current.
- The host's recovered-pick handler now guards paused/finished matches. Its winning-transition defect is the narrower R22.
- Settings and kick screen transitions now contain lobby-phase checks; the delayed lobby-expiry problem remains a separate branch, R04.
- Restore explicitly chooses ONLINE_ROOM. No active recovery branch was found that deliberately converts an online match to AI.
- Old interruption choice and host-disconnected dialogs have no live invocation; their presence as files does not prove that they appear in this version.

## Test results and evidence

### Existing repository tests

Executed against the reviewed working tree:

```text
./gradlew :app:testDebugUnitTest --rerun --console=plain
56 suites, 640 tests
639 passed, 1 failed, 0 errors, 0 skipped
Gradle result: BUILD FAILED because of the failed test
```

Failure:

```text
MultiplayerDepartureAndFinalityTest
explicit host surrender result remains valid without fabricating a Bingo
MultiplayerDepartureAndFinalityTest.kt:183
Expected empty confirmed winner; actual confirmed winner: host
```

The test rejects accepting a reason-only/nonterminal surrender result. The current reconciler accepts it, consistent with R11. This is a relevant production validation failure, not a device setup failure.

The suite also contains tests which do not execute the current running code. For example, `SimplifiedCleanMultiplayerTest.kt:54–64` defines its own 3-second budget function and asserts 3 seconds; production now contains 5 seconds. Its welcome-back and UI-color tests similarly reproduce local sample logic. Older simulations exercise the unused coordinator/controller policy. Passing those tests does not verify the new 5-second policy or the Root timer/UI wiring.

### Additional production-helper probes

A standalone Kotlin/JUnit probe file was created **outside the repository** and compiled against the freshly built production classes. **17/17 probes passed their observation assertions**: three positive controls and fourteen demonstrations of current defective/helper-boundary behavior. Passing an observation assertion means the described behavior was reproduced; it does not mean that behavior complies with the rules.

| Probe | Observation |
| --- | --- |
| Board targets, sizes 5–10 | Target equals size; marking complete board produces Bingo. |
| Offline participant marking | Valid pick marks all boards; duplicate is rejected. |
| Ordinary rotation after offline flag | Offline participant remains in future helper rotation. |
| Same display name, different guest UID/username | Incorrectly identified as local player. |
| Healthy player's remote timeout | Adds disconnected status and one missed turn. |
| Non-host, non-owner fallback timeout | Rejected remotely, while host-origin version is accepted. |
| Group guest result authority | Still requires host confirmation. |
| GAME_OVER on empty boards | Accepted without Bingo or surrender. |
| BINGO_CLAIMED with a reason on empty boards | Accepted without Bingo. |
| Disconnected legitimate Bingo winner | Rejected by result reducer. |
| Finished snapshot naming an unknown winner | Confirmed without a participant board. |
| Nonterminal reason-only surrender snapshot | Confirmed without departure receipts. |
| Peer snapshot while original host absent | Rejected; host version accepted. |
| New seed with old checkpoint | Early new-match snapshot rejected until checkpoint reset. |
| Winning history with unclosed current turn | Nonterminal snapshot rejected; closed-turn/terminal alternatives accepted. |
| Six-minute inactive stored matches | Both 1v1 and group are retained. |
| Local result tombstone plus legacy ongoing match | Tombstone survives, normal ongoing lookup returns null. |

### Evidence directory

`/home/bob/workspace/bingo-simplified-rules-review-evidence-20261018/`

- `before-hashes.json`: hashes of the 310 pre-existing tracked/untracked files.
- `baseline-status.txt`: pre-review working-tree changes.
- `SimplifiedGameRules.reviewed.md`: exact specification reviewed.
- `source-inventory.json`: paths, line counts and hashes for the related source inventory.
- `packet-branches.json` and `root-flow-index.txt`: incoming-handler and state/effect navigation indexes.
- `unit-tests.log` and `test-results/`: Gradle output and XML results.
- `RulesComplianceProbe.kt`, `probe-compile.log`, `probe-results.log`: standalone production-helper probes.
- `after-verification.json`: final source/APK preservation check.

No emulators, real-phone games, live MQTT rooms, FCM delivery or backend deployment were exercised in this review. Android process-death scheduling, actual network delays, animation rendering, exact cross-device countdown alignment and battery consumption require device/integration testing after the source mismatches are resolved.

## Coverage and active/legacy distinction

| Area | Related code reviewed / traced |
| --- | --- |
| Entry, start, rematch and screen transitions | `RootNavGraph.kt`, `MainMenuScreen.kt`, `MainContainerScreen.kt`, `LobbyScreen.kt`, `ManualBoardDesignScreen.kt`. |
| Cards, marks, targets, rotation and recovery order | `Models.kt`, `BingoEngine.kt`, `ManualBoardEngine.kt`, `LobbyLifecycleEngine.kt`, direct pick/outcome/timeout callbacks. |
| Packet production and validation | `MultiplayerGameplayReducer.kt`, `MultiplayerSnapshotReconciler.kt`, `MultiplayerResultAuthority.kt`, `FastPacketCodec.kt`, root dispatcher. |
| Room connection, LWT, reconnect and metadata | `OnlineRoomSyncManager.kt`, `OnlineRoomRegistry.kt`, `RoomRegistryGateway.kt`, lifecycle callbacks, relevant `PresenceManager.kt` paths. |
| Persistence, recovery cards and result recording | `OngoingMatchStore.kt`, Root save/restore/finalization calls, Main Menu verification, `AuthRepository.recordMatch`. |
| Presence, timer and result presentation | `GameScreen.kt`, `GameBottomBarComponents.kt`, `BingoComponents.kt`, `BingoCell.kt`, `InGameChatSpace.kt`, `GameOverCelebration.kt`. |
| Lifecycle and audio boundaries | `MainActivity.kt`, `AppLifecycleObserver.kt`, `BingoSoundEffects.kt`, `SoundPreferences.kt`; shared AI invocation distinguished from online taps. |
| Nearby/shared transport boundary | `LanP2pSessionManager.kt`, `LanDiscoveryManager.kt`, Root transport routing and shared exit/recovery callbacks. |
| Legacy code reachability | `ReliableMatchClient`, `ReliableMatchCoordinator`, `MultiplayerMatchLifecyclePolicy`, `OnlineMatchHeartbeatPolicy`, `MultiplayerLifecyclePacketGate`, old dialog composables. |

Every branch of Root's current incoming packet switch was included in the trace:

- Start/preparation: START_GAME, GO_TO_LOBBY, PLAY_AGAIN, BOARD_READY.
- Gameplay/results: PICK_NUMBER, TURN_TIMEOUT, BINGO_CLAIMED, GAME_OVER, SURRENDER.
- Availability/recovery: REJOIN_GAME, GAME_SYNC, GAME_SYNC_ACK, SYNC_REQUEST, PLAYER_PRESENCE, PLAYER_DISCONNECTED, LEAVE, HOST_LEFT.
- Existing manual controls: PAUSE_EXTEND, GAME_PAUSED, GAME_RESUMED.
- Lobby/settings: SETTINGS_UPDATE, LOBBY_EXTEND, LOBBY_EXPIRED, KICK_PLAYER, PLAY_AGAIN_REQUEST.
- Chat/feedback: EMOTE, CHAT_PHRASE, CHAT_TYPING, CHAT_MESSAGE.

Legacy cleanup should follow functional corrections, not substitute for them. Root does not construct/use `ReliableMatchClient`, `ReliableMatchCoordinator`, `MultiplayerMatchLifecyclePolicy` or the lifecycle packet gate. Dedicated heartbeat methods remain in the manager but Root stops that subsystem instead of starting it. Old 60-second host-wait and interruption-policy tests therefore do not establish current compliance. Do not reintroduce those superseded rules or count their absence as bugs.

## Suggested correction order

1. Unify state-changing validation, permanent eligibility and result proof: R01–R04, R09, R11–R17, R24. This prevents incorrect wins and accidental permanent removals.
2. Make the live turn record and peer acceptance rules consistent: R05–R08. Preserve the specified 30/5 behavior, single welcome-back grant and no missed-turn recovery.
3. Make recovery/finality durable and match-scoped: R10, R18–R23, R25. Include the Main Menu path; a sound game reducer cannot recover a checkpoint the menu deleted.
4. Finish preparation, UI, signaling and chat integration: R26–R30. Preserve intentional social/lobby/manual features pending the listed policy decisions.
5. Update tests to call the actual shared implementation and add lifecycle/integration coverage. Then test two and several devices under repeated backgrounding, reconnect, duplicate/delayed packets, process death, explicit exit, host exit, simultaneous Bingo, result return and inactivity expiry.

These are implementation groupings for the recorded findings, not additional gameplay rules. This review establishes source mismatches and executable helper evidence; it does not certify that no other bug can occur.
