# HeartbeatImplementationPlanFix002

Prepared **2026-10-15 (Asia/Kolkata)**. Send this complete prompt to the implementing agent.

## Your task

Repair the eight remaining findings **F001–F008** in `HeartbeatImplementationFix001Review.md`. Deliver the completed correction in **one delivery**, with internal tested commits, a change journal, actual evidence and a rollback path. Preserve the genuine Fix001 improvements. Do not restart the feature from scratch or introduce another staged roadmap.

Fix001 is **NOT READY because code defects remain**, in addition to missing device testing. Its 355 passing JVM tests and five repaired original probe assertions are useful progress. The additional production checks reproduced **two recovery failures and four storage failures**. Do not treat this as a documentation-only or device-testing-only task.

### Read first

1. `/home/bob/workspace/HeartbeatImplementationFix001Review.md` — authoritative remaining findings, exact source locations and evidence.
2. `/home/bob/workspace/HeartbeatImplementationPlanFix001.md` and `HeartbeatImplementationPlan.md` — agreed behavior and earlier acceptance cases.
3. `/home/bob/workspace/heartbeat-fix001-review-evidence/` — production probes, initial output, runner, test XMLs and source manifest.
4. Fix001 source, journal and resolution report. The report's all-code-fixed/integration-PASS statements are superseded by the re-review; verify the actual implementation.

Line numbers below refer to **`fa3b1ffdd3f517b90e595c1589c6a8290a8c69e0`**. Re-find by symbol after edits. `RoomMessagePacket` is inside `domain/network/OnlineRoomSyncManager.kt`.

## Protected references and repair checkout

| Reference | Required handling |
| --- | --- |
| Baseline `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9`, branch `1.4.1` | Preserve source, branch, original checkout and APK |
| First candidate `fc8bdf0ef460e29dc2e8e410fe41ae81ffd606bd` | Preserve as a historical checkpoint |
| Fix001 `fa3b1ffdd3f517b90e595c1589c6a8290a8c69e0`, branch `fix/heartbeat-fix001` | Starting correction checkpoint; preserve for comparison |
| Tag `v1.4.1`, commit `c01252767d96185e503f9b9dcefa11f1cc1fc02d` | Older original release, not the restored baseline; do not move it |

Inspect current worktrees, HEAD and dirty files. If Fix001 has advanced, inspect and retain that work rather than resetting it. Otherwise use a new repair checkout:

```bash
git -C /home/bob/workspace/bingo_multiplayer worktree add -b fix/heartbeat-fix002 /home/bob/workspace/bingo_heartbeat_fix002 fa3b1ffdd3f517b90e595c1589c6a8290a8c69e0
```

Inspect any existing path/branch and choose an unused name if necessary. Do not use destructive resets, forced branch recreation, `git clean`, force-push or blanket restores. Keep application changes/builds in the new worktree. Do not push protected branches, publish releases, deploy services/rules or overwrite shared APK copies. The older repository rule to push release builds to `main` does not override this instruction.

Protected baseline APK: `/home/bob/workspace/bingo_multiplayer/Bingo.apk`, SHA-256 **`6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc`**. Record the current hash of `/home/bob/workspace/Bingo.apk` and preserve it too; it already contains a candidate, not the baseline.

Scope the new rules to an immutable, explicitly negotiated **online 1v1 match**. Preserve AI, LAN, online groups, lobby behavior, QR/social features, statistics, scorecards, chat/typing, themes, sounds, signing and credentials. Do not infer 1v1 from a changing live-player list or infer new-policy support from default packet fields.

## Behavior contract

