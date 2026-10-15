# Heartbeat repair and self-review

Prepared: **2026-10-15, Asia/Kolkata**. Worktree: `/home/bob/workspace/bingo_heartbeat_codex`, local branch `fix/heartbeat-codex`.

**Verdict: improved candidate, NOT ready to certify the full requested behavior.** The reproduced storage, timer, claim-validation and synchronization defects have code corrections and regression evidence. One functional requirement remains blocked: the guest cannot continue accepting gameplay or confirm a claim/surrender result while the original host is unavailable. Android paired-device verification and a baseline latency comparison are also outstanding.

## What changed

Online 1v1 now uses the actual `ReliableMatchClient` and `ReliableMatchCoordinator`. Both devices send actions through one serialized acceptance path, and render complete accepted state. The original host installation sequences picks, timeouts, pause/resume, chat and results. Legacy 1v1 packets cannot independently change that state. AI, LAN and group matches retain their existing paths.

Turns use a monotonic deadline. Pause/resume preserves the exact millisecond remainder; only a new turn gets 30,000ms. A repeated expiry cannot advance the following turn. Disconnecting, backgrounding or missing turns does not automatically award victory. A returning device reconciles full boards, accepted picks with actual turn IDs, chat, pause state and timing before gameplay unlocks.

The fake shared filesystem adapter is removed. Accepted state, including the immutable final result, is published through the real MQTT broker on `bingo/v4/match/{room}/{seed}/state`, retained at QoS 1. Requests and synchronization use MQTT too. Initial/final publications additionally require an exact retained read-back in the Android adapter. No Cloudflare deployment or HTTP request per move was added.

Pending local writes and accepted shared state are distinct. Failure blocks mutations and triggers reconciliation. Result application has an account-bound durable receipt, so repository restart and repeated delivery cannot increment history/career twice. Account/match checks protect newer sessions from late writes; ambiguous ownerless legacy records are preserved without adopting them.

## Findings reviewed again

Paths below are under `app/src/main/java/com/bingo/multiplayer/`. Exact executed regression names and evidence are in [TEST_REPORT.md](TEST_REPORT.md) and [RegressionTestIndex.md](evidence/RegressionTestIndex.md).

| Finding | Correction and evidence | Current status |
|---|---|---|
| F001: multiple turn writers, unsafe takeover and counter timer | `domain/engine/ReliableMatchCoordinator.kt`, `domain/network/ReliableMatchClient.kt`, `presentation/navigation/RootNavGraph.kt`: one serialized writer, action/revision/turn checks, monotonic deadline, one expiry, no guest promotion. Pick/timeout races, repeated expiry, 20 unavailable turns and restarted-owner tests exercise production classes. | Safety/timing corrected within the fixed-host model. **Guest availability remains BLOCKED.** |
| F002: fake cross-phone result and ignored failure | `domain/network/OngoingMatchStore.kt`, `OnlineRoomSyncManager.kt`, `ReliableMatchClient.kt`: removed shared-file production adapter, added actual retained MQTT recovery and pending/accepted persistence. Separate JVMs with separate device directories recovered a final result after the writer exited. Publication/acceptance fault tests stay retryable. | Corrected and component/live-broker verified for the documented single-host model. Broker disaster recovery and hostile publisher fencing are not claimed. |
| F003: invented claim episode and assumed compatibility | `ReliableMatchCoordinator.kt`, `ReliablePolicyAgreement.kt`, `ReliableMatchClient.kt`, `FastPacketCodec.kt`: explicit lobby capabilities plus fresh runtime negotiation, default version 0, current committed third/open/unreachable episode, fresh response revocation and serialized terminal actions. Legacy/forged claim packets cannot commit results. | Validation corrected. **Guest claim during host absence remains BLOCKED** by F001 availability. |
| F004: incomplete/stale snapshot unlock and pause overwrite | `ReliableMatchClient.kt`, `RootNavGraph.kt`, `presentation/game/GameScreen.kt`: correlated attempts/ACKs, complete validated snapshot, durable apply before unlock, exact Boolean/empty-history application, missing-board wait. Tested stale pause, duplicate-sequence conflicts, chat recovery, delayed ACK and incomplete snapshot rejection. | Corrected in the production protocol; full Android entry/lifecycle UI remains NOT RUN. |
| F005: ownerless record assigned to another participant | `OngoingMatchStore.kt`: scoped lookup/cleanup, no ownerless adoption, no global fallback, guarded writes after rematch/dismiss. Tests check guest versus host ownership and unchanged other-account data. | Corrected and storage-adapter verified. |
| F006: repeated sync restarts recovery stability | `domain/engine/OnlineMatchHeartbeatPolicy.kt`, `ReliableMatchClient.kt`: preserved the earlier reducer correction, synchronized public transitions, bound application ACKs to issued snapshots/current attempts. Added exact late-sync and 5,000/5,001ms gap regressions. | Earlier fix preserved and strengthened; Android scheduler/collector verification remains NOT RUN. |
| F007: resume resets to 30 seconds/rounds state | `ReliableMatchCoordinator.kt`, `ReliableMatchClient.kt`, `RootNavGraph.kt`: exact remainder drives the real 1v1 UI projection; legacy countdown effects do not run this match. Coordinator and serialized-wire tests pause at 14,250ms and verify only the next turn gets 30,000ms. | Corrected and component verified; precise on-device labels remain NOT RUN. |
| F008: unsupported readiness and invented coverage | This report, `TEST_REPORT.md`, new handoff and superseded notices. Exact XML names are indexed; component, source review, real broker and unrun Android evidence are separated. | Reporting corrected. The candidate is explicitly **NOT fully ready**. |

