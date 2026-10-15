# Bingo 1v1 heartbeat implementation instructions

Prepared: **2026-10-15 09:25 IST**. Status: **implementation specification; no application changes made by preparing this file**.

## Task for the implementing agent

Implement the agreed heartbeat, interruption-popup and recovery policy as **one complete delivery**. Work autonomously through small internal code/test chunks; do not stop for approval between ordinary chunks. This document does not prescribe separate release phases.

Protect the baseline, keep a written change journal, test the real integration and deliver an honest result. Do not call the work complete while required behavior is failing or untested. Do not promise that a finite test suite proves every possible failure impossible.

## 1. Protected baseline and scope

| Item | Exact value |
| --- | --- |
| Repository | `https://github.com/VamsiReddyBora/BINGO.git` |
| Original working directory | `/home/bob/workspace/bingo_multiplayer` |
| Protected backup branch | `1.4.1` |
| Protected baseline commit | `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9` |
| Original release tag | `v1.4.1` points to the older `c01252767d96185e503f9b9dcefa11f1cc1fc02d`; it is **not** the restored baseline |
| Baseline signed APK | Original working directory's `Bingo.apk` |
| Baseline APK SHA-256 | `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc` |
| Baseline verification | 304 JVM tests, 30 suites, zero failures/errors/skips; lint 0 errors / 47 warnings |

Apply the new policy to an **ongoing online match with exactly two immutable match participants**. Do not enable it by checking the changing lobby/player-list size: that list can shrink during the very outage being handled.

Preserve AI, LAN, online group matches, lobby behavior, login, QR, friends, leaderboard, statistics, scorecards, chat, typing, themes, sounds and signing configuration. Their ordinary behavior must not change. Additive match metadata and narrowly scoped fixes required for this policy are permitted. Avoid unrelated cleanup, dependency upgrades and architecture rewrites.

Keep MQTT as the gameplay transport. Do not restore experimental ZIP code, introduce per-move HTTP requests, deploy a backend, publish a release, update remote `main`, move a tag or push over `1.4.1` as part of this task. The older `.gemini/rules/github_release_policy.md` instruction to push release builds to `main` is superseded by the user's explicit baseline-protection instruction here.

### Work in an isolated checkout

Read `AGENT_HANDOFF.md` and the two linked review reports first. Verify the baseline exists locally and on the backup branch. From the original repository, create a new worktree:

```bash
git rev-parse --verify a13d39edb0ff6a67d272cac187f1ed9fb294f9e9^{commit}
git worktree add -b feature/heartbeat-1v1 /home/bob/workspace/bingo_heartbeat_work a13d39edb0ff6a67d272cac187f1ed9fb294f9e9
```

If that branch or directory already exists, inspect it and preserve its work; choose an unused name when necessary. Never use `-B`, `reset --hard`, `clean -fd`, force-push or a blanket restore over an existing checkout. Keep all implementation edits and builds inside the new worktree. Do not overwrite either original baseline APK copy, including `/home/bob/workspace/Bingo.apk`.

Copy this specification into the feature worktree's `docs/HeartbeatImplementationPlan.md`. Record baseline hashes for tracked source/resources, Gradle files and APK in the journal directory. Hash configuration/signing files without printing their contents. Configure the SDK locally; do not commit SDK paths or credentials.

## 2. Exact agreed gameplay policy

| Setting | Required value / rule |
| --- | --- |
| Heartbeat interval | **2,500 ms** while foregrounded and participating in the online match |
| Unavailable threshold | **15,000 ms since the last verified fresh peer heartbeat** |
| Watchdog scheduling | One observer job; check at most every 250 ms; elapsed time decides eligibility |
| Recovery before another episode can count | Complete state synchronization, then at least **10,000 ms** of stable verified heartbeats |
| Stable-heartbeat maximum gap | **5,000 ms**; a larger gap restarts the recovery-stability window |
| Normal turn duration | **30,000 ms**, independent of heartbeats |
| First / second interruption | Offer **Pause game** and **Continue countdown** |
| Third and later interruptions | Also offer **Claim victory**, while that opponent is still unavailable |
| Automatic defeat | **Never** from silence, backgrounding, app closure or missed turns |
| Normal finish | Legitimate Bingo, explicitly confirmed surrender, or an explicitly accepted eligible victory claim |

