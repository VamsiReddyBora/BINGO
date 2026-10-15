# HeartbeatImplementationPlanFix003

Prepared: **2026-10-15, Asia/Kolkata**. This is the complete instruction prompt for the implementing agent. Preparing this plan does not change the application.

## Task and expected delivery

Repair the **eight remaining finding areas F001–F008** in `HeartbeatImplementationFix002Review.md`. Preserve the improvements already working. Implement and verify the correction in **one delivery**, using small internal tested commits and an append-only change journal. Do not return only an architecture proposal, repeat the old implementation with more fields, or stop after making the existing unit suite green.

Fix002 is **NOT READY because production code defects remain**. Its 362 passing unit tests and 12 passing earlier probe assertions do not close the review. The latest review reproduced **10 additional failed contract assertions**: six policy/storage assertions, two independent-process recovery assertions and two codec/match-scope assertions. These support the eight finding areas; they are not ten separately counted bugs.

Read these before editing:

1. `/home/bob/workspace/HeartbeatImplementationFix002Review.md` — latest findings, actual locations and evidence; takes precedence over Fix002's resolution claims.
2. `/home/bob/workspace/HeartbeatImplementationPlan.md` — agreed policy and original 44 acceptance cases.
3. `/home/bob/workspace/HeartbeatImplementationPlanFix002.md` — existing implementation and compatibility requirements, except superseded findings.
4. `/home/bob/workspace/heartbeat-fix002-review-evidence/` — probe sources, `.out` files, runners, executed test XMLs and `ReviewManifest.json`.
5. Current candidate source, root `AGENT_HANDOFF.md`, and existing change journals. Treat reports as claims to verify, not proof that their implementation exists.

All source paths below are relative to the repair checkout. Line anchors describe reviewed commit `326231022f1bdf305e715b31f9c6625c55fca61b`; re-find by symbol after edits. Kotlin files are under `app/src/main/java/com/bingo/multiplayer/`. `RoomMessagePacket` is declared in `domain/network/OnlineRoomSyncManager.kt`.

## Protect the baseline and previous evidence

| Item | Required handling |
|---|---|
| Restored baseline `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9`, branch `1.4.1` | Preserve source, branch and original checkout `/home/bob/workspace/bingo_multiplayer` |
| Original tag `v1.4.1`, commit `c01252767d96185e503f9b9dcefa11f1cc1fc02d` | Preserve; this older release is not the restored baseline |
| First heartbeat checkpoint `fc8bdf0ef460e29dc2e8e410fe41ae81ffd606bd` | Preserve |
| Fix001 `fa3b1ffdd3f517b90e595c1589c6a8290a8c69e0` | Preserve |
| Reviewed Fix002 `326231022f1bdf305e715b31f9c6625c55fca61b`, branch `fix/heartbeat-fix002` | Start correction from this candidate, retaining any later work after inspection |
| Protected baseline APK `/home/bob/workspace/bingo_multiplayer/Bingo.apk` | Preserve; SHA-256 `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc` |
| Shared `/home/bob/workspace/Bingo.apk` | Preserve its current bytes; at preparation it contains Fix002, SHA-256 `6c8b8616727cc9e0b709edbc4ff5878722cbe3a1bd517deebdb52e7e13500631` |

Inspect HEAD, worktrees and dirty files. If the candidate has advanced, inspect and retain that work; do not reset it to the reviewed SHA. Otherwise create an isolated repair checkout:

```bash
git -C /home/bob/workspace/bingo_multiplayer worktree add -b fix/heartbeat-fix003 /home/bob/workspace/bingo_heartbeat_fix003 326231022f1bdf305e715b31f9c6625c55fca61b
```

If the branch/path exists, inspect and preserve it; choose an unused name when necessary. No destructive resets, `git clean`, blanket restores, force-push, protected-branch changes, release publication or deployment. Do not overwrite either protected/shared APK. The older repository release instruction to push builds to `main` does not override this instruction.

Before rerunning a probe, copy its runner/sources, old output and classpath-export helper into a Fix003 evidence directory. The supplied runners overwrite `.out` files and use a fixed temporary classpath file: update **only the copied helper paths** to unique Fix003 paths. Do not run concurrent exports against the same temporary file or overwrite the historical review evidence.