## What still needs work

1. **Original-host availability.** With the existing ordinary MQTT broker, this candidate preserves one writer by waiting when the host is absent. Guest moves/timeouts/claims and confirmed surrender cannot progress then. To satisfy that requirement, supply a continuously running MQTT-connected authority or a supported broker-enforced ownership/fencing capability. No per-move HTTP is required by that solution. The authority needs a concrete hosting location, credentials and durable storage; no service was silently created or deployed. See [OWNERSHIP_AND_STORAGE.md](OWNERSHIP_AND_STORAGE.md).
2. **Android verification.** Two existing AVDs were attempted without available KVM. Neither reached usable boot-complete screens. No candidate was installed and no paired Android gameplay/screenshots are claimed. Run the original device plan after usable devices are available: both roles, home/background, swipe/kill, network toggles, rapid reconnect, UI actions/colors, manual boards, chat, notifications and timer labels.
3. **Latency measurement.** The headless live MQTT probe measured eight alternating picks at approximately 503ms median / 554ms p95 from request to both clients accepting state, with 50ms sampling resolution. This is neither physical tap latency nor a comparison with baseline. The guest's authoritative round trip is real; no promise of unchanged/instant latency is justified. Measure baseline and candidate on the same phones/network.
4. **Rollout retention.** Retained state is real and was replayed after both test clients exited. Large-scale rollout still needs quota monitoring and an acknowledged-final archival/cleanup policy. Do not erase an absent player's result on an arbitrary timer. Broker restart/disaster durability was not tested.

## Review limits

This is an honest-client, original-single-installation protocol. Shared application broker credentials and retained last-writer-wins topics do not become server-enforced game-rule authorization or distributed fencing. No malicious publisher, cloned host installation or administrative broker deletion guarantee is claimed. Network delivery adds uncertainty to remote countdown projections; local exact-millisecond tests do not prove millisecond agreement between two delayed screens.

## Checkpoints and rollback

- Implementation: local commit `d4abaac26a06004b97b97a26fb936907ef782126`; follow-up test/evidence commits are listed by `git log fix/heartbeat-codex` and the final manifest.
- Starting Fix003: `600a2591da5efcf2135f3c8d3e1570aac059bac0`. Earlier review evidence remains available and is not rewritten as passing.
- Protected restored baseline: `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9`, branch `1.4.1`, checkout `/home/bob/workspace/bingo_multiplayer`. Its 159 fingerprinted files and APK remain unchanged. `v1.4.1` remains the older release tag.
- Candidate APK: [Bingo-codex.apk](Bingo-codex.apk). It is separate from both protected/shared APK locations. No push, merge, release or deployment was performed.
- Reproduce/rollback by creating a separate checkout at the required existing commit. Preserve new-schema checkpoints and evidence; do not reset a protected checkout or overwrite app data just to return to an old implementation.