- Foreground match probes every **2,500 ms**, starting immediately after valid connection/subscription readiness; continue while deliberately paused.
- A healthy observer detects **15,000 ms** without a verified fresh peer response. Generic traffic or stale retained messages do not establish liveness.
- One continuous outage is one incident. Counts are per affected participant and match, survive restart and reset only for a genuinely new match.
- Another episode can count only after current state synchronization completes and then **10,000 ms** of stable fresh heartbeats, with no response gap greater than **5,000 ms**.
- Incidents 1/2: **Pause game / Continue countdown**. Incident 3+: additionally **Claim victory** while the opponent remains unavailable and the observer remains healthy.
- Fresh peer response immediately disables claiming. A claim remains manual and must be accepted by the match's decision mechanism.
- Every normal turn is **30,000 ms**; its expiry advances once. Silence detection does not change the turn deadline. Pause preserves exact remaining milliseconds; resume and rejoin do not create a fresh turn.
- Backgrounding, app closure and missed turns do not automatically award a result. Explicit surrender and legitimate Bingo retain their rules.
- Rejoin blocks gameplay until the current accepted match/final result is reconciled. Preserve boards, picks with actual turn IDs, timing, pause, incident records and accepted chat.
- Neither player active: preserve accepted state and suspend progression. Do not replay unattended timeout backlogs, regenerate a board or change online mode to AI.

Keep the original fixes working: automatic/manual monitoring activation, the 1v1 automatic-forfeit bypass, the original five passing policy checks, shared rejoin entry-point wiring, removal of the stale UI claim override and removal of generic packet freshness.

## Mandatory implementation approach

The existing defects are in connected runtime paths. Use a small **production** match coordinator/state holder with serialized decisions and injectable clock, transport and durable storage. Navigation/UI submits actions and renders accepted state. Extract only what this feature needs; avoid unrelated rewrites.

A helper, unused metadata field, deterministic player order, copied condition in a test or a comment claiming coordination is not an implementation. Every new API must have its actual app caller wired and tested. Define explicit acceptance/persistence/acknowledgment ordering before adding more recovery callbacks.

F001 and F002 require enforceable ownership and shared durable state. Audit the actual existing broker/cloud capabilities. Ordinary MQTT retention, a local epoch increment or non-atomic cloud GET/update does not establish those guarantees. Keep gameplay on MQTT; do not silently reintroduce per-move HTTP or a new authority service.

If existing infrastructure cannot safely provide a required guarantee, implement the visible safety wait, complete independent corrections and report that dependency **BLOCKED** with a concrete capability/configuration requirement. Do not mark the feature READY or substitute competing local timers. Any proposed deployment must first be made concrete and reviewable; this prompt does not authorize deploying it.

## F001 — Implement real ownership and deadline-based turns

**Locations:** `presentation/navigation/RootNavGraph.kt`: countdown near **4032**, peer fallback near **4131**, sync responders near **2871**, `executePick()` and incoming pick/timeout handling. Packet identity/epoch/revision fields are in `OnlineRoomSyncManager.kt`.

**Required changes:**

- Introduce one accepted match state containing unique match identity, immutable participants, negotiated policy, controller identity/epoch, revision, turn ID/current player, deadline or paused remainder and final result.
- Serialize action acceptance. Bind each pick/timeout/pause/resume/claim to exact match, controller generation, expected revision, turn ID and stable action ID. Duplicate delivery returns the same accepted outcome without another transition.
- Remove overlapping new-policy authorizations such as `activeRemaining.size <= 2` and independent peer fallback timing. The unavailable turn must not acquire the old extra two seconds.
- Render countdowns from accepted timing state. A Compose effect, foreground event, dialog or duplicate sync cannot assign a new deadline. Never compare unrelated device/boot monotonic clocks directly.
- Suspend unsafe mutation when local health or ownership is uncertain. A returning original host must synchronize before acting and cannot silently reclaim control. Prevent old owners/old packets from committing over a new owner.
- Establish a known accepted checkpoint for takeover and recovery; no timer advances while nobody can safely own the match.

**Proof required:** Two instances of the real production coordinator under asymmetric delivery, both reconnecting, obsolete owners, duplicate timeout, pick-at-expiry, local outage and both-offline recovery. Assert one accepted history/result and exact 30-second fake-clock deadlines. Name and demonstrate the actual ownership-enforcement mechanism; if absent, mark takeover BLOCKED.

## F002 — Make final results shared, immutable and crash-safe

**Locations:** `domain/network/OngoingMatchStore.kt` result helpers near **257–294**; `RootNavGraph.kt` claim completion near **811–852**, incoming result handling and recovery.