"15 seconds" is the **silence threshold**, not the heartbeat sending interval. Count separate outage episodes, not missing packets or missed turns. A continuous absence of 45 seconds, five minutes or longer is still **one episode**.

Counts are per **match ID and affected player UID**, cumulative within that match. Recovery does not reset the count. A new match/rematch receives a new match ID and zero counts. Account changes must not share counters or unfinished matches.

Do not diagnose the cause of absence. Use **"Opponent not responding"**. Returning after a short interruption that never reaches the threshold creates no incident and no popup.

## 3. One small policy owner, separated from UI

Create a small pure Kotlin policy reducer, for example `OnlineMatchHeartbeatPolicy`, with an injectable monotonic clock. It owns availability state, episode identity/count, stable recovery and popup/claim eligibility. Its events return decisions; they do not directly edit boards or award results.

Use one match-scoped coordinator to serialize policy events with accepted pick, timeout, pause, resume, synchronization and result events. Do not spread counter changes across composables, MQTT callbacks and timer coroutines. UI collects state and submits requests.

Required per-peer states:

| State | Meaning |
| --- | --- |
| `SYNCING` | Startup/local recovery; peer failure cannot yet be judged |
| `RESPONSIVE` | Fresh peer responses and synchronized state |
| `UNREACHABLE` | Qualified 15-second silence; one open episode |
| `RECOVERING` | Peer responds again; synchronization/stability is in progress |
| `LOCAL_UNAVAILABLE` | This device cannot reliably observe its peer; do not penalize the peer |

Track at least match ID, policy version, peer UID, connection generation, last accepted sequence, last fresh response time, episode ID, committed episode count, whether the episode remains open, recovery start, match revision and result status. Keep turn timing in the match state, not in the availability reducer.

An initial handshake establishes the first observation time. Do not treat an unset heartbeat time as an infinitely old peer. Use `SystemClock.elapsedRealtime()` for live elapsed durations; wall-clock timestamps are for logs. Never compare elapsed clock values from different phones, process/boot generations or old saved sessions.

## 4. Heartbeat delivery and freshness

The baseline room heartbeat mixes MQTT sending with cloud reconciliation. Give the match heartbeat a separate, cancellable job so a slow cloud request cannot stop its schedule. Maintain the existing lobby/social heartbeat behavior independently. There must be exactly one new heartbeat job per match connection and one watchdog; stop old jobs before starting replacements.

Use a small dedicated versioned MQTT packet, rather than assuming every old `HEARTBEAT`, chat message, cloud `lastSeen`, retained status or broker ping proves this match participant is currently available.

Required identity: protocol version, match ID, sender UID, intended peer UID, per-connection random generation, sequence and challenge ID. Reject packets for another match, another account, a superseded connection or the wrong peer. Use a fresh connection generation on MQTT reconnection, not only on manager construction.

To prevent queued old heartbeats from falsely proving recovery, implement the 2.5-second heartbeat as a lightweight challenge/reply:

1. Each foreground match participant sends a new random `PROBE` every 2.5 seconds, starting immediately after subscription/handshake.
2. The foreground peer immediately replies with `ACK`, echoing the exact challenge and addressing the requesting connection generation.
3. Accept an ACK once, only for an outstanding challenge sent by this observer within the previous **5,000 ms**, from the validated current peer connection. Arrival time on the observer's monotonic clock becomes `lastFreshPeerResponseAt`.
4. Expired, duplicate, unmatched and superseded ACKs have no availability effect. Connection replacement requires a fresh handshake/challenge; an unknown generation cannot displace a current generation merely by publishing a packet.
5. PROBE arrival alone is not proof of a working round trip. Keep heartbeat traffic non-retained, QoS 0 and disposable. Do not queue, persist, retry or replay old heartbeat attempts on reconnect. Existing durable gameplay/control messages need separate acknowledgments and deduplication.

