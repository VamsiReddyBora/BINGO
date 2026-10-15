# Host-disconnection hold — repair and verification report

Prepared: **2026-10-15T18:49:35+05:30**. Branch: `fix/host-disconnected-wait`, worktree `/home/bob/workspace/bingo_host_wait`.

**The agreed temporary policy is implemented and component/live-MQTT verified. Android screen interaction remains unverified.** The guest waits for the fixed host behind a blocking popup. Earlier uninterrupted guest play, controller transfer and referee proposals are deferred by the user; they are not claimed as completed features.

## Behavior delivered

After the existing 15-second verified heartbeat threshold, Stuart sees **Host Disconnected** / **Waiting for the host to continue the game.** The popup has no buttons and rejects Back/outside dismissal. Game actions and countdown progression are held. On Kevin's return, a full persisted snapshot, matching application acknowledgement and correlated READY are required before release. Board, picks, real turn IDs, chat and exact logical timer remainder are preserved. Manual pause remains separate; zero expires once after recovery. Active-play duration excludes the hold.

Both phones must use this APK: lobby capability 3 and runtime `hostWaitVersion=1` prevent a silently incompatible recovery. No new backend, broker rules, per-move HTTP or control transfer were introduced.

## Executed evidence

| Check | Actual result |
|---|---|
| Final full unit run | **419 tests / 40 suites; 0 failures/errors/skips** |
| Additional regression methods | **21**; [exact executed index](evidence/RegressionTestIndex.md) |
| Android lint | **0 errors/fatal; 34 warnings** |
| Signed release build | Success; same signing certificate as baseline |
| Live MQTT | Eight alternating accepted picks, actual 15-second host silence, frozen guest, recreated host client and full recovery pass |
| Separate returning process | Canonical retained final recovered after writer JVM exited; no resurrection |
| Cleanup | Unique verification topic deleted; no real user invitations or existing-room changes |
| Protected baseline |159/159 source fingerprints unchanged; protected/shared and prior candidate APK hashes unchanged |
| Android UI/lifecycle | **NOT RUN**; no connected device, unavailable KVM |
| Physical baseline latency | **NOT RUN** |

Application commits: `e3c1eb32368bb8ce21061842948922ef3a29825b` and `f74da6e34e9a4982b8009711760a153fe7f0b5c9`. Earlier builds were intermediate; `evidence/final-build.log` identifies the final source build. Historical failures/intermediate logs remain clearly named.

## Case-by-case closure

| Case | Result and limit |
|---|---|
| Normal connected play | Production protocol and actual broker picks pass. Existing gameplay tests also pass. |
| Host leaves; 15-second detection | Existing pure policy threshold tests plus real 15-second live absence pass. Root wires that decision to a hold before diagnostic persistence. Actual Android lifecycle scheduling unrun. |
| Frozen timer and blocked picks/actions | Production-client test freezes exactly 14,250ms, blocks PICK/CHAT/PAUSE/RESUME/SURRENDER/CLAIM and remains unchanged through 120 seconds of synthetic downtime. |
| Non-dismissible popup | Actual production DialogProperties disable Back/outside dismissal; exact message test passes. Source-reviewed no buttons and root Back guards. Device taps/predictive Back unrun. |
| Host rejoin | Full SYNC→ACK→READY; owner and guest retain exact logical remainder under the injected clock. Live recovery verifies same boards/picks/turn and records projected times below. |
| Partial/brief recovery | Delayed ACK, dropped READY, replayed/wrong-generation frames and storage failure remain blocked or retry. A heartbeat/STATE alone cannot release the hold. |
| Already zero | Holds 0 through downtime; one accepted expiry after recovery gives the next turn 30,000ms. |
| Previously manually paused | Underlying pause, owner, exact time and chat preserved. Host popup takes precedence over the existing pause dialog; the manual pause remains on recovery. Device dialog layering unrun. |
| Guest closes/reopens | Independent client instance restores persisted hold and frozen remainder; no silent unlock. |
| Both apps absent | Synthetic 600,000ms downtime and cold host pending checkpoint recovery produce no timeout backlog. If host returns alone, observe 15 seconds before normal host continuation. |
| Guest leaves; host remains | Existing host continuation preserved; production-client and long timeout regressions pass. |
| Existing final / normal results | Durable final overrides leftover hold even offline. Legitimate Bingo/surrender and atomic statistics tests pass; live absent-peer final recovery passes. |
| Crash during an unpublished mutation | Pick/surrender candidates cannot invent accepted state; older accepted broker checkpoint wins. Missing shared proof stays blocked. |
| Old pause/timing snapshot | Cannot undo a known accepted pause or restart the countdown with duplicate READY. |
| Account/rematch isolation | Host-wait save/clear requires the exact account and seed. Existing account/CAS/legacy ownership tests pass. |