Copy this plan and the latest review into `docs/` in the repair checkout. Create `artifacts/heartbeat-fix003/` and the journal before application edits. Hash sensitive configuration/signing files without displaying their contents.

## Exact behavior to preserve

| Rule | Required behavior |
|---|---|
| Scope | Explicitly negotiated online 1v1 policy, using two immutable match participants; a shrinking live lobby list cannot change policy |
| Foreground probing | Every **2,500ms**, first probe immediately after valid readiness; one effective probe/watchdog job per match connection |
| Failure detection | **15,000ms** without a verified fresh peer response, while this observer is foregrounded and its connection, subscription and receive path are healthy |
| Continuous absence | One open incident, regardless of duration or number of missed turns |
| Recovery | Current accepted state synchronization, followed by **10,000ms** of stable verified heartbeats; gap **greater than 5,000ms** restarts stability |
| Incident counts | Per affected participant and match; survive process restart; reset for a genuinely new match only |
| Incidents 1/2 | Offer **Pause game** and **Continue countdown** |
| Incident 3+ | Also offer manually confirmed **Claim victory**, only while still eligible |
| First fresh returning response | Revoke claim eligibility immediately; do not wait for slow board synchronization |
| Normal turns | **30,000ms** each; one accepted expiry per turn; no extra grace or accelerated skip |
| Deliberate pause | Preserve exact remainder; recovery never silently resumes it |
| Rejoin | Same online match and accepted board/history/chat/turn/timing/pause/incidents or final result; mutations blocked until reconciliation succeeds |
| Both apps absent | Save/suspend; no always-running unattended countdown is required and no timeout backlog is replayed |
| Results | No automatic defeat from backgrounding, disconnection or missed turns; explicit surrender, legitimate Bingo and eligible accepted claims remain supported |

Use **“Opponent not responding”** rather than guessing whether the cause was network loss, app switching or termination. Returning before the silence threshold creates no incident. Dismissing the popup or choosing Continue does not reset time. Keep MQTT as gameplay transport and preserve the baseline AI, LAN, online groups, lobby, QR, social features, statistics, scorecards, chat/typing, themes, sound and signing.

## Make the correction in the real runtime path

Use one small **production match coordinator/state holder** to serialize accepted state changes, with injectable clock, transport and persistence adapters. UI submits intents and renders state; it must not independently advance the turn or declare a result. Retain the existing pure heartbeat reducer, correcting its lifecycle. Extract only the code needed for these guarantees; no framework or unrelated rewrite is requested.

For every new or changed API, identify the actual application caller and its test. A new data field or unused coordinator does not fix a composable that still follows the old path. Remove duplicate new-policy writers after wiring the replacement; preserve legacy branches deliberately.

**Two requirements need a real capability check:** safe control transfer while the original host is absent, and final-result recovery after both app processes have exited. Inspect the existing broker/cloud adapters and their actual access, persistence and atomic-operation guarantees. Record the conclusion and the exact implementation evidence early in `OWNERSHIP_AND_STORAGE.md`.

- A static map, one process-wide singleton or switched fake preferences cannot communicate between phones.
- A retained MQTT owner record is last-writer-wins unless another mechanism prevents obsolete writers; it is not automatically a lock or fencing mechanism.
- A local actor/mutex serializes one process only. A locally incremented epoch and cloud GET-then-PUT do not by themselves prevent two owners.
- A persistent broker may retain a result after apps close, but verify the actual retention, acknowledgement and conflict/authorization behavior. MQTT PUBACK is not proof of every required application-level durability guarantee.
- Do not reintroduce per-move HTTP, silently create an authority service or deploy broker/backend/rule changes. If required infrastructure is missing, implement the safe waiting behavior, complete independent repairs, and deliver the concrete missing capability/configuration requirement as **BLOCKED**. Do not label that fallback as meeting uninterrupted guest play or the full requirement.

Proceed with authorized code/test work without stopping for routine approval between chunks. A missing infrastructure capability does not justify leaving the independent bugs unfixed. It also cannot be concealed by calling the whole feature fixed pending hardware.

