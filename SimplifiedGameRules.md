# Bingo Multiplayer — Simplified Game Rules & Mechanics

**Updated**: 18 October 2026, 17:54 IST (UTC+05:30)
**Status**: Official Active Specification
**Design Philosophy**: Casual, friendly, zero-stress multiplayer. Zero battery-draining heartbeats, zero blocking popups, zero controller dependencies, zero unfair forfeits.

**Specification priority**: The rules and clarifications in this document take priority over conflicting older version 1.5.0 plans and game-rule documents. Updating this specification does not mean the application already implements it.

---

## 1. Core Principles

1. **Equal Peers**: Every player is an equal peer. There is no master host controller policing clocks or watchdogs.
2. **Never-Lose Disconnection**: Disconnecting, minimizing the app, answering a phone call, or battery dying NEVER causes an automatic loss, surrender or removal. A legitimate Bingo can still finish the match, and an away/offline player remains eligible to win it.
3. **No Blocking Modals**: No interruption decision popups ("Pause vs Continue" / "Claim Victory") appear to block the screen.
4. **Append-Only Number Log**: The match state is 100% determined by the ordered list of picked numbers. Every called number is marked on every player's card.
5. **Only Two Ways to Decide a Winner**:
   - An eligible player completes the board-size Bingo target: **N completed lines on an N×N board** (5 for 5×5, 6 for 6×6, and so on).
   - Explicitly confirmed **Surrender / Leave** leaves only one eligible participant.
6. **Inactivity Is Not Defeat**: A match has a **5-minute inactivity window**. Inactivity expiry closes an abandoned match without awarding a victory or a forfeit loss. Ongoing player activity keeps the match alive without a fixed overall duration limit; see section 6.

---

## 2. Player Presence States & Visual Indicators

Every player is in one of three clear states:

| Status | Trigger | Avatar Ring | Top Status Banner Text | Banner Color | Budget if this is the player's status when their turn starts |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **🟢 Online** | App in foreground (`onResume`) | Solid Emerald Green *(Pulses softly when it's their turn)* | `"<Name> is choosing..."`<br>`"<Name> is cooking something..."`<br>*(Or "Your Turn!" for local player)* | Green (`0xFF22C55E`) | **30 seconds** |
| **🟡 Minimized / Away** | App in background (`onStop`), phone call, or home button | Solid Warm Amber | `"<Name> minimized the game"` | Amber Yellow (`0xFFEAB308`) | **5 seconds** |
| **🔴 Disconnected / Offline** | App swiped away, socket closed, or internet dropped (MQTT LWT) | Solid Soft Red | `"<Name> is disconnected"` | Coral Red (`0xFFEF4444`) | **5 seconds** |

Presence indicators can update during a turn. The turn budget is chosen at the start of that turn; a later away/offline status does not shorten an already-running 30-second turn.

---

## 3. Turn Timers & The "Welcome Back!" Rule

### A. Active Turn (🟢 Online)

- Check the player's status immediately before starting their turn. An online player receives a full **30-second** turn budget to inspect their board and make a pick.
- When they tap an available cell, the number is marked immediately and sound plays. Evaluate Bingo first. If the match continues, advance to the next eligible player and choose that new turn's budget from that player's status: **30 seconds online; 5 seconds away/offline**.
- **Leaving during an existing turn does not shorten it.** If Stuart started online and backgrounds, swipes away or loses internet with 18 seconds remaining, that same countdown continues from **18 to 0**. It is not replaced by a 5-second countdown.
- Returning during that originally 30-second turn preserves its remaining time; it does not grant another fresh 30 seconds.

### B. Away or Disconnected Turn (🟡 Away / 🔴 Disconnected)

- If the player is already away/disconnected **when their next turn starts**, start a **5-second grace countdown**:
  > *"Stuart is disconnected · Passing in 5s..."*
- This prevents remaining active friends from waiting 30 seconds for an empty chair.
- Recheck status at the start of every subsequent turn. Remaining away/offline does not remove the player from future rotations.

### C. The "Welcome Back!" Rule

- If a turn started with the **5-second absence budget** and the player returns (comes 🟢 **Online**) **before that turn expires**:
  - **The timer immediately resets to the full 30 seconds!**
  - Banner updates: `"<Name> is back! <Name> is choosing..."`
  - The returning player is never rushed and has full time to analyze their board.
- Example: Stuart returns with 4, 2 or 1 seconds remaining in the 5-second countdown; he receives a fresh 30 seconds.
- Once that turn has received its 30-second budget, later status changes do not shorten or repeatedly reset it. This follows the same mid-turn rule as section 3A.
- If the turn has already expired and passed, returning does not recover that missed turn. The player rejoins the current match state and waits for their next turn.

### D. Turn Expiry at 0s (Pure Turn Pass)

- If the timer hits **0s** without a pick:
  - The turn **smoothly passes to the next eligible player exactly once**. The new turn's budget follows the status-at-start rule above.
  - **No auto-pick**: No random numbers are chosen.
  - **No penalty**: The player is not removed or penalized.
  - When the turn rotation comes back to them, they get their turn again.

---

## 4. Exit & Surrender Behavior

All exits are performed voluntarily via the existing **Exit icon** in the top bar:

### A. In 1v1 Matches

- When a player confirms Exit / Surrender:
  - The match terminates immediately.
  - The opponent is awarded an instant **Victory** (`"Opponent Surrendered"`).
  - The match is purged from the surrendering player's storage so it does not linger.

### B. In Multiplayer (Group Matches, 3–8 Players)