## Real MQTT trace

```text
PASS real broker negotiates both production clients
PASS accepted MQTT pick 1 reaches both clients
PASS accepted MQTT pick 2 reaches both clients
PASS accepted MQTT pick 3 reaches both clients
PASS accepted MQTT pick 4 reaches both clients
PASS accepted MQTT pick 5 reaches both clients
PASS accepted MQTT pick 6 reaches both clients
PASS accepted MQTT pick 7 reaches both clients
PASS accepted MQTT pick 8 reaches both clients
METRIC request-to-both-accepted samples=8 medianMs=334.0 p95Ms=405 rawMs=[264, 404, 202, 404, 151, 404, 151, 405] (50ms poll resolution; not physical tap latency)
PASS 15s host silence enters persistent blocking guest hold
PASS MQTT host hold freezes remainingMs=14678, board, turns and actions
PASS returning host and guest finish correlated recovery
PASS MQTT rejoin preserves board/picks/turn; frozenMs=14678, hostMs=14566, guestMs=14678
PASS prepared third-episode checkpoint published
PASS accepted final result retained before winner exits
PASS independent returning JVM recovers final result after winner JVM exited
PASS returned match stays final; no unfinished-match resurrection
```

The host is a newly created production client after the original connection/client closes; device stores are separate. The final reader runs in another JVM after writer exit. Heartbeat eligibility uses the actual pure policy reducer; this headless probe does not run Android's observer or UI. The third incident used for the terminal claim remains a prepared fixture.

Eight headless request-to-both-accepted samples have median **334.0ms**, nearest-rank p95 **405ms**, and 50ms polling resolution. This is not a physical tap measurement or a same-setup baseline comparison. Logical frozen remainder is exact; remote running countdown projections retain network-delivery uncertainty.

## Reproduce

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease :app:exportHostWaitVerificationClasspath -I artifacts/host-disconnected-wait/verify-classpath.init.gradle --offline --console=plain --max-workers=2
python3 artifacts/host-disconnected-wait/run_live_mqtt.py
```

Preserve prior live-probe outputs before rerunning. SDK and credentials must be configured locally; do not print secrets. The candidate is [Bingo-host-wait.apk](Bingo-host-wait.apk), 7,063,659 bytes, SHA256 `9db6b81303c28c6d1e66b55b0ffd9f47af758f0c343dd27ca7eb2e7fc2bb0eba`. Verified certificate SHA256 `39a428942c3c2629ff3490c944b8b82c7625b22d660b7a2cdf682456d81f60a4`. Full metadata: [ReviewManifest.json](ReviewManifest.json).

## Remaining verification

Install this APK on both test phones/emulators and execute home/background, swipe, force-stop, network toggles, repeated returns, Back/outside taps, manual-pause layering, chat/board preservation, expiry at zero and normal Bingo/surrender. Record both screens and accepted turn/remainder. Physical UI and baseline latency checks remain open; finite component/live-broker tests are not a guarantee covering every Android/network condition.

No source-level blocker was found in the final scoped review. The accepted model remains one original host installation with honest clients and existing broker retention. Broker disaster recovery, cloned/malicious publisher fencing and large-scale retention cleanup are outside this temporary UI/hold change; see the earlier ownership assessment for those existing limits.

The baseline and prior evidence were preserved. Nothing was pushed, merged, released or deployed. [Change journal](CHANGELOG.md) and [implementation details](IMPLEMENTATION.md) explain the rollback checkpoint and real code paths.
