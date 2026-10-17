# Bingo Multiplayer — Simplified Game Rules & Mechanics

**Updated**: 17 October 2026, 21:15 IST  
**Status**: Official Active Specification  
**Design Philosophy**: Casual, friendly, zero-stress multiplayer. Zero battery-draining heartbeats, zero blocking popups, zero controller dependencies, zero unfair forfeits.

---

## 1. Core Principles

1. **Equal Peers**: Every player is an equal peer. There is no master host controller policing clocks or watchdogs.
2. **Never-Lose Disconnection**: Disconnecting, minimizing the app, answering a phone call, or battery dying NEVER causes a player to lose the match.
3. **No Blocking Modals**: No interruption decision popups ("Pause vs Continue" / "Claim Victory") appear to block the screen.
4. **Append-Only Number Log**: The match state is 100% determined by the ordered list of picked numbers. Every called number is marked on every player's card.
5. **Only Two Ways a Game Ends**:
   - A player completes **5 Bingo lines** (Authoritative Bingo).
   - A player explicitly confirms **Surrender / Leave** via the in-game exit icon.

---

## 2. Player Presence States & Visual Indicators

Every player is in one of three clear states:

| Status | Trigger | Avatar Ring | Top Status Banner Text | Banner Color | Turn Budget |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **🟢 Online** | App in foreground (`onResume`) | Solid Emerald Green *(Pulses softly when it's their turn)* | `"<Name> is choosing..."`<br>`"<Name> is cooking something..."`<br>*(Or "Your Turn!" for local player)* | Green (`0xFF22C55E`) | **30 seconds** |
| **🟡 Minimized / Away** | App in background (`onStop`), phone call, or home button | Solid Warm Amber | `"<Name> minimized the game"` | Amber Yellow (`0xFFEAB308`) | **3 seconds** |
| **🔴 Disconnected / Offline** | App swiped away, socket closed, or internet dropped (MQTT LWT) | Solid Soft Red | `"<Name> is disconnected"` | Coral Red (`0xFFEF4444`) | **3 seconds** |

---

## 3. Turn Timers & The "Welcome Back!" Rule

### A. Active Turn (🟢 Online)
- The active player receives a full **30-second** turn budget to inspect their board and make a pick.
- When they tap an available cell, the number is marked immediately, sound plays, and the turn advances to the next player with a fresh 30 seconds.

### B. Away or Disconnected Turn (🟡 Away / 🔴 Disconnected)
- If it is an away/disconnected player's turn, the timer starts a quick **3-second grace countdown**:
  > *"Stuart is disconnected · Passing in 3s..."*
- This prevents remaining active friends from waiting 30 seconds for an empty chair.

### C. The "Welcome Back!" Rule
- If the player returns (comes 🟢 **Online**) with **1s or 2s remaining** in that 3-second grace window:
  - **The timer immediately resets to the full 30 seconds!**
  - Banner updates: `"<Name> is back! <Name> is choosing..."`
  - The returning player is never rushed and has full time to analyze their board.

### D. Turn Expiry at 0s (Pure Turn Pass)
- If the timer hits **0s** without a pick:
  - The turn **smoothly passes to the next player**.
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
  - **Mid-Turn Surrender**: If it was currently that player's turn, the turn **immediately advances** to the next eligible player with a fresh 30 seconds (no waiting for remaining time).
  - The remaining players continue the match until someone gets BINGO.
  - **Sole Survivor**: If all other players surrender and only 1 player remains, that player is awarded the Victory!

---

## 5. Winning & Results Screen

1. **First to 5 Lines**: The player who calls the pick that completes their 5th line is crowned **Winner**.
2. **Simultaneous Completion**: If another player's 5th line also completes on that same pick, they receive **Runner-up**.
3. **Play Again / Rematch**:
   - The **"Play Again"** button appears on the results screen **only if the lobby/room still exists**.
   - If the room has already been closed or dissolved, only the **"Return to Main Menu"** button is displayed.

---

## 6. Seamless Rejoin & Persistence

1. **Auto-Save on Pick**: Every called number and board state is saved to local storage on every pick.
2. **Rejoin Card**: When an un-surrendered player reopens the app, an **"Ongoing Match"** card is shown on the Main Menu.
3. **Instant State Catch-Up**: Tapping **Resume** connects to the room, synchronizes all picks called while away, and restores the live game board instantly.

---

## 7. Audio & Sensory Rules

1. **Sound on Tap Only**: Satisfying cell-click sounds play **only** when tapping a cell on the board.
2. **Zero Siren / Alarm Sounds**: No jarring countdown beeps, turn change dings, or disconnection alerts.
