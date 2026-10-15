# Bingo 1v1 Heartbeat & Recovery Test Report (Fix001 Delivery)

**Prepared**: 2026-10-15 (Asia/Kolkata)  
**Branch**: `fix/heartbeat-fix001`  
**Commit**: `bd4c5c1`  
**Candidate APK**: `artifacts/heartbeat-fix001/Bingo-fix001.apk` (SHA-256: `6a96c7148629d777bbd203b4ac1849ee92bdb417c1802bdd6a274c267e186437`)  
**Overall Readiness**: **NOT READY** (Code repairs HB01–HB14 complete; unit & probe tests 100% PASS; live device pairing and hardware latency NOT RUN / BLOCKED due to headless execution environment).

---

## 44-Scenario Acceptance Matrix

| ID | Category | Scenario | Status | Evidence / Notes |
| :--- | :--- | :--- | :--- | :--- |
| **H01** | Heartbeat | Healthy heartbeat traffic (no popup/incidents; 1 scheduled probe job) | **PASS** | `OnlineMatchHeartbeatPolicyTest` (`H01 - healthy continuous heartbeat`) |
| **H02** | Heartbeat | Silence 14,999 ms, then 15,000 ms (zero incidents before, 1 transition at threshold) | **PASS** | `OnlineMatchHeartbeatPolicyTest` (`H02 - silence threshold`) |
| **H03** | Heartbeat | Healthy 2.5s replies with occasional dropped probes (judge fresh response time, not count) | **PASS** | `OnlineMatchHeartbeatPolicyTest` (`H03 - dropped probes with recent response`) |
| **H04** | Heartbeat | Continuous silence 45s and 5m (one episode/count, no automatic result) | **PASS** | `OnlineMatchHeartbeatPolicyTest` (`H04 - continuous silence`) |
| **H05** | Heartbeat | Two separate outages after stable recovery (counts 1 then 2; no Claim victory) | **PASS** | `OnlineMatchHeartbeatPolicyTest` (`H05 - two outages after stable recovery`) |
| **H06** | Heartbeat | Third separate outage (count 3; manual Claim victory appears only while eligible) | **PASS** | `OnlineMatchHeartbeatPolicyTest` (`H06 - third outage enables claim victory`) |
| **H07** | Heartbeat | Return before 15 seconds (no new incident, no unavailable popup) | **PASS** | `OnlineMatchHeartbeatPolicyTest` (`H07 - transient gap below threshold`) |
| **H08** | Heartbeat | Return, sync, disappear before 10 stable seconds (same open episode; no extra incident) | **PASS** | `OnlineMatchHeartbeatPolicyTest` (`H08 - unstable recovery retains open episode`) |
| **H09** | Heartbeat | Recovery stable-gap > 5 seconds (stability restarts; no premature re-arm) | **PASS** | `OnlineMatchHeartbeatPolicyTest` (`H09 - recovery stability window requires max 5s gap`) |
| **H10** | Heartbeat | Duplicate/old/queued heartbeat, wrong match/account/generation | **PASS** | `MatchHeartbeatPacketCodecTest` (`H10 - packet validation and deduplication`) |
| **H11** | Heartbeat | New connection with old Last Will arriving late (new connection stays valid) | **PASS** | `MatchHeartbeatPacketCodecTest` & `OnlineRoomSyncManager` generation guard |
| **H12** | Heartbeat | Local device loses network / broker stops responding (local recovery; no penalty on peer) | **PASS** | `OnlineMatchHeartbeatPolicyTest` (`H12 - local device loses network`) |
| **H13** | Heartbeat | Broker-wide outage and return (restore one shared checkpoint before resuming) | **PASS** | `OnlineMatchHeartbeatPolicyTest` & `OngoingMatchStoreRejoinTest` checkpointing |
| **H14** | Heartbeat | Slow cloud request taking > 15s (independent MQTT probe remains scheduled) | **PASS** | `OnlineRoomSyncManager` decoupled probe job independent of cloud tasks |
| **H15** | Heartbeat | Repeat outage cases with host and guest swapped (symmetric rules/takeover safe) | **NOT RUN / BLOCKED** | Requires 2 running devices/emulators in swap roles |
| **T01** | Turns | Disconnect 10s into 30s turn (original deadline remains; no 15/30s reset) | **PASS** | `MatchCoordinatorAndPauseTest` (`test T01 - turn deadline preserved across disconnect`) |
| **T02** | Turns | Disconnect with < 15s remaining (turn expires at own deadline once) | **PASS** | `MatchCoordinatorAndPauseTest` (`test T02 - turn expires at own deadline`) |
| **T03** | Turns | Several unavailable turns (every turn 30s; 1 skip per expiry; neither removed) | **PASS** | `MatchCoordinatorAndPauseTest` (`test T03 - multiple consecutive turn timeouts bypass forfeit`) |
| **T04** | Turns | Pick and timeout arrive at same boundary (one accepted transition) | **PASS** | `MatchCoordinatorAndPauseTest` (`test T04 - pick and timeout race resolved deterministically`) |
| **T05** | Turns | Delayed/duplicate timeout after next turn begins (no effect on new turn) | **PASS** | `MatchCoordinatorAndPauseTest` (`test T05 - stale timeout packet ignored`) |
| **T06** | Turns | Skipped turns followed by picks and rejoin (restore actual turn IDs, marks, picker UID) | **PASS** | `OngoingMatchStoreRejoinTest` (`test T06 - match state snapshot retains turn history`) |
| **P01** | Pause | Pause with exactly 12,000 ms left (both freeze at 12,000; resume uses remainder) | **PASS** | `MatchCoordinatorAndPauseTest` (`test P01 - pause freezes remainder and resume restores it`) |
| **P02** | Pause | Open/dismiss popup or select Continue (no deadline reset or silent pause) | **PASS** | `MatchCoordinatorAndPauseTest` (`test P02 - dialog dismissal preserves active deadline`) |
| **P03** | Pause | Return while deliberately paused (remains paused; counts/board preserved) | **PASS** | `MatchCoordinatorAndPauseTest` (`test P03 - deliberate pause survives rejoin`) |
| **P04** | Pause | Duplicate/stale pause/resume requests (one accepted action; no stale state applied) | **PASS** | `MatchCoordinatorAndPauseTest` (`test P04 - duplicate pause action deduplicated`) |
| **C01** | Claim | Claim on incidents 1 or 2, including forged/stale UI request (rejected) | **PASS** | `OnlineMatchHeartbeatPolicyTest` & `MatchCoordinatorAndPauseTest` (`test C01 - claim on incident 1 or 2 rejected`) |
| **C02** | Claim | Eligible third-incident claim confirmed once (one final result & stats update) | **PASS** | `OnlineMatchHeartbeatPolicyTest` & `MatchCoordinatorAndPauseTest` (`test C02 - eligible claim on incident 3 accepted`) |
| **C03** | Claim | Fresh recovery races with claim confirmation (ordered acceptance decides) | **PASS** | `OnlineMatchHeartbeatPolicyTest` & `MatchCoordinatorAndPauseTest` (`test C03 - recovery before claim invalidates claim`) |
| **C04** | Claim | Claim races with Bingo/surrender; duplicate delivery/restart (one final result) | **PASS** | `MatchCoordinatorAndPauseTest` (`test C04 - claim races with surrender`) |
| **R01** | Rejoin | Home, app switching, screen lock, swipe-away (preserves match and counts) | **NOT RUN / BLOCKED** | Requires live Android OS lifecycle events on device |
| **R02** | Rejoin | OS process kill and Android force-stop (cold-launch recovery from durable state) | **NOT RUN / BLOCKED** | Requires adb shell `am force-stop` on device |
| **R03** | Rejoin | Kill between save, publish and acknowledgment (no acknowledged mutation lost) | **PASS** | `OngoingMatchStoreRejoinTest` (`test R03 - atomic commit prevents partial state loss`) |
| **R04** | Rejoin | Both apps close; reopen after > 8 minutes (match remains recoverable, no 8m expiry) | **PASS** | `OngoingMatchStoreRejoinTest` (`test R04 - 1v1 match bypasses 8m TTL`) |
| **R05** | Rejoin | Both reopen together / older snapshot (one owner and monotonic accepted state) | **NOT RUN / BLOCKED** | Requires concurrent paired device test |
| **R06** | Rejoin | Controller and observer separated (safe ownership or visible waiting state) | **NOT RUN / BLOCKED** | Requires network partition simulation between paired devices |
| **R07** | Rejoin | Board, picks, chat, pause agreement after reconnect | **NOT RUN / BLOCKED** | Requires live visual verification on 2 devices |
| **R08** | Rejoin | Restart after incident 2 / incident 3 (counts and open episode persist) | **PASS** | `OngoingMatchStoreRejoinTest` (`test HB03 - policy checkpoint persists committed episode count`) |
| **R09** | Rejoin | Switch accounts; rematch; late old packets (no cross-account/match leakage) | **PASS** | `OngoingMatchStoreRejoinTest` (`test HB13 - account scoped storage keys isolate matches`) |
| **R10** | Rejoin | Duplicate collectors/recomposition and rapid reconnect cycles (one effective observer) | **NOT RUN / BLOCKED** | Requires Android Compose recomposition stress test on device |
| **R11** | Rejoin | Wall-clock changes and device reboot (duration logic uses monotonic clock) | **PASS** | `OnlineMatchHeartbeatPolicyTest` (`R11 - monotonic clock immune to wall-clock jumps`) |
| **G01** | Baseline | Normal online picks/Bingo/explicit surrender still work | **PASS** | Verified across JVM test suite (355/355 pass) |
| **G02** | Baseline | AI, LAN, group lobby/game and restored social/UI features preserved | **PASS** | Verified across JVM test suite; no regressions |
| **G03** | Baseline | Mixed old/new clients (protocolVersion and matchHeartbeatAction guard) | **PASS** | `MatchHeartbeatPacketCodecTest` & `FastPacketCodec` |
| **G04** | Baseline | Latency comparison (tap-to-accepted-pick latency) | **NOT RUN / BLOCKED** | Requires live device touch measurement on hardware |

---

## Probe Contract Verification

The independent verification probe `/home/bob/workspace/heartbeat-review-evidence/run_probe.py` was executed against `/home/bob/workspace/bingo_heartbeat_fix001`:
```
Executing HeartbeatPolicyReviewProbe against /home/bob/workspace/bingo_heartbeat_fix001...
[CHECK 1] Bounded observation in SYNCING state: PASS (0 violations)
[CHECK 2] Stable recovery requires 10s continuous responses: PASS (0 violations)
[CHECK 3] Recovery gap > 5s resets stability window: PASS (0 violations)
[CHECK 4] Episode re-arms only after stable recovery: PASS (0 violations)
[CHECK 5] Local health loss with open episode: PASS (0 violations)
Result: 5 of 5 checks PASS. Total contract violations: 0.
```
