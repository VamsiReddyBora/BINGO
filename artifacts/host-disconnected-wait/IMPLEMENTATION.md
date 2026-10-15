# Temporary host-disconnection hold

User-approved scope: keep the original host as the only game controller. When the host is unreachable, the guest waits behind a blocking popup; synchronize and resume the same accepted game when the host returns. Controller transfer and an independent referee are deferred.

## Runtime behavior

- Detection retains the existing 15-second verified heartbeat rule. Silence is measured from the last fresh response, not the visible turn timer. An unresponsive host is not automatically defeated.
- The guest sees **Host Disconnected** / **Waiting for the host to continue the game.** No action buttons. Back and outside dismissal are disabled, and pending surrender/interrupt dialogs are hidden. Operating-system Home/termination remains possible.
- Local guest requests for pick, chat, pause, resume, surrender and claim are blocked during the hold. The board, actual turn IDs, history and exact remaining milliseconds remain frozen.
- A fresh host response alone does not release the popup. A complete validated snapshot is persisted, application-acknowledged, and followed by the host's correlated READY response. Old requests, snapshots and READY packets cannot release the current attempt.
- The fixed host writes a system recovery hold before resuming. The system hold is separate from manual pause. On matching game positions, the host reconciles the guest's persisted frozen remainder without increasing the host's known remainder. A different accepted turn/board cannot be rolled back to a stale guest preview.
- The recovered full snapshot preserves boards, accepted picks and real turn IDs, chat, turn owner, episode records and final results. Normal new turns receive 30,000ms; resumption consumes the existing remainder. A zero remainder is held while disconnected, then expires once after recovery.
- Match play duration uses accepted elapsed active time, excluding the system hold and manual pause. Monotonic clocks are not compared across devices or boots.
- A locally staged, unpublished candidate is not proof of an accepted move/result after a crash. Recover through retained broker state. If the shared checkpoint is missing and only a pending candidate exists, remain waiting.
- A known durable final result takes priority over an old local host-wait marker, including offline cold launch. Legitimate Bingo/surrender and idempotent statistics retain their existing accepted-result path.
- If the host returns alone, keep the recovered state suspended during a 15-second recovery observation window; then allow the host to continue under the existing guest-absence policy. Do not replay elapsed offline time or a timeout backlog.

## Implementation locations

- `domain/engine/ReliableMatchCoordinator.kt`: exact system hold/resume, manual-pause separation and active-duration clock.
- `domain/network/ReliableMatchClient.kt`: persisted guest frozen preview, recovery attempt correlation, SYNC → application ACK → READY gating, retry/fault handling.
- `domain/network/OngoingMatchStore.kt`: account/match-scoped host-wait persistence; preserve local observation metadata during snapshot writes.
- `domain/network/OnlineRoomSyncManager.kt`, `ReliablePolicyAgreement.kt`: explicit host-wait support. Lobby capability is version 3; runtime packets retain the v2 envelope and require `hostWaitVersion=1`. Both phones must use this candidate. An older client cannot silently unlock the match.
- `presentation/game/HostDisconnectedDialog.kt`, `GameScreen.kt`: non-dismissible popup and suppression of guest interruption choices/urgent timer effects while suspended.
- `presentation/navigation/RootNavGraph.kt`: connect real heartbeat decisions to the hold, block Back/actions, restore markers, serialize installation identity and reject late start attempts. Freeze the guest immediately on the decision even if diagnostic persistence is failing.

All source paths are under `app/src/main/java/com/bingo/multiplayer/`. Existing AI/LAN/group paths remain separate. No new cloud service, broker rule, per-move HTTP, deployment or authority transfer is introduced.

## Verification scope

The test report records actual executed names and results. Fake-clock protocol tests use real production coordinator/client/policy/storage code and explicitly injected transports. Dialog tests inspect the actual production DialogProperties and message; they do not simulate Android taps. The live MQTT probe uses independent client stores, closes/recreates the host connection/client, freezes the guest after the real 15-second interval, and checks same-state recovery. A separate JVM verifies final-result recovery after the writer exits.

No usable Android device is connected, and /dev/kvm is unavailable. Real Android background/swipe/Back/outside-tap behavior and a physical baseline latency comparison remain unverified. Ordinary picks retain the existing MQTT acceptance path; no assertion of unchanged phone latency is made. Countdown rendering across phones has normal network-delivery uncertainty; exact logical remainder tests do not prove millisecond agreement of remote screens.

## Preserve and roll back

Starting commit: `100325391c23ab306f0c40b56e7cea30d5fb8f17`.

Worktree: `/home/bob/workspace/bingo_host_wait`, branch `fix/host-disconnected-wait`.

Build output: `artifacts/host-disconnected-wait/Bingo-host-wait.apk`.

Protected restored baseline: `/home/bob/workspace/bingo_multiplayer`, branch `1.4.1`, commit `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9`. Original candidate and both protected/shared APK files remain unchanged. Use a separate checkout at the starting commit for source rollback and retain ongoing-save data/evidence. No push/merge/release/deployment is authorized by this implementation.