Pause does not stop heartbeats. Backgrounding stops this foreground-participation heartbeat; on return, immediately handshake and synchronize. Do not rely on an exit callback or a background timer continuing to run.

Before blaming the opponent, require: this app is foregrounded, MQTT is connected, the room subscription is confirmed, a recent application-level self-echo/probe proves the broker receive path within 5 seconds, and local observation is initialized. `isConnected` alone is insufficient.

When local health is lost: cancel claim eligibility and peer-failure judgments, preserve existing episode records and suspend unsafe match actions. On local recovery, first reconcile shared state, then establish a fresh observation window. Do not count the entire period this observer was offline against the other player. Broker reconnection does not itself create an opponent incident.

## 5. Exact episode and recovery transitions

- At `silence >= 15,000 ms`, while local observation is healthy, create one episode if none is open. Commit its unique ID/count through the match coordinator, persist it and show its popup once.
- Continued silence never increments the same episode. Duplicate observer events and duplicate packets must resolve to the same committed event, not add counts on each phone.
- On the first verified fresh peer response, immediately disable **Claim victory** and enter `RECOVERING`. Do not let slow board synchronization keep a responsive player eligible for a claim.
- After state synchronization, clear the unavailable popup and show one recovery notification. Maintain the open episode internally until the 10-second stability requirement is met.
- Stable recovery requires verified responses with no gap greater than 5 seconds. If a larger gap occurs, restart the stability window.
- If a new 15-second silence occurs before stable recovery completes, reopen the warning for the **same episode**, without adding another incident. Avoid repeatedly presenting the same modal; keep its actions accessible through a banner/status action.
- After 10 stable seconds and completed sync, close the episode and re-arm detection. Only a later qualified outage can increase the count.
- Restarts restore episode/count/claim records before any eligibility decision. They do not manufacture a new episode from a missing in-memory timestamp.

Example: A is absent for 20 seconds, returns and synchronizes, stays responsive for 10 seconds, then disappears for another 15 seconds. That is two incidents. If A disappears again before completing the stable recovery window, it remains the first incident.

## 6. Popup, pause, countdown and claim behavior

Popup title: **Opponent not responding**. Body: **No heartbeat received for 15 seconds. Interruption N in this match.** Use the existing theme/button tokens and readable labels; avoid unrelated redesigns.

Opening, dismissing or rendering the popup does **not** pause gameplay. Continue the existing authoritative countdown unless an accepted pause action changes match state. A number can still be picked when legally permitted. Do not cover the turn display with an undismissable dialog while time keeps running without a visible countdown.

| Action | Required implementation |
| --- | --- |
| Pause game | Submit an idempotent action with expected match revision and turn ID. On acceptance, freeze exact remaining milliseconds; persist and synchronize pause owner/reason. A pause must affect the shared match, not one screen. |
| Continue countdown | Dismiss the decision popup without altering the turn ID/deadline. If paused, use an explicit accepted resume action. Every subsequent turn remains a full 30 seconds. |
| Resume game | Available to the locally active participant when the coordinator can safely accept it. Resume the frozen remainder, not a fresh 30 seconds. Reconnection alone never resumes a deliberately paused match. |
| Claim victory | Visible only on the third/later open episode while the opponent remains `UNREACHABLE` and this observer is healthy. Require explicit confirmation. Revalidate on acceptance; do not trust old UI eligibility. |

A claim must atomically check current match/episode ID, count >= 3, current peer unavailability, expected revision, actor eligibility and that no result already exists. Resolve claim/recovery/Bingo/surrender races in the same ordered decision path. A newer accepted recovery invalidates an old claim request; a result already committed before recovery remains final.

