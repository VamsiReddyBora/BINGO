# Simplified rules — fix and verification report

**Updated:** 18 October 2026, 19:44 IST  
**Source:** `/home/bob/workspace/bingo_multiplayer`  
**Branch:** `pre-release-ver-1.5`  
**Baseline HEAD:** `a5a089899fbface4f0db59e9fe5bb8efb0d4f911`

## Result

The 30 findings in `SimplifiedGameRulesComplianceReview.md` have been addressed in the online match source. The rule-related callbacks were traced again after the fixes. The automated run passed **677 tests in 59 suites, with 0 failures, 0 errors and 0 skipped tests**. This includes the opt-in real MQTT test.

Android screen interaction, visuals and physical-device behavior are left to the user, as requested. These results are code and automated-test verification, not certification of every device/network edge case.

## Confirmed behavior

- Temporary app switching, closure or network loss changes availability; it never permanently removes a player or awards a surrender result.
- An online-started turn retains its 30-second deadline when its owner leaves. Each later away/offline-started turn gets 5 seconds. Returning before an absence turn expires grants 30 seconds once.
- Timeouts close one turn, choose no number and never add forfeiture penalties. Commands include their closed-turn identity/clock; duplicate or superseded timeouts cannot close another turn.
- Picks mark all eligible boards. Offline players may win or be runners. N×N requires N lines. Caller priority and the confirmed existing non-caller tie-break are preserved.
- Explicit exit is permanent. Remaining eligible group players continue even when the creator exits; one eligible survivor wins. A locally durable outbox protects exit publication.
- Ongoing activity keeps recovery alive. Five minutes without human activity expires the match without a victory or forfeit loss. Automatic ticks, passes and replication do not refresh that activity timestamp.
- Recovery reads retained snapshots, accepted commands and departure receipts before enabling play. Everyone leaving preserves the saved turn/remainder; cold rejoin uses one bounded live-peer check to distinguish an active match from an empty room. There is no replay of hypothetical elapsed turns.
- Final results, boards and chat survive restart. Account-scoped stable result IDs prevent duplicate history/statistics application.

## Finding coverage

| Finding | Implemented correction | Running source path |
| :--- | :--- | :--- |
| R01 | Temporary transport LEAVE/LWT cannot surrender a player. | Root packet gate; manager availability topic |
| R02 | Timeouts never award a disconnection/three-miss forfeit. | Peer timeout reducer and online dispatch |
| R03 | Disconnect updates presence without advancing the current turn. | Generation-scoped presence handler |
| R04 | Match, room, sender, connection, turn clock and pause revisions fence stale controls. | SimplifiedMatchPacketGate; Root phase checks |
| R05 | Only an unexpired absence-started turn can receive one welcome-back 30s budget. | MultiplayerTurnClock |
| R06 | Turn commands carry shared deadlines and next-turn budgets; duplicate closes are rejected. | Turn clock; broker command stream |
| R07 | Any eligible peer can recover state, pass an expired turn and validate a result. | PeerMatchRecovery; allowPeerTimeouts; shared result evaluator |
| R08 | Recovery holds actions until retained catch-up; saved zero/remainder is preserved. | Recovery marker and cold-return live-peer probe |
| R09 | Ordinary misses do not change online/away/offline status. | MultiplayerGameplayReducer.timeout |
| R10 | Five minutes of human inactivity expires without a win/loss; remote activity is checked first. | Root inactivity check; MainMenu recovery; no PLAYING host TTL |
| R11 | Result flags/reason text cannot bypass board or permanent-departure proof. | MultiplayerResultRules; reducer; snapshot reconciler |
| R12 | Caller priority produces one winner and the other completing players as runners. | Shared result evaluator |
| R13 | Away/offline players remain eligible through evaluation, persistence and results rendering. | Result rules; Root finalization; GameScreen |
| R14 | Permanent departures are stored in the checkpoint and retained exit receipts. | OngoingMatchStore; PeerMatchRecovery |
| R15 | Sole survivor counts eligibility, including temporarily absent players. | Shared result evaluator |
| R16 | Permanent creator exit keeps the group room active; administrative role follows eligible roster. | leavePeerMatch; administrative roster update; metadata seed guard |
| R17 | An exit is queued durably before local removal; failed publication retries. | Account/match-scoped pending exit outbox |
| R18 | Exit retries use independent connections; cleanup captures the original room/session. | PeerMatchRecovery.publishExit; captured metadata cleanup |
| R19 | New generated/manual matches reset their checkpoint, result, clock and replay state. | startNewGame; manual setup and rematch paths |
| R20 | Boards, accepted history, checkpoint, departures and clock are saved in one record. | savePeerMatch; atomic SharedPreferences commit |
| R21 | Final checkpoints survive restart; stable account/room/seed IDs prevent duplicate stats. | Store tombstones; Root and MainMenu result recording |
| R22 | The winning pick advances its accepted history before final publication; durable commands fill snapshot gaps. | Peer command journal; result proof during cold recovery |
| R23 | A failed/unknown room lookup preserves recovery; delayed cleanup cannot delete a replacement match. | MainMenu; AuthGate; clearMatchIfCurrent |
| R24 | Stable account IDs identify local boards; display-name matching is removed. | LobbyLifecycleEngine.isPlayerMe; strict online context |
| R25 | Retained connection-scoped presence and fresh reconnect IDs fence old wills and restore status. | MultiplayerPresenceState; manager reconnect |
| R26 | Manual readiness uses the same eligible roster; original ready timestamps define the shared first clock. | Manual ready callbacks and BOARD_READY codec |
| R27 | Status colors and passing/welcome-back wording follow the documented presence and clock. | GameScreen status banner |
| R28 | Results use verified room existence for Play Again; closed/unknown rooms offer Main Menu. | Root result room lookup; GameScreen result actions |
| R29 | Online match snapshots are event-driven; old match heartbeat/full-state/cloud polling stops. | activePeerSeed guards; Nearby-only old periodic sync |
| R30 | Recovered chat feeds the mounted screen, deduplicates IDs and aligns messages by sender UID. | Root chat merge; GameScreen accepted history |