**Required changes:**

- Keep a durable immutable accepted result keyed by unique match identity, with stable result ID, participants, winner/reason, eligible claim evidence where applicable, controller epoch and committed revision.
- Make that result recoverable by the absent participant **after the winner's app also closes**. A local SharedPreferences tombstone or a one-shot packet sent to an offline peer is insufficient.
- Use a verified existing durable repository/transport capability. Do not assume a local cache write is cloud-acknowledged or that the cloud room metadata contains a full result.
- An identical result replay is idempotent. A conflicting second result must be rejected and must leave the original final record unchanged. Do not implement immutability with an unguarded read followed by unconditional replacement.
- Persist the accepted final record before acknowledging completion or removing active recovery state. Handle write failure visibly and safely.
- Apply career/history once using durable result identity. Make crash recovery between result persistence, statistic updates and acknowledgment safe; a Boolean written only after incrementing counters does not by itself prevent double application after a crash.
- On recovery, resolve a stored final result before enabling any unfinished-match UI. A concluded match cannot be resurrected.

**Proof required:** Separate local stores for two real coordinator instances; peer offline during claim; winner persists and exits; peer restarts and obtains the same result from the shared repository. Repeat restarts/delivery, inject failed writes and a conflicting result. Verify exactly one result and one career/history application per participant.

**Test interpretation:** Raw local preferences are not required to fetch the network synchronously. It is acceptable for the corrected recovery coordinator to fetch shared truth through a separate repository. In that design, replace the old cross-device raw-store probe with an equivalent test through the real recovery path. Preserve the original failure output and the user-visible expectation; do not make a local-only implementation appear shared by changing assertions or giving both devices the same preference map.

## F003 — Validate claims before changing any sender/peer state

**Locations:** `RootNavGraph.kt` packet identity assignment near **1710**, `CLAIM_VICTORY` near **2415**; `RoomMessagePacket` / `FastPacketCodec` and start negotiation.

**Required changes:**

- Remove the trust cycle where an incoming packet first sets `opponentPlayerId` and is then accepted because its sender equals that newly assigned value. Validate against immutable participants and current connection/controller context before any identity mutation.
- New-policy gameplay/result packets require explicit, nonblank active match identity. Reject zero/missing legacy identity rather than conditionally checking only populated fields. Require the exact active match/seed mapping; do not permit old-room/rematch traffic to finish the new game.
- Separate claim requests from accepted final-result commits. A request carrying `claimEpisodeCount=3` is not proof of eligibility.
- Validate actor, affected peer, committed episode ID/count, current unavailability/local health, expected revision, authorized owner/epoch and stable action/result ID. Order recovery, surrender, Bingo and claim races through F001/F002.
- Negotiate policy capability explicitly before start. Default `protocolVersion=1` in decoded old packets is not negotiation. Keep legacy AI/LAN/group behavior separate.
- Ensure the selected compact/JSON encoding preserves every validation field. Decoder defaults cannot silently convert missing mandatory new-policy metadata into valid authority.

**Proof required:** Invoke the actual packet receiver with missing/zero identity, old match, unknown sender, stale connection/epoch/revision, low/fabricated episode count and duplicate result. Assert no game-over/statistics/storage side effects. Verify the valid accepted result once. Include the exact regression where an unknown sender would previously have overwritten `opponentPlayerId`.

## F004 — Finish recovery before allowing gameplay

**Locations:** `RootNavGraph.kt` `restoreAndRejoinOngoingMatch()` near **1317**, immediate local restore near **1354**, navigation/delayed requests near **1445**, `SYNC_REQUEST` response near **2878**, foreground handling near **420**.

**Required changes:**

