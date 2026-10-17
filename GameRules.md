# Bingo Multiplayer — Game Rules

Updated: **17 October 2026, 13:50 IST (UTC+05:30)**.

Status: **Requirements reference. Creating this document does not change the app or certify that every rule is implemented.**

## 1. Purpose and priority

Use this file when planning, implementing, reviewing or testing multiplayer behaviour. Keep online **1v1** and online **group matches (3–8 original participants)** separate where their rules differ.

The user's latest decisions are:

1. **Group matches keep the 60-second host-absence limit.**
2. **Tapping the in-game Exit button is an intentional, permanent departure. The remaining players continue the same match.**

These decisions override earlier suggestions that Exit should preserve a rejoin opportunity, or that group matches should wait indefinitely for an absent host. Later explicit user decisions take priority over this document. Older implementation plans and review reports are historical references; they must not silently override these rules.

The group timing settings listed in section 7 are recorded baseline behaviour, rather than newly confirmed requirements. Preserve them while implementing the confirmed changes; do not copy 1v1 policies into group matches or classify a deliberate policy as a bug without checking.

## 2. Meaning of the actions and states

| Action or state | Meaning | Participation in the same match |
| --- | --- | --- |
| App moved to background / switching apps | Temporary unavailability. | Preserved, subject to the match still being open. |
| Internet or MQTT connection lost | Temporary unavailability. | Preserved, subject to the match still being open. |
| App swiped away, force-closed, crashed or killed by Android | Temporary unavailability, unless a permanent Exit or Surrender was already recorded. | Preserved, subject to the match still being open. |
| **In-game Exit button** | **Intentional, permanent departure.** | **Removed; the player cannot rejoin this match as a participant.** |
| Explicit Surrender | Intentional surrender under the existing surrender rules. | Removed permanently. |
| Match already finished | Its accepted result is final. | Show the result; do not resume play. |

An unavailable player and a permanently departed player are different states. Being the only player currently online does not mean being the only participant remaining in the match.

A separate **Return to Lobby** control, if present, must be mapped and documented separately. The latest decision defines the in-game **Exit** action; it does not automatically redefine every navigation button or handler with “leave”, “back” or “lobby” in its name.

## 3. Permanent Exit — applies to every player

### Required behaviour

- The exiting player permanently stops participating in that match.
- Remove that player from future eligible turns. Keep their previous picks, board history and accepted chat as historical match data.
- A later app launch, reconnect, heartbeat or old recovery record must not put that player back into the match.
- If it was the exiting player's turn, close that turn exactly once and give the next eligible player a normal **30-second** turn.
- If someone else owns the turn, preserve that player's current turn and remaining time.
- A peer's Exit does not automatically cancel an existing pause. If that Exit starts a new turn while paused, preserve the pause and freeze the new turn's full 30 seconds until a valid resume.
- Repeated Exit messages must not close additional turns, reset countdowns or change an already accepted result.
- Keep the same match, boards, selections, chat history and remaining players. A group reduced to two players continues its original match; it does not restart as a new 1v1 game or switch to AI.
- Preserve the permanent Exit decision across an app restart or connection interruption. A pending Exit must not be converted into an automatic rejoin. Other players can react only after receiving a valid accepted departure.
- An Exit belonging to an older match must not affect a new match or rematch.

### How many players remain?

Count participants who have not permanently departed or been removed under an explicit applicable gameplay policy. A temporarily offline participant still belongs to the match.

| Remaining eligible participants after accepted permanent departures | Required outcome |
| --- | --- |
| Two or more | Continue the same match among them. |
| One | Finish using the existing explicit-departure/forfeit outcome for that remaining participant. This is caused by intentional departures, not by heartbeat silence. |
| None | Close the match without inventing a winner. |

This document does not introduce new statistics penalties or change existing Bingo winner/runner selection.

### When the exiting player is the host

**The permanent Exit rule applies to the host too.** If enough participants remain, their match must continue. Do not treat the host's accepted intentional Exit as a temporary absence that waits for that host to return or runs out the 60-second recovery window.

This is a required outcome, not a claim that the current controller implementation supports it. The existing code relies on the original host for group control. Continuing after that host permanently exits therefore needs a reviewed design that keeps one authoritative turn sequence. The implementation must explain and test how the remaining match stays controllable. This document does not choose the controller handoff mechanism or authorize its implementation.

## 4. Online 1v1 — heartbeat and interruption rules