## F006 — Complete recovery despite repeated synchronization

**Locations:** `domain/engine/OnlineMatchHeartbeatPolicy.kt:onStateSynchronized()` near 381; `OnlineRoomSyncManager.kt:notifyStateSynchronized()` near 251; `RootNavGraph.kt` periodic GAME_SYNC near 1675, GAME_SYNC completion near 2426 and GAME_SYNC_ACK near 2441.

**Current defect:** every sync callback resets `recoveryStartedAt`. Normal 2.5-second snapshots/ACKs keep it from reaching ten seconds, so later outages remain part of incident 1.

**Implement:**

1. Bind synchronization to match ID, recovery-attempt generation and accepted snapshot identity/revision. Accept an application acknowledgement only for the snapshot/attempt actually awaiting it.
2. Invalidate the previous attempt's sync on a new outage or genuinely lost reconciliation. Preserve the open incident/count across unstable recovery and local restarts.
3. Set `stableSince` once when this attempt first becomes synchronized and peer responses are verified. Do not credit pre-sync time. If synchronization precedes the first returning response, start stability no earlier than that response.
4. Duplicate snapshots/ACKs and newer ordinary gameplay revisions that preserve synchronized continuity must not restart stability. Reject obsolete/wrong-match/wrong-peer ACKs without changing policy.
5. A verified response gap over 5,000ms, local observation failure or actual desynchronization resets the appropriate observation state. A duplicate notification is not desynchronization.
6. Complete recovery after ten stable post-sync seconds, close the incident once, emit/persist the transition once, and re-arm detection for the next distinct outage. Keep claim eligibility revoked during recovery.

**Exact fixture:** outage at 15,000ms; first returning response at 16,000ms; current sync accepted at 26,000ms. With fresh responses at 28,500 / 31,000 / 33,500 / 36,000ms, remain recovering through 35,999ms and complete at 36,000ms. Deliver periodic GAME_SYNC plus matching ACKs every 2,500ms throughout: they must not move the stability start. A separate later outage increments from 1 to 2, then after another stable recovery to 3.

Test both callback orderings at the same fake-clock timestamp, duplicates between responses, newer valid revisions, an old attempt's late ACK, and local health loss during stability. Preserve the 12 earlier probe expectations that still apply.

## F002 — Recover a real shared result and handle failed persistence correctly

**Locations:** `OngoingMatchStore.kt` cache near 87, result record near 274 and lookup near 313; `RootNavGraph.kt:executeClaimVictory()` near 815 and incoming claim/result near 2482; `OnlineRoomSyncManager.kt:broadcastPacket()` near 650.

**Implement:**

1. Remove `sharedMatchResults` as a supposed cross-device data source. If a cache remains for ordinary local optimization, scope it correctly and let it contain only successfully accepted/durable records. Independent production instances/tests must not share hidden process memory.
2. Define one canonical result: unique match ID, stable result ID, immutable participant IDs, winner/draw, canonical reason code, claim episode evidence where applicable, authorized owner/epoch and committed revision. Winner/loser display text is derived from the same reason code; different UI strings must not create conflicting records.
3. Establish shared durable recovery through a verified production adapter. B must retrieve A's accepted result when B missed the MQTT message and A has since closed. Querying local preferences alone is not this recovery path.
4. Atomically accept the first authorized result under the chosen ownership/commit mechanism. Exact duplicate delivery is idempotent; a conflicting winner/result must never overwrite the original. An unguarded read-then-write is insufficient under concurrent callers.
5. Only expose/cache/publish an accepted result after the required durable commit succeeds. If the write fails, return failure, preserve unfinished recovery state and remain retryable. A retry must attempt the required commit, not return true from a failed-write cache.
6. Make publication and recovery retryable across a crash after local save but before shared publication. An offline local intent is pending, not an established shared victory.
7. Apply statistics/history once per durable result identity, including crashes between record, statistics and acknowledgement. Reopening a final match shows its result and cannot enable picks.

