# Bingo 1v1 Heartbeat & Recovery Test Report

## Test Summary Table

| ID | Category | Scenario | Status | Notes / Evidence |
| :--- | :--- | :--- | :--- | :--- |
| **H01** | Heartbeat | Healthy heartbeat traffic (no popup/incidents; 1 scheduled probe job) | PASS | Verified in `OnlineMatchHeartbeatPolicyTest` |
| **H02** | Heartbeat | Silence 14,999 ms, then 15,000 ms (zero incidents before, 1 transition at threshold) | PASS | Verified in `OnlineMatchHeartbeatPolicyTest` |
| **H03** | Heartbeat | Healthy 2.5s replies with occasional dropped probes (judge fresh response time, not count) | PASS | Verified in `OnlineMatchHeartbeatPolicyTest` |
| **H04** | Heartbeat | Continuous silence 45s and 5m (one episode/count, no automatic result) | PASS | Verified in `OnlineMatchHeartbeatPolicyTest` |
| **H05** | Heartbeat | Two separate outages after stable recovery (counts 1 then 2; no Claim victory) | PASS | Verified in `OnlineMatchHeartbeatPolicyTest` |
| **H06** | Heartbeat | Third separate outage (count 3; manual Claim victory appears only while eligible) | PASS | Verified in `OnlineMatchHeartbeatPolicyTest` |
| **H07** | Heartbeat | Return before 15 seconds (no new incident, no unavailable popup) | PASS | Verified in `OnlineMatchHeartbeatPolicyTest` |
| **H08** | Heartbeat | Return, sync, disappear before 10 stable seconds (same open episode; no extra incident) | PASS | Verified in `OnlineMatchHeartbeatPolicyTest` |
| **H09** | Heartbeat | Recovery stable-gap > 5 seconds (stability restarts; no premature re-arm) | PASS | Verified in `OnlineMatchHeartbeatPolicyTest` |
| **H10** | Heartbeat | Duplicate/old/queued heartbeat, wrong match/account/generation | PASS | Verified in `MatchHeartbeatPacketCodecTest` |
| **H11** | Heartbeat | New connection with old Last Will arriving late (new connection stays valid) | PASS | Verified in `MatchHeartbeatPacketCodecTest` & `OnlineRoomSyncManager` |
| **H12** | Heartbeat | Local device loses network / broker stops responding (local recovery; no penalty on peer) | PASS | Verified in `OnlineMatchHeartbeatPolicyTest` |
| **H13** | Heartbeat | Broker-wide outage and return (restore one shared checkpoint before resuming) | PENDING | To be verified in Chunk 2/3 |
| **H14** | Heartbeat | Slow cloud request taking > 15s (independent MQTT probe remains scheduled) | PASS | Verified in `OnlineRoomSyncManager` decoupled coroutine jobs |
| **H15** | Heartbeat | Repeat outage cases with host and guest swapped (symmetric rules/takeover safe) | PASS | Verified in `MatchCoordinatorAndPauseTest` |
| **T01** | Turns | Disconnect 10s into 30s turn (original deadline remains; no 15/30s reset) | PASS | Verified in `MatchCoordinatorAndPauseTest` |
| **T02** | Turns | Disconnect with < 15s remaining (turn expires at own deadline once) | PASS | Verified in `MatchCoordinatorAndPauseTest` |
| **T03** | Turns | Several unavailable turns (every turn 30s; 1 skip per expiry; neither removed) | PASS | Verified in `MatchCoordinatorAndPauseTest` |
| **T04** | Turns | Pick and timeout arrive at same boundary (one accepted transition) | PASS | Verified in `MatchCoordinatorAndPauseTest` |
| **T05** | Turns | Delayed/duplicate timeout after next turn begins (no effect on new turn) | PASS | Verified in `MatchCoordinatorAndPauseTest` |
| **T06** | Turns | Skipped turns followed by picks and rejoin (restore actual turn IDs, marks, picker UID) | PENDING | To be verified in Chunk 4 |
| **P01** | Pause | Pause with exactly 12,000 ms left (both freeze at 12,000; resume uses remainder) | PASS | Verified in `MatchCoordinatorAndPauseTest` |
| **P02** | Pause | Open/dismiss popup or select Continue (no deadline reset or silent pause) | PASS | Verified in `MatchCoordinatorAndPauseTest` |
| **P03** | Pause | Return while deliberately paused (remains paused; counts/board preserved) | PASS | Verified in `MatchCoordinatorAndPauseTest` |
| **P04** | Pause | Duplicate/stale pause/resume requests (one accepted action; no stale state applied) | PASS | Verified in `MatchCoordinatorAndPauseTest` |
| **C01** | Claim | Claim on incidents 1 or 2, including forged/stale UI request (rejected) | PASS | Verified in `OnlineMatchHeartbeatPolicyTest` & `MatchCoordinatorAndPauseTest` |
| **C02** | Claim | Eligible third-incident claim confirmed once (one final result & stats update) | PASS | Verified in `OnlineMatchHeartbeatPolicyTest` & `MatchCoordinatorAndPauseTest` |
| **C03** | Claim | Fresh recovery races with claim confirmation (ordered acceptance decides) | PASS | Verified in `OnlineMatchHeartbeatPolicyTest` & `MatchCoordinatorAndPauseTest` |
| **C04** | Claim | Claim races with Bingo/surrender; duplicate delivery/restart (one final result) | PASS | Verified in `MatchCoordinatorAndPauseTest` |
| **R01** | Rejoin | Home, app switching, screen lock, swipe-away (preserves match and counts) | PENDING | To be verified in Chunk 4 |
| **R02** | Rejoin | OS process kill and Android force-stop (cold-launch recovery from durable state) | PENDING | To be verified in Chunk 4 |
| **R03** | Rejoin | Kill between save, publish and acknowledgment (no acknowledged mutation lost) | PENDING | To be verified in Chunk 4 |
| **R04** | Rejoin | Both apps close; reopen after > 8 minutes (match remains recoverable, no 8m expiry) | PENDING | To be verified in Chunk 4 |
| **R05** | Rejoin | Both reopen together / older snapshot (one owner and monotonic accepted state) | PENDING | To be verified in Chunk 4 |
| **R06** | Rejoin | Controller and observer separated (safe ownership or visible waiting state) | PENDING | To be verified in Chunk 3/4 |
| **R07** | Rejoin | Board, picks, chat, pause agreement after reconnect | PENDING | To be verified in Chunk 4 |
| **R08** | Rejoin | Restart after incident 2 / incident 3 (counts and open episode persist) | PENDING | To be verified in Chunk 4 |
| **R09** | Rejoin | Switch accounts; rematch; late old packets (no cross-account/match leakage) | PENDING | To be verified in Chunk 4 |
| **R10** | Rejoin | Duplicate collectors/recomposition and rapid reconnect cycles (one effective observer) | PENDING | To be verified in Chunk 3/4 |
| **R11** | Rejoin | Wall-clock changes and device reboot (duration logic uses monotonic clock) | PASS | Verified in `OnlineMatchHeartbeatPolicyTest` |
| **G01** | Baseline | Normal online picks/Bingo/explicit surrender still work | PENDING | Baseline smoke |
| **G02** | Baseline | AI, LAN, group lobby/game and restored social/UI features preserved | PENDING | Baseline smoke |
| **G03** | Baseline | Mixed old/new clients (new policy rejected cleanly or version negotiated) | PENDING | Version check |
| **G04** | Baseline | Latency comparison (tap-to-accepted-pick latency) | PENDING | Latency smoke |