| Setting | Required rule |
| --- | --- |
| Heartbeat interval | **2.5 seconds** while foregrounded and participating. Heartbeats are completely silent: no sound or vibration. |
| Unavailable threshold | **15 seconds** without a verified fresh response, while the observing phone can reliably judge the peer. |
| First and second distinct interruptions | Offer **Pause game** and **Continue countdown** to the host when the non-host is unavailable. |
| Third and later distinct interruptions | Also offer **Claim victory**, only while the opponent is still unavailable and the claim remains eligible. |
| Decision popup lifetime | **15 seconds**. Outside taps, Back and back gestures do not dismiss it. A choice or its timeout resolves it. |
| No choice before popup timeout | Automatically choose **Continue countdown**. Opening the popup does not automatically pause the match. |
| Normal turn | **30 seconds**. A temporary outage does not shorten the turn. |
| Turn reaches zero | Skip that open turn exactly once. Give the next turn its normal duration. |
| Automatic defeat from silence or missed turns | Not allowed. An eligible victory claim must be explicitly chosen and accepted. |

### Counting interruptions

- Count distinct absence episodes per player and per match, not missed heartbeat packets or missed turns.
- One continuous absence remains one interruption, even if it lasts several turns.
- A short interruption that never reaches 15 seconds does not create a warning episode.
- Repeated packets, reconnection attempts and process restarts must not add duplicate warnings.
- Retain the existing recovery qualification: synchronize state and receive stable fresh responses for **10 seconds**, with no response gap over **5 seconds**, before another distinct interruption can be counted.
- Briefly returning and disappearing before completing that recovery qualification remains the same episode.
- Starting a new match resets that match's counts. Rejoining an unfinished match preserves its counts.
- Once a fresh response establishes recovery, disable an unavailable-opponent victory claim. Do not leave it usable while synchronization completes.

### Pause and resume

- The host controls manual pause and resume.
- Pause freezes the accepted remaining turn time. For example, pausing at **17 seconds** resumes at **17 seconds**, rather than starting another 30 seconds.
- The paused popup shows the opponent's current connection status.
- A reconnecting non-host may see the paused state but cannot resume it independently.
- The non-host's reconnection informs the host; it does not grant the non-host resume control.

## 5. Online 1v1 — temporarily unavailable host

When the host is determined unavailable:

1. Freeze picks and game countdowns.
2. Show a blocking host-unavailable waiting popup on the guest's screen.
3. Outside taps, Back and back gestures do not close it.
4. Keep it until the host returns and the accepted match state is synchronized, or a valid final result is recovered.
5. Resume the accepted remaining time and state after recovery.

The guest does not take over control or use the non-host interruption-choice popup to bypass this waiting state. **The group's 60-second host-absence limit does not apply to 1v1.**

An accepted intentional host Exit is handled by section 3 instead. In 1v1, the remaining participant receives the existing explicit-departure outcome.

## 6. Online group matches — confirmed host-absence rule

**Retain the existing total limit of 60 seconds for temporary host absence.** It is not 60 seconds in addition to the initial silent buffer.

| Time without a verified fresh host response | Required group behaviour |
| --- | --- |
| Before 10 seconds | Keep the existing initial silent-buffer behaviour. Do not invent a permanent Exit. |
| At 10 seconds | Enter the existing host HOLD state and freeze picks/gameplay countdowns. The remaining host-wait allowance is **50 seconds**. |
| Host returns before the 60-second limit | Verify recovery, synchronize state, run the existing **3-second** ready countdown, then resume the preserved remaining turn time. |
| 60 seconds reached without valid recovery | End the match under the existing host-disconnected policy. Do not invent a Bingo or award an automatic victory to the remaining guest merely because the host is absent. |
| Match already ended | A late heartbeat or old packet cannot reopen it. |

The HOLD/ready countdowns can progress while the gameplay turn is frozen. A host return verified within the allowed window can complete its normal ready countdown; the ready countdown is not another period of host absence.

**Intentional host Exit follows section 3.** It permanently removes the host and requires the remaining participants' match to continue when enough remain. It must not be mistaken for the temporary-absence case in this section.

## 7. Other group policies — preserve the baseline

The user confirmed the 60-second host limit and permanent Exit. Other deliberate group policies are protected by the instruction to preserve existing features. The following settings were observed in the current baseline and are recorded here to prevent an agent silently replacing them with 1v1 settings:

| Baseline group setting | Recorded behaviour |
| --- | --- |
| Normal turn budget | 30 seconds. |
| Current remote player's inactivity detection | 10 seconds. |
| Host inactivity decision window | 10 seconds, followed by the existing automatic skip if unanswered. |
| Group pause window | 30 seconds. |
| Extend pause | Existing extension becomes available with 5 seconds or less remaining. |
| Recovery/ready countdown | Existing 3-second countdown. |
| Elimination and pause-expiry/reconnect handling | Retain the current applicable group policies. |

These are **baseline observations, not newly confirmed policy decisions**. In particular, the group's existing inactivity skip can occur before the normal 30-second budget. Do not claim the 1v1 full-turn outage rule also describes that group exception. If a task would change these settings or their meaning, obtain a specific rule decision first.

A defect in applying a chosen policy is different from the policy itself. For example, tracking the wrong player after a turn change is a defect; choosing a different intended inactivity duration is a policy change.