**Required evidence:** two independent app/JVM processes with separate local storage and the real shared adapter or an explicitly identified adapter integration environment. Winner accepts result; absent peer misses it; winner process exits; peer starts and gets the same final result. Repeat after broker reconnect/restart if broker durability is claimed. Test failed commits, failed publish/ack, duplicate delivery, concurrent conflicting results and restart at each persistence boundary.

If network lookup belongs in a new recovery coordinator, port the old raw-store probe to that real entry point. Preserve its expectation and failing history; do not require synchronous network access inside a preferences getter or make both devices share a map to satisfy the old test.

## F001 — One ordered turn transition and an actual deadline

**Locations:** `RootNavGraph.kt` countdown near 4113, own timeout near 4205, host/acting-host timeout near 4213, `executePick()` near 1479 and incoming timeout near 2095; packet epoch/revision/turn fields in `OnlineRoomSyncManager.kt`.

**Implement:**

1. Keep one accepted match state with participant/policy identity, owner/epoch, committed revision, exact current turn ID/player, running timing or suspended remainder, pause and result state.
2. Give every mutating action a stable action ID, exact match/turn and expected commit context. Process UI actions, received packets, heartbeat decisions and time expiry through the same production coordinator.
3. Only the authorized sequencer commits a timeout. Another phone can submit a timeout observation/request; it cannot independently rotate an accepted turn. One action replay returns the same outcome. Old turn timeouts cannot expire the next turn.
4. Enforce ownership against obsolete writers in the actual shared acceptance mechanism. An original host returning after valid transfer must recover the new epoch before acting. Heartbeat silence alone must not authorize takeover. If no safe owner exists, expose waiting and report the availability requirement BLOCKED.
5. Derive remaining time from the accepted deadline using a monotonic local projection. Do not decrement a counter once per coroutine delay. Scheduling delays must not stretch a 30,000ms turn.
6. Keep remote clock domains explicit; do not compare elapsed clocks from two devices or from an earlier boot. Use a documented authoritative timing/clock mapping and treat excessive uncertainty as recovery/waiting.
7. Local transport loss disables unsafe mutation and requires reconciliation before resuming. It must not create a fresh deadline or authorize the stale local timer. The healthy accepted owner may continue normal turns where ownership is established.
8. On expiry, advance once and give the next turn its normal 30,000ms. Do not replay missed expiry callbacks in a burst. With nobody active, restore/suspend from the accepted checkpoint without a backlog.

Keep the already-correct 1v1 automatic-forfeit guards, full participant rotation and removal of the extra two-second fallback. Opening/dismissing a popup, a duplicate packet, recomposition or returning to foreground cannot create a new deadline.

Test the real coordinator with two independent clients, asymmetric delivery, duplicate/out-of-order requests, stale owner, pick-at-expiry, foreground loss and simultaneous rejoin. A timer test that manipulates an unrelated integer is insufficient.

## F004 — Recover a complete accepted snapshot before unlocking

**Locations:** `RootNavGraph.kt:restoreAndRejoinOngoingMatch()` near 1354–1476, GAME_SYNC broadcaster near 1675, REJOIN_GAME response near 2275, snapshot application near 2295–2444, SYNC_REQUEST response near 2950 and manual sync near 4513.

**Implement:**

1. Make recovery state match/account/connection/attempt scoped. Saved data may render a preview but cannot authorize picks, timeouts, claims or result/stat changes.
2. Await connection plus confirmed subscription/readiness. Replace the fixed 300ms assumption with a retryable, correlated recovery request. Cancel retries/listeners when the attempt/account/match changes.
3. Use a single complete snapshot schema/builder/application path for recovery responses. Periodic lightweight diagnostics are explicitly partial and cannot mark recovery complete. Snapshot exchange must not echo a stale recovering client's preview as authority.
4. Include accepted board identities/layouts/manual data and marks, picks with actual picker/turn IDs, turn order/current turn, timing domain/deadline or exact remainder, pause true/false plus owner/reason, both incident records, accepted chat IDs/messages and final result if present.
5. Validate participants, exact match, recovery attempt and authorized owner/epoch/revision before applying. Apply all fields consistently, durably save the accepted state, then send its application ACK and unlock permitted gameplay. Reject an old/incomplete snapshot without partial state changes.
6. Apply false and empty values intentionally: a current unpaused snapshot clears an old pause; an accepted empty history is distinct from an omitted partial field. Do not revive stale typing indicators.
7. For a running turn, restore the accepted current timing; do not assign 30 seconds merely because a player rejoined. Example under a controlled clock: authoritative remaining 12,000ms means 12,000ms, not 30,000ms. Account for real transmission age in the chosen timing model.
8. Missing trustworthy boards remain waiting until a complete accepted board arrives. Do not generate either player's replacement board, call `startNewGame()` or switch to AI.
9. Route cold launch, menu/lobby/code rejoin, foreground and network recovery through the same coordination. Reset/cancel the gate on leaving an attempt, finishing a match and starting a new/rematch/AI/LAN game; old callbacks cannot unlock or block the new game.