## Features preserved

Manual host pause/resume, the existing non-caller line-count/turn-distance tie-break, Ongoing Match Dismiss, social/friends features, lobby ping, AI and Nearby game modes remain. Administrative host badges are separate from peer turn/result authority. Temporary host absence does not transfer that badge.

Existing social presence and ping traffic remain for their UI features; they do not drive match turns or surrender outcomes. No external match referee or HTTP request per pick was added. Local taps render immediately; their turn/result commit comes through MQTT, including the publisher's echo. A short timeout settling/retry window handles packets at the deadline without repeated skips.

## Verification evidence

Command completed:

```sh
BINGO_LIVE_MQTT=1 ./gradlew :app:testDebugUnitTest --console=plain
```

The new production-policy regression tests cover clock budgets, mid-turn absence, one-time promotion, obsolete timeout clocks, duplicate packets, cold recovery, immutable card layouts, identity, chat orientation, durable departures, checkpoint/account isolation and result finality. The simulation suite runs **112 complete matches**, spanning 2–8 players and board sizes 5–8, with duplicate/reordered recovery commands. The existing broader suite also passed.

The real MQTT test uses isolated generated room/player IDs and no cloud room writes. It verifies two production managers complete recovery, both receive the publisher's committed pick, the pick survives both clients closing before a later snapshot, and an explicit retained exit produces the correct recoverable result. Its retained test data is cleaned afterward.

Evidence directory:

`/home/bob/workspace/bingo-simplified-rules-fix-20261018-183525/verification`

Full Android lint was not completed in the combined validation run. The final verified candidate test build is recorded at `/home/bob/workspace/bingo_multiplayer/releases/Bingo-1.5.0-SimplifiedRules-Test.apk` (signed with the project release key, SHA-256 `bf03f7ad0a336e81b5ac1c474e482b48d3bf676cab6bab4b662286f1da94da58`), built from final source with the manual-preparation guard. It is distinguished from a production R8-optimized release, which takes ~5.5 minutes and can be assembled via `./gradlew :app:assembleRelease` if requested. Compiler warnings are not represented as zero-warning certification.

The emulator startup attempt failed because `/dev/kvm` reported “No such device”; no emulator gameplay or screenshot pass is claimed. Device testing was then explicitly assigned to the user. This peer protocol has been tested under cooperative MQTT operation; arbitrary partitions, broker data loss and deliberately inconsistent device clocks are not proven impossible by these tests.

## Recovery and source ownership

The complete pre-fix working tree, including existing uncommitted changes, is backed up at:

`/home/bob/workspace/bingo-simplified-rules-fix-20261018-183525/before-source.tar.gz`

Use `before-hashes.json` and `before-status.txt` alongside it to restore selected files. Do not reset the whole repository. Pre-existing rules, scripts and unrelated user modifications were preserved. No commit, push or deployment was performed.