Persist a stable result/event ID and a separate reason such as `DISCONNECTION_CLAIM`. Record statistics/history once. Do not fake five completed lines or label this as ordinary Bingo. A returning player receives the stored final result. Repeated confirmation, redelivery and restart cannot award the win twice.

## 7. Turn correctness and control ownership

Keep both original participants in the 1v1 turn order while unavailable. Remove the old 1v1 rule that excludes an offline player after three missed turns. A timeout is a turn event; an interruption is an availability event. They are different counters.

Use one deadline per turn and one serialized timeout decision keyed by `(matchId, turnId)`. A stale timer captures the old turn ID and must be rejected after that turn finishes. Serialize pick-versus-timeout races. An old `TURN_TIMEOUT`, `GAME_SYNC`, `LEAVE` or recovery packet cannot advance a newer turn.

Compute the visible countdown from accepted timing state. A UI tick is display-only. Opening dialogs, returning to foreground, receiving duplicate sync and changing turn ownership must not reset it. Synchronize timing in one explicit clock domain; do not directly compare clocks from two devices. Persist remaining duration/pause state with clock-generation metadata for restart recovery. With real transport delay, require countdown displays within one second after synchronization; fake-clock tests must be exact.

**Control ownership safety is mandatory, even though this is a small feature.** Heartbeats detect silence; they do not grant exclusive ownership. Broad baseline conditions such as `isHosting || isActingHost || activeRemaining.size <= 2` are not a safe single-writer mechanism.

- One agreed controller commits turn/pause/incident/result changes. Include its identity and generation/epoch in shared state. Reject commits from old owners and old revisions.
- A returning original host must synchronize before acting and must not automatically take control back.
- An unavailable-controller takeover must have enforceable exclusive ownership and a known accepted checkpoint. A local counter, deterministic tie-break, ordinary retained MQTT message or non-atomic cloud GET/update does not establish that guarantee.
- Inspect the actual baseline's coordination capability. Reuse a proven existing ordering/ownership mechanism if present. Do not add an unapproved service or quietly route every move through HTTP to satisfy this requirement.
- **If safe ownership cannot be established with the available infrastructure, suspend mutations with a visible waiting state and document the unmet takeover requirement.** Do not silently implement two independent controllers or claim that continuous play during controller loss has passed. Complete independently safe heartbeat work in the same delivery and report the precise infrastructure dependency. This is a safety fallback, not permission to describe the full requirement as finished.

The intended behavior is for the remaining active player to continue, subject to that ownership guarantee. Neither phone being active requires no running timer: preserve the last accepted match, suspend it and do not replay a backlog of unattended timeouts when someone returns.

## 8. Rejoin and storage requirements

The baseline cloud room model does **not** hold a complete live match. `OngoingMatchStore` currently uses local SharedPreferences with an eight-minute expiry. MQTT retained room metadata is not a durable full gameplay history. Verify storage instead of assuming it exists.

For the new 1v1 policy, persist a versioned, account-scoped checkpoint after every accepted mutation and before acknowledging it as durable. Use an atomic durable write off the main thread; do not depend solely on asynchronous `apply()` followed by process survival. Log failures and avoid acknowledging progress that was not saved.

Include: match ID/seed/policy, immutable participants and original board layouts/manual-board data, board marks, accepted picks with picker UID **and actual turn ID**, current turn/order, revision, controller identity/epoch, timing/pause state, per-player incident records/recovery status, accepted chat messages with IDs and final result if any. A pick-list index cannot reconstruct turn IDs after skipped turns.

Reuse existing storage only if it supplies this information correctly. Keep periodic cloud checkpoint work outside the heartbeat/turn scheduling path. Do not label a checkpoint cloud-durable until its write is acknowledged. If recovery depends on the live peer, state that explicitly; after both devices restart, reconcile their saved revisions before selecting an owner. Missing trustworthy data requires a waiting/error state, never a regenerated board.

Remove the eight-minute silent deletion for these unfinished 1v1 matches. Keep the checkpoint until an accepted result, an explicitly agreed abandonment or a documented non-penalizing archive policy. Preserve old record readability through defaults/migration; do not erase unrelated stored data.