Test saved A-turn versus newer B-turn, missing local board, chat while absent, paused-to-unpaused and unpaused-to-paused recovery, equal-turn timing correction, lost request/reply, slow subscription, old callbacks and a final result arriving during recovery. Kill/restart after snapshot application but before its ACK.

## F005 — Require account ownership at every live storage boundary

**Locations:** `OngoingMatchStore.kt` unscoped read fallback near 182 and broad clear near 253; `presentation/auth/AuthGateScreen.kt` startup cleanup near 50; `RootNavGraph.kt` chat checkpoint near 2219 and every remaining unscoped caller.

**Implement:**

1. Require a validated nonblank account UID for live reads, saves, updates and clears. Make unsafe calls impossible to overlook, preferably by removing optional UID defaults from production APIs and fixing every compile-time caller.
2. Remove first-scoped-key fallback and prefix-wide deletion from normal operations. Clearing A touches only A's selected match, not B's save. During unresolved auth, wait; do not guess an account.
3. Keep legacy migration in an explicit separate path. A nonblank owner mismatch is rejected even if the signed-in account is another participant. For ownerless data, participant membership alone does not prove local board perspective; preserve ambiguous legacy data separately.
4. Scope async callbacks to the authenticated session generation. Startup room cleanup, chat, pause, surrender, exit, result and availability writes must not mutate the previous account after a switch.
5. Serialize updates so availability/pause/chat/pick writes cannot overwrite a newer checkpoint. Handle storage failure off the UI thread and retain recoverable data.

Test A and B with distinct scoped matches: get/update/clear A leaves B byte-for-byte unchanged. Cover startup with A's room closed and B's active, corrupt/expired legacy data beside valid scoped data, logout/login and a delayed A callback after B signs in. Preserve all unrelated settings/profile/history.

## F003 — Validate eligible claim commits, not self-reported counts

**Locations:** `RootNavGraph.kt` incoming claim near 2482 and START_GAME near 3647; `OnlineRoomSyncManager.kt` packet defaults near 53 and receiver; `domain/engine/LobbyLifecycleEngine.kt:isPacketForActiveMatch()` near 366; `FastPacketCodec.kt`.

**Implement:**

1. Preserve strict claim match/seed checks and immutable participant validation before any identity mutation. Extend strict identity checks to **all new-policy mutating and recovery packets**, including GAME_SYNC_ACK, pause/resume and timeout.
2. Separate a claim request from an accepted final-result commit. A real peer sending count 3 and any nonblank episode string is not evidence of eligibility.
3. Validate against the committed record for the affected player: current open episode ID, actual count at least 3, still-unavailable state, healthy observer, active match/turn/owner/revision and unused action/result identity. Do not compare a peer's claim about this player against an unrelated locally observed counter for the other player.
4. Serialize fresh recovery, claim confirmation, surrender and Bingo in the same commit path. If fresh recovery is accepted first, reject the claim. If a valid final result is already accepted, all later actions reconcile to it without a second winner/stat update.
5. Reject stale episode claims within the same match and obsolete owner/recovery generations. Do not validate an already accepted historical result by re-running current heartbeat eligibility; validate its accepted commit evidence instead.
6. Negotiate the policy explicitly before enabling it. An absent version must not decode into proof of support. Mixed clients must get a clear incompatible-policy response or use an explicitly separate agreed legacy mode; no silent new-policy match.
7. Reject zero/missing match identity in a new-policy session. Keep necessary legacy behavior behind explicit session policy rather than weakening every match's filter. Compact/JSON codecs must preserve required identities and fail closed on malformed mandatory fields.