- Add an explicit recovering/waiting state. Local saved state can render a preview, but cannot authorize picks, timeouts, claims or normal turn progression before reconciliation.
- Replace fixed-delay assumptions with actual connection/subscription readiness and a recovery request bound to match identity, connection and recovery-attempt generation.
- Request accepted snapshot or final result; validate identity, participants, controller/epoch, revision and history. Atomically apply, durably persist and acknowledge it before enabling permitted actions.
- Include board identity/layout/manual data, marks/picks with actual turn IDs, turn order/current player, timing domain/remainder, pause owner/reason, both participants' incident records and accepted chat/result IDs. Avoid independent merges that invent a state no controller accepted.
- Reconcile accepted chat by stable IDs. Do not fabricate missing historical data, revive old typing indicators or clear a deliberate pause.
- Apply this flow to cold launch, menu/lobby/code rejoin and foreground/network recovery. Late callbacks from an older attempt cannot mark the current recovery complete.
- If no trustworthy board/checkpoint exists, show a waiting/error state. Remove ongoing-match fallback to `startNewGame()` or a generated replacement board. Never temporarily fall back to AI.

**Proof required:** A stale saved snapshot says it is A's turn while B has advanced the match. A rejoins and attempts an immediate pick before sync: reject/disable it. Then apply the current snapshot and verify board, chat, incidents, pause and countdown. Repeat every entry point, both roles, missing board, late previous sync, remote final result and both reopening.

## F005 — Enforce account ownership without global alias adoption

**Locations:** `OngoingMatchStore.kt` scoped/global write near **103**, migration near **183**, optional read/update/clear APIs; all store callers in navigation/menu/auth.

**Required changes:**

- Require an explicit validated account UID for production save/read/update/clear. Do not preserve optional unscoped behavior in live callers.
- Stop writing a global `active_match_data` mirror as the current recovery record. Use owner-scoped storage and a separate, deliberate legacy migration path.
- If a legacy record has a nonblank owner different from the signed-in account, reject migration even when the signed-in account appears as its opponent. Never relabel A's local board/perspective as B's.
- For ownerless legacy data, migrate only when local record ownership/perspective can be established from trustworthy existing metadata. Membership alone is insufficient; otherwise retain it separately without exposing it to another account or deleting unrelated data.
- Reading/updating/clearing A must not touch B. Resolve result identity with the correct match/participants/account context too. Account switching cancels old asynchronous session callbacks.
- Preserve existing profile/settings/history and back up legacy data before migrations. Document any baseline downgrade limitation for new unfinished-match schemas instead of silently wiping data.

**Proof required:** A-owned record with participants A/B, then sign in as B: B cannot see/adopt that checkpoint and no B key is created from it. B creates its own match, A returns and both records remain correct. Test actual read/update/clear callers, including cold auth/menu recovery and legacy-owner mismatch.

## F006 — Bind stability to the current completed synchronization

**Locations:** `domain/engine/OnlineMatchHeartbeatPolicy.kt` recovery start near **300**, sync handling near **359**; `OnlineRoomSyncManager.notifyStateSynchronized()` near **250**; coordinator acknowledgments.

**Required changes:**

- Associate state synchronization with the current recovery generation and accepted snapshot/revision. A previous healthy-session sync cannot satisfy a new recovery attempt.
- Fresh ACK immediately revokes claiming and starts recovery observation. It does not itself prove board/turn synchronization.
- Start the required 10-second stable window **after current state synchronization completes**. ACKs received before that point cannot be credited as post-sync stability.
- All completion paths must require the current valid sync, 10 seconds of continuity and no gap above 5 seconds. A broken gap resets stability. Local unavailability cancels/restarts current recovery observation appropriately while preserving incident identity/count.
- Route decisions from tick, ACK and sync completion through the same accepted transition/persistence/event path. Do not discard a `RecoveryCompleted` returned by `onStateSynchronized()`.
- Ensure the device providing the recovery snapshot receives/records the matching application acknowledgment, so its own policy is not left waiting forever for a sync it only sent.

**Exact regression fixture:** A known healthy match loses peer responses; episode opens at 15,000 ms. First valid returning ACK at 16,000 ms. Further ACKs arrive at 18,500 / 21,000 / 23,500 / 26,000 ms. Current snapshot synchronization finishes at 26,000 ms. Episode must remain open at that point and through 35,999 ms. With fresh ACKs at 28,500 / 31,000 / 33,500 / 36,000 ms, it may close at 36,000 ms. Without current sync, it must not close merely because an older sync flag was true.