On foreground/cold launch: restore matching account and match identity, connect/subscribe, perform the fresh handshake, request the latest accepted snapshot, validate/apply it atomically, persist, acknowledge sync, then enable moves. Merge accepted chat by stable message ID. Clear stale ephemeral typing indicators. Preserve a deliberate pause. A completed match shows its final result and cannot be resurrected.

Temporary app background/closure must not publish or interpret **surrender**. Keep an explicitly confirmed surrender distinct from transport loss. Do not switch the game mode to AI when room/player discovery is temporarily incomplete.

## 9. Exact baseline integration map

Paths below are relative to `app/src/main/java/com/bingo/multiplayer/`. Line numbers refer to protected commit `a13d39e`; re-find by symbol after edits.

| Location | Required audit / narrowly scoped change |
| --- | --- |
| `domain/network/OnlineRoomSyncManager.kt`, `RoomMessagePacket` near 21 | Add versioned match/connection/action metadata with compatible defaults; separate match heartbeats from lobby heartbeats. |
| Same file, MQTT setup near 283–305 | Current Last Will is `LEAVE`. Prevent abrupt disconnect/LWT from ending or deleting a new-policy 1v1 match. Validate old-session wills; preserve lobby/group behavior. |
| Same file, heartbeat loop near 375–420 | Current 2.5-second loop awaits cloud reconciliation. Decouple match probes; stop duplicate jobs; guard callback lifetime. |
| Same file, `broadcastPacket` near 485 | Heartbeats must not use the retry queue. Add application acknowledgments/deduplication for state-changing actions; MQTT delivery acknowledgment is not gameplay acceptance. |
| Same file, `handleIncomingPacket`, `LEAVE` near 925 | Registry removal must not erase immutable participants or trigger premature 1v1 host-loss outcomes. |
| `domain/network/FastPacketCodec.kt`, encode/decode | Existing `H|`, `P|` and `T|` paths drop newly added fields unless changed. Use the JSON fallback for new-policy packets or extend both codec directions with tests. Preserve legacy formats outside the new policy. |
| `domain/network/OngoingMatchStore.kt` near 15–45 | Add scoped durable policy/checkpoint fields; remove silent eight-minute expiry for these matches; handle write failures and old schemas. |
| `presentation/navigation/RootNavGraph.kt`, foreground observer near 420 | Replace unconditional disconnect clearing/reconnected messages with the coordinator's validated recovery. Never reset incidents on foreground. |
| Same file, `togglePause` near 665 and handlers near 2104 | Pause/resume must preserve and synchronize remaining time and expected revision. |
| Same file, `calculateNextTurnPlayerId` near 906 | Preserve both 1v1 participants despite incident/missed-turn counts. |
| Same file, `executePick` near 1100–1400 | Route acceptance through a single serialized decision; ensure durable state, exact turn IDs and idempotent result recording. |
| Same file, `TURN_TIMEOUT` near 1789, `GAME_SYNC` near 1986 | Remove new-policy auto-forfeit and fresh-30-second sync resets; reject stale/duplicate revisions and skipped-turn reconstruction errors. |
| Same file, surrender/departure handlers near 2198–2475 | Keep explicit surrender. Bypass transport-loss auto-victory and immediate skip paths for new-policy 1v1. |
| Same file, `SYNC_REQUEST` near 2518 | Return one validated authoritative snapshot, including incidents/timing/pause/chat; eliminate competing responders for this policy. |
| Same file, countdown effect near 3964 | Bypass the disconnected-player 2-second/15-second fast skip, missed-turn-forfeit block and overlapping fallback timeout paths for new-policy 1v1. Use one normal deadline. |
| `domain/engine/LobbyLifecycleEngine.kt`, `isPacketForActiveMatch` near 368 | Its legacy acceptance of unseeded packets is unsuitable for new-policy gameplay. Enforce explicit new match identity in the adapter without breaking legacy/lobby tests. |
| `presentation/game/GameScreen.kt`, pause/exit UI near 469–575 | Wire the incident popup/actions, preserve theme tokens, display timing and disable stale claims. Keep confirmed surrender distinct. |