Invoke the real receiver/coordinator in tests. Cover fabricated and superseded episodes, low count, missing/wrong match/peer/epoch/revision, fresh-ACK race, duplicate result, mixed version and the already-fixed unknown-sender peer-overwrite regression. Rejection must leave board, result, counters and statistics unchanged.

## F007 — Preserve histories, milliseconds and committed checkpoints

**Locations:** `RootNavGraph.kt` history declaration near 160, initialization near 1128, periodic/rejoin snapshots near 1687/2275, history reconstruction near 2303 and pause/resume near 2447; `FastPacketCodec.kt` timeout encoder near 37; `OngoingMatchStore.kt:updateMatchGameState()`.

**Implement:**

1. Prefer one typed accepted pick record containing number, picker and real turn ID over three independently mutable lists. If parallel lists remain temporarily, validate lengths and reset them together.
2. Clear all match-scoped histories, gates, pending actions and incident state on a genuinely new match/rematch, including automatic/manual-board entry points. Preserve them on recovery of the same match.
3. Preserve real turn IDs through PICK_NUMBER, TURN_TIMEOUT and every full history/snapshot format. A timeout must not silently drop metadata that its schema advertises. Do not infer new-policy turns from `pickIndex + 1`.
4. Keep accepted remainder/deadline in milliseconds. A 14,250ms pause must remain 14,250ms after receive, save, kill/restart, resume and republish. `ceil(ms / 1000)` is for the countdown label only; never multiply that rounded label back into state.
5. Save locally and remotely accepted pause/resume, episodes, chat and picks through the same serialized durable state path. No application-level acceptance ACK or committed UI outcome after a failed required write. MQTT delivery ACK and state application ACK are different.
6. Persist accepted revision consistently. An unrelated partial storage write must not manufacture a new distributed gameplay revision. Empty accepted lists must be representable deliberately.

Test turns 1 and 3 picked with turn 2 skipped, timeout codec round-trip, match 2 after a completed match 1 without restarting the app, exact non-whole-second pauses, remote pause/resume then immediate kill, failed commits and simultaneous pause/availability updates.

## F008 — Report only tests and guarantees that actually exist

**Locations:** Fix002's `artifacts/heartbeat-fix002/Fix002ReviewResolution.md`, `TEST_REPORT.md`; tests `MatchCoordinatorAndPauseTest.kt`, `OngoingMatchStoreRejoinTest.kt`, heartbeat/codec suites and the new real production coordinator tests.

Preserve historical reports and mark them superseded. Create new Fix003 reports; do not erase old failing outputs.

- For every finding, name the changed production symbols, wired caller, exact existing test name, command, result and evidence path. Check that cited test names exist in executed XML, not just in prose.
- Distinguish pure unit tests, production-path integration, shared-adapter/process integration, paired-device execution and measured latency. A copied validator, local integer loop or storage-only pause test cannot certify the packet/timer/UI path.
- Test two local clients with independent stores and process state. Any intentionally shared test service must be explicit and match the production adapter's contract; no hidden global state standing in for another phone.
- Use `FIXED`, `PARTIAL/OPEN`, `BLOCKED`, `FAIL` and `NOT RUN` accurately. Describe missing infrastructure and unrun hardware separately from code failures.
- Keep the original HB01–HB14 matrix and all 44 original acceptance cases mapped to real evidence. Do not relabel a source inspection as an integration PASS.

## Mandatory Fix003 regression register

Record each row independently; do not use a single “all passed” sentence. Cases N01–N10 preserve the ten latest failing assertions, though correct refactors may move their entry point.