- When a player confirms Exit / Surrender:
  - A notification is posted in the in-game chat:
    💬 **`"<Name> surrendered"`**
  - The surrendering player is permanently removed from the remaining turn rotation.
  - **Mid-Turn Surrender**: If it was currently that player's turn, the turn **immediately advances** to the next eligible player (no waiting for remaining time). The new turn uses section 3: **30 seconds if online; 5 seconds if already away/offline**.
  - The remaining players continue the match until someone gets BINGO.
  - **Sole Survivor**: If all other players surrender and only 1 player remains, that player is awarded the Victory!

---

## 5. Winning & Results Screen

1. **Board-Size Target**: An N×N board requires **N completed Bingo lines**. For example: 5×5 requires 5 lines, 6×6 requires 6 lines, and 7×7 requires 7 lines. Do not hard-code a five-line target for every board size.
2. **Every Pick Counts for Every Eligible Board**: A called number counts on every participant's board, including participants who minimized the app, lost internet or closed the app unintentionally. Those players remain eligible to win unless they explicitly surrendered.
3. **Another Player's Pick Can Win Your Match**: If Kevin calls a number that completes Stuart's target while Kevin remains below his own target, **Stuart wins immediately**. Stuart wins even if he is away/offline. His return is not required to accept the legitimate result.
4. **Simultaneous Completion — Caller Priority**: If the same pick completes the target for the player whose turn it is **and** other participants, the player whose turn produced that pick is **Winner**; the other participants completing their target on that pick are **Runner-up**, including away/offline participants.
   - Example: On a 5×5 board, Kevin picks a number that brings both Kevin and Stuart to 5 lines. Kevin wins and Stuart is runner-up.
   - Example: On a 6×6 board, Stuart picks a number that brings both Stuart and Kevin to 6 lines. Stuart wins and Kevin is runner-up.
   - This does not let a caller below their own target defeat another player who has completed the target.
   - **Existing group tie-break (confirmed)**: If several non-callers complete their target while the caller remains below target, preserve the existing comparison by completed-line count, then distance in the turn order. Do not introduce host or online priority.
5. **Accepted Results Stay Final**: A player returning after a legitimate Bingo sees that accepted result rather than restarting the match. Disconnection itself never creates a forfeit result.
6. **Play Again / Rematch**:
   - The **"Play Again"** button appears on the results screen **only if the lobby/room still exists**.
   - If the room has already been closed or dissolved, only the **"Return to Main Menu"** button is displayed.

---

## 6. Seamless Rejoin & Persistence

1. **Auto-Save on Pick**: Every called number and board state is saved to local storage on every pick.
2. **Rejoin Card**: When an un-surrendered player reopens the app while the match is still unfinished and within its recoverable lifetime, an **"Ongoing Match"** card is shown on the Main Menu.
3. **Instant State Catch-Up**: Tapping **Resume** connects to the room, synchronizes all picks called while away, and restores the same live game board. Apply the turn rules in section 3; do not replay missed turns.
4. **5-Minute Inactivity Window**: Keep the match recoverable for **5 minutes without player activity**. Further player activity refreshes the inactivity window. This is an inactivity limit, not a fixed 5-minute match duration.
5. **Activity Keeps the Match Alive**: While player activity continues, the match can continue indefinitely until a legitimate Bingo or the applicable explicit-surrender outcome finishes it. One player's absence does not start a separate forced-loss deadline for that player.
6. **Everyone Closes the App**: If everyone becomes inactive, the same match remains recoverable within the 5-minute inactivity window. Returning within that window permits recovery; resumed activity keeps the match alive again. Resume the frozen turn with its saved remaining countdown; do not advance through deadlines elapsed while all apps were closed. Do not require closed apps to remain running to preserve that recovery opportunity.
7. **Inactivity Expiry**: If the full 5-minute inactivity window expires without further player activity, close/expire the abandoned match. Award **no automatic victory and no forfeit loss**. Do not pretend that anybody surrendered or completed Bingo.
8. **Keep the Timers Distinct**: The **5-second** countdown passes an absent player's individual turn. The **5-minute** inactivity window controls how long an inactive match remains recoverable. Automatic countdown ticks/passes are not player activity and must not keep an abandoned match alive forever.
9. **Existing Activity Meaning**: Use the application's normal player-activity meaning for its inactivity timer; this clarification does not introduce a new heartbeat-based definition of activity.

### Clarification scenarios

| Scenario | Required outcome |
| :--- | :--- |
| Stuart starts online, then leaves with 18 seconds remaining | Keep that turn's existing 18-to-0 countdown. No shortening to 5 seconds. |
| Stuart is still away when his following turn starts | Give that turn 5 seconds, then pass once if he remains absent. |
| Stuart returns with 2 seconds left in an absence-budget turn | Reset that turn to 30 seconds. |
| Stuart leaves again after receiving the welcome-back 30 seconds | Preserve that running countdown; no reduction or repeated reset from status changes. |
| Kevin's pick completes only offline Stuart's Bingo target | Stuart wins immediately; Kevin's turn ownership does not override Stuart's legitimate Bingo. |
| Kevin's pick completes both Kevin's and Stuart's targets | Kevin wins; Stuart is runner-up, even if Stuart is away/offline. |
| Everyone closes the app and someone returns within the 5-minute inactivity window | Recover the same unfinished match and resume participation under the turn rules. |
| No player activity occurs for the full 5-minute inactivity window | Expire the match without a winner or forfeit loss. |
| The match has ongoing player activity for more than 5 minutes | Keep it active; there is no fixed overall 5-minute duration cap. |

---

## 7. Audio & Sensory Rules

1. **Sound on Tap Only**: Satisfying cell-click sounds play **only** when tapping a cell on the board.
2. **Zero Siren / Alarm Sounds**: No jarring countdown beeps, turn change dings, or disconnection alerts.