Search all remaining `LEAVE`, `HOST_LEFT`, `TURN_TIMEOUT`, `isHostLeftGame`, `consecutiveMissedTurns`, `turnTimer = 30`, ongoing-match clearing and automatic result paths. This map is a starting inventory, not permission to ignore another live caller. Do not bring the test-only Firebase/Nearby session models into production.

Negotiate the new policy version before starting a match. Both clients must support it. A legacy peer must not enter a match labeled as using the new rules; show an update/compatibility message. Once chosen, a match's policy cannot flip because an observer disconnects or a UI flag changes.

## 10. Mandatory change journal and runtime evidence

Before the first code edit, create `artifacts/heartbeat-1v1/CHANGELOG.md`, `BASELINE_MANIFEST.json` and `TEST_REPORT.md` in the feature worktree. Copy the plan into `docs/`. Initialize the journal with baseline/worktree/branch/HEAD and a list of proposed files. Append an intention **before** each logical edit chunk and append its outcome afterward. Do not replace failures with later successes; preserve both.

Use this entry format:

```markdown
### Change 001 — <precise purpose>
- Started / completed: <IST timestamps>
- Checkpoint before edit: <commit SHA>
- Files and symbols: <exact paths and functions>
- Previous behavior: <what this chunk replaces>
- New behavior: <what it now does>
- Scope guard: <why AI/LAN/group/lobby behavior is preserved>
- Storage / packet changes: <defaults, compatibility, migration>
- Validation: <exact command/test IDs, actual result, evidence path>
- Remaining concern: <specific issue or none>
- Rollback: <checkpoint / commit to revert; data compatibility notes>
```

Commit each tested logical chunk locally, staging only intended files. Include its journal entry in that commit. Record resulting commit IDs in the next entry or final report; do not create a self-referential commit-hash loop. Keep a final ordered commit list so another agent can identify every change.

Record bounded JSONL runtime traces separately for Kevin and Stuart. Each important event includes UTC/IST display time, local monotonic time, device/account alias, match/connection/episode/action IDs, controller epoch, revision, turn ID, remaining milliseconds, peer status/count, local transport health and acceptance/rejection reason. Log state transitions and relevant probe IDs; do not flood the UI, log credentials or store unlimited traces.

For device tests, record action time, expected behavior, actual behavior, both screen captures, relevant log ranges and `PASS`, `FAIL`, `BLOCKED` or `NOT RUN`. A screenshot alone cannot prove that only one timeout/result was committed.

## 11. Required tests in the same delivery

Use a fake monotonic clock for exact boundary tests; do not wait real minutes for unit tests. Inject delivery faults for integration tests. Use automated ADB/UI actions for gameplay so screenshot analysis does not consume the 30-second turns. Use only the test accounts; do not invite `@bob` or alter/reset player data without a separate instruction.