Retain the five originally repaired checks: initial absence at 15 seconds, no false recovery after ten silent seconds, one episode across unstable recurrence and correct re-arm after local recovery.

## F007 — Durably save received pause/resume and exact gameplay metadata

**Locations:** `RootNavGraph.kt` pause/resume receiver near **2393**, availability-only checkpoint near **4193**, `executePick()` saves and history reconstruction near **1925 / 2283**; storage update APIs.

**Required changes:**

- Both locally requested and remotely received accepted actions must pass through the same durable state writer. Receiving Pause/Resume cannot update only remembered UI fields.
- Preserve exact remaining milliseconds, turn/revision, pause owner/reason and result/incident state. Keep units explicit; convert to display seconds only when rendering.
- Record the actual accepted turn ID with each pick, including gaps from timeouts. Remove `pickIndex + 1` reconstruction for new-policy history.
- Persist accepted chat and accepted transition metadata consistently. The recovery snapshot and local state must describe the same committed revision.
- Handle storage failure explicitly and keep durable I/O off the main thread. Do not send an application acceptance ACK for data that failed to save. Distinguish MQTT delivery acknowledgment from game-state acceptance.
- Protect read-modify-write operations from lost updates between availability, pause and pick writes. A single serialized coordinator with a transactional/durable writer is preferable to racing partial copies of one preference record.

**Proof required:** A pauses at 12,000 ms, B applies/persists the remote commit, then B is killed before any pick/heartbeat checkpoint. B restores paused at exactly 12,000 ms. Repeat remote resume and a subsequent full turn. Skip turns, then pick: after restart/rejoin preserve original pick turn IDs/marks. Inject failed persistence and concurrent availability/pause events; no acknowledged state may disappear or overwrite a newer revision.

## F008 — Test real production paths and correct the readiness evidence

**Locations:** `MatchCoordinatorAndPauseTest.kt`, `MatchHeartbeatPacketCodecTest.kt`, `OngoingMatchStoreRejoinTest.kt`, policy tests and Fix001's report/handoff.

**Required changes:**

- Correct the current all-code-fixed claim immediately. Preserve previous reports/outputs as history and mark them superseded; do not erase failures.
- Test the production coordinator/receiver/lifecycle/storage adapters. Tests that assign a local integer, compare UUIDs, check `packet.count >= 3` or maintain a separate challenge map do not establish that the application path validates those things.
- Introduce only the injection/hooks needed to exercise actual code. Do not build a second test-only match implementation that always agrees with the specification.
- Maintain separate evidence categories: pure unit calculation, production-path integration, paired-device execution and measured latency. Do not claim Robolectric/UI testing without an actual configured and executed test.
- Document all eight remaining findings as `FIXED`, `FAIL`, `BLOCKED` or `NOT RUN`, with changed symbols, regression IDs, actual commands and evidence. Keep the original HB01–HB14 matrix accurate and retain regressions for the fixes already working.
- Network/takeover/force-stop/shared-result cases require matching evidence. If devices are unavailable, record that limit; it cannot turn an unimplemented ownership/shared-result mechanism into a device-only blocker.

## Verification and probe handling

Before editing, preserve the initial output and reproduce the current failures against Fix001:

```bash
python3 /home/bob/workspace/heartbeat-fix001-review-evidence/run_review_probes.py /home/bob/workspace/bingo_heartbeat_fix001
```

The expected starting point is original five checks passing, two recovery assertions failing and four storage assertions failing. Add regressions for the source-derived receiver/rejoin/timer problems too.

After building Fix002's debug/unit-test classes, run equivalent probes against the repair worktree:

```bash
python3 /home/bob/workspace/heartbeat-fix001-review-evidence/run_review_probes.py /home/bob/workspace/bingo_heartbeat_fix002
```

If a correct refactor changes an API or moves shared result lookup out of the local store, port the corresponding probe to the new **real production entry point**. Preserve its behavioral expectation, original output and a mapping to the new regression. Do not delete or weaken a failing check just to report zero failures. Copy review evidence before running scripts that replace their `.out` files.