## 8. Reconnection, shared state and final results

- Recognize an eligible player's existing unfinished match and support its recovery through the existing rejoin flow.
- A permanently exited or surrendered player is ineligible to rejoin that match as a participant.
- Recover the same board layouts, marked numbers, pick ownership/history, accepted chat, current player, turn number, pause state and remaining time.
- Synchronize before enabling moves. Both phones must follow one accepted turn sequence.
- If the match continued while a non-host was unavailable, rejoin the current accepted turn. Missed turns cannot be recovered or replayed.
- If the match was frozen, resume its frozen remainder. Do not reset to 30 seconds unless a genuinely new turn begins.
- Duplicate or delayed picks, timeouts, departures, heartbeats and snapshots must not apply a turn transition twice or roll back accepted progress.
- A new match/rematch has its own identity. Its board, messages, warnings and result must not inherit delayed events from the previous match.
- Keep the selected online mode. Reconnection does not switch the match to AI or regenerate boards.
- Keep immediate legal pick feedback. A locally predicted Bingo becomes a final result only after authoritative acceptance.
- Preserve the accepted final result so an eligible returning player can receive it even after another app closes. Record the result once and never reopen a completed match.
- Temporary app closure alone does not mean Surrender or permanent Exit. Suspended apps are not assumed to keep executing timers; recovery must not replay a backlog of unattended timeouts.

## 9. Notifications and existing UI

- Use accurate player names and the existing approved connection wording. Missing responses establish unavailability; they do not prove whether the cause was internet loss, backgrounding or an app crash.
- Heartbeats have no vibration, pick sound or repeated toast.
- Keep the approved centred text-only in-game chat status messages, without filled message backgrounds. Keep the appropriate decision and blocking waiting popups.
- Show connected/reconnected status in green and unavailable status in red using the current approved design.
- Preserve player photos and existing badges, lobby ping, detailed presence, friends/leaderboard, QR, statistics, scorecards, chat typing and notification features.
- AI and Nearby policies are outside these new online rules. Preserve their existing behaviour unless a separate user instruction changes it.

## 10. Examples for implementation and testing

| Case | Required visible outcome |
| --- | --- |
| Stuart backgrounds the app during a 1v1 match | Temporary absence. Kevin gets the qualified interruption popup; Stuart retains rejoin rights. |
| Stuart swipes away the app without pressing in-game Exit | Temporary absence. Do not permanently remove Stuart or infer Surrender. |
| Stuart presses in-game Exit in a group match | Permanent departure. Remove Stuart from future turns; the remaining participants continue the same match. |
| Kevin presses in-game Exit as the group host | Permanent departure. Remaining participants continue when enough remain; this requires a safe control design, not a waiting popup for Kevin. |
| Kevin temporarily loses connection as group host | Existing 10-second buffer, then up to 50 seconds of HOLD. Recover within the limit or end under the existing host-disconnected policy. |
| Kevin returns after 35 seconds of group host absence | Synchronize, show the existing 3-second ready countdown and resume the frozen turn remainder. |
| Kevin never returns within the group limit | End at the 60-second limit under the host-disconnected policy; do not label another player as having scored Bingo. |
| A 1v1 turn continues from 30 seconds to zero during an outage | One expiry closes that turn once. The next turn receives its normal 30 seconds. |
| A player's non-current-turn permanent Exit arrives | Preserve the current player's remaining time. |
| An Exit message is redelivered | No extra skip, countdown reset or repeated result. |
| An exited player restarts the app | No participant rejoin to that match. Their old accepted actions remain historical data. |
| A returning eligible player missed the match's final result | Recover and display the accepted result, rather than restart or invent an outcome. |

## 11. Rules for agents working from this file

1. Treat the latest user decisions and mode-specific rules above as the requirements reference. Flag any unresolved interpretation before changing a policy.
2. Distinguish required behaviour, retained baseline behaviour and actual implementation status in every review. A requirement written here is not evidence it already works.
3. Review the permanent Exit path separately from temporary background/network/app-close recovery. Map actual UI controls to their intended actions before editing handlers.
4. Explain the host-Exit control dependency before implementation. Do not silently keep the old “host left, end the group match” behaviour when the requirement is continuation.
5. Keep gameplay on the existing MQTT transport. Do not introduce a new gameplay backend, change the network architecture, or add a controller-transfer mechanism while merely documenting these rules.
6. Preserve a backup and a change log for future implementation. Keep changes limited to the approved task and preserve unrelated features.
7. Verify real implementation paths and then simulate the relevant cases on Kevin and Stuart. Identify source findings, automated test results and observed device behaviour separately.
8. A passing test count does not establish complete compliance. Report untested cases and implementation gaps explicitly; do not promise zero bugs.

**Work performed for this request: this rules document only. Application source, gameplay, APK and release configuration are unchanged.**