| ID | Action/fault to execute | Required evidence/outcome |
|---|---|---|
| N01 | Recovery with full/partial sync and ACKs every 2.5s for 20s | Recovery completes after the valid post-sync stable window; duplicates do not postpone it |
| N02 | New silence after N01, then another complete recovery/outage | Counts 1 → 2 → 3 across separate incidents; a continuous outage remains one |
| N03 | Unscoped production lookup with A/B saved | Rejected/unavailable until account known; no arbitrary selection |
| N04 | Normal cleanup with A/B saved | Only the authorized account/match is removed; both are not deleted |
| N05 | Required result commit returns false | No accepted/cache-visible final result, stats increment or active-state deletion |
| N06 | Repeat N05 with identical result and all writes failing | Retry remains failure; no false durable success |
| N07 | B absent; A accepts result and exits; B starts separately | B obtains accepted result from real shared adapter, with no shared process memory |
| N08 | Resume attempt after N07 | Final result shown; unfinished match cannot restart |
| N09 | Encode/decode timeout carrying picks at turns [1,3] | Number/picker/turn identities preserved exactly |
| N10 | Missing/zero seed or match identity in a new-policy session | Mutating/recovery packet rejected with no side effects |
| N11 | Silence at 14,999ms and 15,000ms; absence 45s and 5min | Exact fake-clock threshold, one incident, no auto-result |
| N12 | Late sync at 26s, ACKs and duplicate sync through 36s | No pre-sync credit; completion at 36s under the fixture |
| N13 | Stable gaps 5,000ms versus 5,001ms | Boundary follows the policy; only excessive gap restarts stability |
| N14 | Wrong/old connection or recovery ACK; delayed Last Will | No freshness, unlock, peer replacement or claim change from obsolete events |
| N15 | Disconnect 10s into a 30s turn; repeat at <15s remaining | Accepted deadline preserved; no extra 15/30s or two-second grace |
| N16 | Duplicate expiry and late old-turn expiry | One accepted turn advance; next turn gets its full duration |
| N17 | Pick races timeout under both delivery orderings | One ordered accepted history; no dropped accepted pick or double advance |
| N18 | Host and guest each lose connectivity; asymmetric partition | Safe owner/commit order proven or explicit safety wait and BLOCKED availability |
| N19 | Both apps close >8min and return together | Same accepted checkpoint, no TTL deletion, competing owners or skip backlog |
| N20 | Rejoin with authoritative 12,000ms running remainder | Correct clock projection; never a new 30s solely from rejoin |
| N21 | Pause at 14,250ms; resume and next normal turn | Exact state remainder preserved; next genuine new turn is 30,000ms |
| N22 | Remote pause/resume accepted, then immediate process kill | Persisted state restores correctly without waiting for another pick/heartbeat |
| N23 | Saved pause true, current snapshot false; reverse it | Both Boolean directions applied; recovery never silently resumes a deliberate current pause |
| N24 | Missing local/manual board and messages sent during absence | Complete accepted board and chat restored before unlock; otherwise waiting |
| N25 | Subscription delayed; request/reply/ACK dropped or duplicated | Correlated retry succeeds safely; partial/old snapshot cannot unlock |
| N26 | Exit while recovery pending; start AI/LAN/new online/rematch | Gate/jobs/old callbacks cannot affect the later game |
| N27 | Match 1 history then match 2; skipped turns before picks | No old IDs; actual turn/picker attribution survives restart and wire round-trip |
| N28 | A/B account switching, startup cleanup and late A callback | B's data unchanged; no owner/perspective migration across accounts |
| N29 | Claim incidents 1/2, invented third or stale episode | Rejected without terminal or statistic effects |
| N30 | First fresh response races eligible claim confirmation | Accepted ordering determines one outcome; stale UI cannot override it |
| N31 | Claim/Bingo/surrender race, conflict and restart/replay | One canonical final result and one career/history application per participant |
| N32 | New/old policy capability combinations | Explicit compatibility decision; defaults do not prove negotiation |
| N33 | Pause/availability/chat/pick writes and storage fault interleave | No lost accepted revision, false ACK or poisoned-cache success |
| N34 | Recomposition/rapid reconnect, repeated 20 times per role | One effective observer/controller; no stale jobs mutating a later attempt |
| N35 | Same-setup baseline/candidate online pick latency | Actual sample count, median/p95 and timing boundaries; explain any regression |