Use a fake monotonic clock and injected delivery/write faults for exact tests. In addition to every F001–F008 regression, repeat the original plan's 44 acceptance cases with honest layer labels. Include host/guest, automatic/manual boards, all rejoin routes, short/long/repeated outages, broker/local/asymmetric interruptions, stale packets, pause/claim races, process death, both apps closing, account switches and mixed versions.

For device execution use Kevin/Stuart test accounts and automated actions so screenshot analysis cannot consume the 30-second turns. Repeat recovery cycles at least 20 times per role and include complete matches. Do not invite `@bob`, reset statistics or clear user data. Capture action timestamps, both screenshots, accepted event/revision traces and actual outcomes.

Run meaningful targeted checks per internal chunk. Finish with fresh full-suite execution and lint/release build in the repair worktree:

```bash
./gradlew testDebugUnitTest --rerun --offline --console=plain --max-workers=2
./gradlew lintDebug assembleRelease --offline --console=plain --max-workers=2
```

Use online dependency resolution if caches are missing. Keep baseline checks passing, zero lint errors and explain new warnings. Verify APK signing matches the protected baseline. Measure baseline/Fix002 tap-to-accepted-pick latency under the same setup, reporting samples, median and p95. Non-blocking coroutines are not latency measurements.

## Change journal, rollback and deliverables

Before code edits create `artifacts/heartbeat-fix002/CHANGELOG.md`, `BASELINE_MANIFEST.json`, `TEST_REPORT.md` and `Fix002ReviewResolution.md`. Copy this prompt and the latest review into the repair worktree's `docs/`. Record exact starting HEAD, worktree/branch, protected references, hashes and proposed file inventory without printing secrets.

Append before/after each logical edit chunk:

```markdown
### FIX002-001 — <specific correction>
- Started / completed: <IST timestamps>
- Parent checkpoint: <SHA>
- Review findings: <F001–F008 and original HB IDs>
- Files / symbols and wired callers: <exact locations>
- Old behavior / new accepted behavior: <concrete change>
- Ownership / ordering / durability: <actual mechanism and limits>
- Compatibility / migrations: <scope and data handling>
- Tests and evidence: <commands, actual results, failed attempts retained>
- Remaining concerns: <specific issue or none>
- Rollback: <targeted revert/checkpoint and data considerations>
```

Commit tested chunks locally and preserve an ordered commit list. Stage only intended files. Before reverting a failed chunk, save its diff, status, untracked implementation files, logs and migration backups. Do not hide failures by resetting another worktree or rewriting history.

Bounded runtime JSONL logs should identify match, connection/recovery generation, actor/controller, episode/action/result IDs, revision, turn ID, remaining milliseconds, transport health, persistence outcome and acceptance/rejection reason. Never log credentials. A screenshot alone does not establish exactly-once timeout/result handling.

For a separate baseline checkout, choose an unused directory:

```bash
git -C /home/bob/workspace/bingo_multiplayer worktree add --detach /home/bob/workspace/bingo_fix002_baseline_check a13d39edb0ff6a67d272cac187f1ed9fb294f9e9
```

Use targeted `git revert` on the repair branch when source rollback is needed. Preserve source/data evidence and the untouched original baseline APK. Do not uninstall, wipe application data, change signing keys or promise the old APK understands a new active-match schema without testing/documenting downgrade behavior.

Deliver repaired source and commit list; `artifacts/heartbeat-fix002/Bingo-fix002.apk` with version/size/SHA-256/signature comparison; updated root handoff; change journal; full F001–F008 resolution matrix; original HB status matrix; test results and actual device/latency evidence or explicit unrun/blocked entries. Do not overwrite the original or shared workspace APKs.

Use **READY** only when the implemented guarantees and mandatory acceptance cases have real passing evidence. If exclusive ownership, shared durable final recovery or device validation remains unmet, state **NOT READY** with the exact dependency. A larger test count or passing the previous limited probes does not close the review by itself.