| ID | Scenario | Required evidence / assertion |
| --- | --- | --- |
| H01 | Healthy heartbeat traffic | No popup/incidents; exactly one scheduled probe job per connection. |
| H02 | Silence 14,999 ms, then 15,000 ms | Zero incidents before threshold; exactly one eligible transition at threshold in fake-clock tests. |
| H03 | Healthy 2.5-second replies with occasional dropped probes | Judge elapsed fresh-response time, not a count of all expected packets. |
| H04 | Continuous silence 45 seconds and five minutes | One episode/count, no automatic result. |
| H05 | Two separate outages after stable recovery | Counts 1 then 2; no Claim victory. |
| H06 | Third separate outage | Count 3; manual Claim victory appears only while eligible. |
| H07 | Return before 15 seconds | No new incident, no unavailable popup. |
| H08 | Return, synchronize, disappear before 10 stable seconds | Same open episode; no extra incident. |
| H09 | Recovery stable-gap > 5 seconds | Stability restarts; no premature re-arm. |
| H10 | Duplicate/old/queued heartbeat, wrong match/account/generation | Cannot prove freshness, erase counts or create a false recovery. |
| H11 | New connection with old Last Will arriving late | New connection stays valid; no result, premature removal or skip. |
| H12 | Local device loses network / broker stops responding | Local recovery state; no new accusation/claim against peer. |
| H13 | Broker-wide outage and return | No double penalties; restore one shared checkpoint before resuming. |
| H14 | Slow cloud request taking > 15 seconds | Independent MQTT heartbeat remains scheduled; no false incident from blocked reconciliation. |
| H15 | Repeat all outage cases with original host and guest swapped | Correct counts/state; demonstrate safe takeover or mark it BLOCKED explicitly. |
| T01 | Disconnect 10 seconds into a 30-second turn | Original deadline remains; no extra 15/30-second reset. |
| T02 | Disconnect with < 15 seconds remaining | Turn may expire before popup; expiry still occurs once at its own deadline. |
| T03 | Several unavailable turns | Every turn is 30 seconds; one skip per expiry; neither player removed from order. |
| T04 | Pick and timeout arrive at same boundary | One accepted transition; no double advance or duplicate number. |
| T05 | Delayed/duplicate timeout after next turn begins | No effect on the new turn. |
| T06 | Several skipped turns followed by picks and rejoin | Restore actual turn IDs, marks and picker attribution; do not derive turns from pick index. |
| P01 | Pause with exactly 12,000 ms left | Both authoritative states freeze at 12,000; resume uses that remainder. |
| P02 | Open/dismiss popup or select Continue | No deadline reset or silent pause. |
| P03 | Return while deliberately paused | Remains paused; counts/chat/board preserved. |
| P04 | Duplicate/stale pause/resume requests | One accepted action; no stale pause/resume applied to a newer state. |
| C01 | Claim on incidents 1 or 2, including forged/stale UI request | Rejected; no statistics change. |
| C02 | Eligible third-incident claim, confirmed once | One final result and one statistics/history update. |
| C03 | Fresh recovery races with claim confirmation | Ordered acceptance decides; recovery accepted first rejects claim. |
| C04 | Claim races with Bingo/surrender; duplicate delivery/restart | Exactly one stored final result; no resurrection or double counting. |
| R01 | Home, app switching, screen lock, swipe-away | Outage/recovery follows heartbeats, preserving match and counts. |
| R02 | OS process kill and Android force-stop | Cold-launch recovery from durable state; do not expect a force-stopped app to reopen itself. |
| R03 | Kill between save, publish and acknowledgment | No acknowledged mutation disappears; duplicate replay is harmless. |
| R04 | Both apps close; reopen after > 8 minutes | Saved match remains recoverable; no unattended timeout backlog. |
| R05 | Both reopen together / one returns with an older snapshot | One owner and monotonic accepted state; no two independent games. |
| R06 | Controller and observer become separated/asymmetric delivery | No split ownership or conflicting result; use visible safety wait if ownership is unproven. |
| R07 | Board/manual board, picks, chat and pause after reconnect | Both clients agree on snapshots, revisions, participants, actual turn IDs and result. |
| R08 | Restart after incident 2 / incident 3 | Counts and open episode persist; restart creates no new incident or stale claim. |
| R09 | Switch accounts; rematch; late old-match packets | No cross-account/match counters, chat, board or result leakage. |
| R10 | Duplicate collectors/recomposition and rapid reconnect cycles | One effective observer/timer/controller; jobs from previous sessions cannot mutate state. |
| R11 | Wall-clock changes and device reboot | Duration logic stays correct or enters explicit recovery; no comparison of unrelated monotonic clocks. |
| G01 | Normal online picks/Bingo/explicit surrender | Existing legitimate results and board rules still work. |
| G02 | AI, LAN, group lobby/game and all restored social/UI features | Baseline behavior remains; document representative device smoke coverage. |
| G03 | Mixed old/new clients | New-policy start rejected cleanly; no silent incompatible match. |
| G04 | Latency | Measure baseline and candidate tap-to-accepted-pick latency under the same setup; report sample counts, median and p95. Explain any new waits. |