Additionally retain the original healthy/short interruption, home/app switching, screen lock, swipe-away, process kill, Android force-stop, slow cloud request, wall-clock change/reboot, normal full matches and baseline regression cases. Force-stop recovery occurs on relaunch; do not expect Android to auto-reopen a force-stopped app.

Use fake clocks and fault injection for exact timing/ordering, then actual paired test devices where available. Use Kevin/Stuart dedicated accounts and automated actions so screenshot analysis does not consume the turns. Capture action timestamps, both screens and accepted event traces; do not invite `@bob`, wipe data or reset real statistics. If `adb devices` is empty, inspect available AVDs and existing harnesses before declaring the environment unavailable; report what was actually attempted. Device execution that cannot run remains NOT RUN, never inferred PASS.

## Journal, builds, rollback and final handoff

Before editing create:

- `artifacts/heartbeat-fix003/BASELINE_MANIFEST.json`
- `artifacts/heartbeat-fix003/CHANGELOG.md`
- `artifacts/heartbeat-fix003/OWNERSHIP_AND_STORAGE.md`
- `artifacts/heartbeat-fix003/TEST_REPORT.md`
- `artifacts/heartbeat-fix003/Fix003ReviewResolution.md`

Append an entry before/after each logical chunk:

```markdown
### FIX003-001 — <specific correction>
- Started/completed: <IST timestamps>
- Parent checkpoint and tested commit: <SHAs>
- Findings/cases: <F IDs, HB IDs, N IDs>
- Files, symbols and actual callers: <locations>
- Before → after: <concrete behavior>
- Ordering/ownership/persistence mechanism: <implemented guarantee or explicit gap>
- Compatibility/migration: <data handling and limitations>
- Verification: <actual commands, outcomes and evidence; retain failures>
- Remaining work/blockers: <specific item or none>
- Rollback: <targeted revert/checkpoint, schema/data considerations>
```

Keep bounded structured runtime traces with process/device identity, account/session generation, match/recovery/connection IDs, action/episode/result IDs, accepted owner/epoch/revision, turn/timing, observation health, persistence outcome and rejection reason. No credentials, tokens or unrelated chat/profile dumps in logs.

Suggested internal order: reproduce/preserve failures and inventory capabilities; correct account/persistence boundaries; correct recovery generations; wire ordered production state/wire/rejoin paths; run integration/device checks and write the resolution. These are internal work chunks, not separate requested releases. Commit verified chunks locally; do not push or merge protected branches.

Run meaningful targeted checks while editing, then a fresh full suite and lint/release build in the isolated checkout:

```bash
./gradlew testDebugUnitTest --rerun --offline --console=plain --max-workers=2
./gradlew lintDebug assembleRelease --offline --console=plain --max-workers=2
```

If dependencies are uncached, resolve them normally and record the command. Do not claim offline failure is an application regression. Verify baseline tests, zero lint errors and explain new warnings. Check candidate signing against the baseline without exposing keys.

Copy the candidate only to `artifacts/heartbeat-fix003/Bingo-fix003.apk`; record version, size, SHA-256 and signing certificate comparison. Do not overwrite `/home/bob/workspace/Bingo.apk` or the baseline APK even if an older release script says to do so.

For rollback, retain original refs, migration backups, failed diffs and logs. Use targeted tested reverts on the repair branch. Never wipe app data or promise an older APK understands new active-match schemas without a tested downgrade/migration statement. Recheck protected baseline source fingerprints and APK hashes at delivery.

Deliver the source/commit list, candidate APK, append-only journal, capability decision, F001–F008 resolution matrix, HB01–HB14 matrix, original 44-case mapping, N01–N35 actual results, test XML/logs, device/latency evidence or explicit NOT RUN entries, and an updated root `AGENT_HANDOFF.md` naming the exact checkout/checkpoint and next steps.

**Completion rule:** use READY only when the required code paths, ownership/result capabilities and mandatory behavioral checks have actual passing evidence. A passing local suite, a larger test count, a same-process simulation or a safe fallback that cannot continue guest play is not full completion. If a guarantee depends on unavailable infrastructure or unrun device validation, report NOT READY with those exact items while delivering the independent repairs and concrete reviewable dependency specification.