Repeat rapid foreground/network/recovery cycles at least 20 times per role. Include complete two-device matches as well as synthetic unit tests. Preserve failed attempts and retest evidence. Report timing resolution/scheduler tolerance on real devices; a scheduled callback cannot be promised to execute at the exact millisecond.

Run appropriate unit/integration checks per internal chunk. Before final delivery run the existing full JVM suite, lint and signed release build in the feature worktree:

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease --offline --console=plain --max-workers=2
```

Use online dependency resolution if the isolated checkout lacks caches. Baseline tests must remain passing unless a test explicitly asserts a retired 1v1 disconnection rule; document and replace such assertions with meaningful new-policy tests. Do not edit unrelated tests merely to make failures disappear. Lint must have zero errors and no new unexplained warnings. Do not fabricate tests, counts or PASS entries from an earlier report.

## 12. Safe checkpoints and rollback

Rollback must preserve investigation evidence and user data. The protected baseline checkout and branch already provide the safest escape path: stop using the feature checkout and use the unchanged baseline. Never reset or delete the feature worktree just to hide a failed implementation.

Before abandoning/reverting a chunk, save its diff, status, logs and untracked implementation files to a separate local rescue directory. A Git diff alone does not include untracked files. Keep configuration/secrets local. Record why rollback was necessary and the last passing checkpoint.

For an independent baseline checkout, choose an unused directory and run:

```bash
git -C /home/bob/workspace/bingo_multiplayer worktree add --detach /home/bob/workspace/bingo_heartbeat_baseline_check a13d39edb0ff6a67d272cac187f1ed9fb294f9e9
```

Verify baseline source fingerprints and the baseline APK hash. To undo already committed feature changes while keeping history, use targeted `git revert` commits on the **feature branch**, with the journal identifying which commits to revert. Do not revert the protected baseline or unrelated work.

Keep storage changes additive and backward-readable. Before installing test APKs, retain account-scoped local state/test-device backups where supported. Returning to the baseline APK must not require uninstalling, clearing app data, resetting statistics or changing signing keys. If a migration cannot meet that condition, document it before changing the schema and use a separate test profile/device.

## 13. Required final delivery

Deliver the candidate source on its feature branch, current change journal, exact commit list, automated/device test report, relevant two-device screenshots/logs and a candidate signed APK with version, signing-certificate comparison, size and SHA-256. Keep the candidate APK under the feature worktree's artifacts; preserve the original baseline APK copies. Update the feature worktree's `AGENT_HANDOFF.md` to describe the new implementation and actual remaining limitations.

Final report must state: baseline reference; changed files/purpose; heartbeat/episode/pause/claim semantics; control-ownership mechanism and its evidence; persistence/recovery behavior; actual tests and failures; device/latency results; backward compatibility; and exact safe rollback path.

Use **READY** only when all mandatory acceptance cases pass with the required evidence and safe ownership is established. Otherwise report **NOT READY** with specific FAIL/BLOCKED/NOT RUN items while retaining all completed safe work. A claim option appearing in a screenshot does not prove claims or recovery are correct.

### Reference documentation

- Use a monotonic Android duration clock: [Android SystemClock](https://developer.android.com/reference/android/os/SystemClock).
- MQTT retained messages and QoS describe transport behavior; game acceptance and ownership need application rules: [OASIS MQTT 3.1.1](https://docs.oasis-open.org/mqtt/mqtt/v3.1.1/os/mqtt-v3.1.1-os.html).
- If evaluating an existing cloud coordination capability, verify atomicity and offline behavior against its actual API; ordinary get/update is not a transaction: [Firestore transactions](https://firebase.google.com/docs/firestore/manage-data/transactions). This reference does not authorize adding a cloud authority or deploying rules.
