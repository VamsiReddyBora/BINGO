# Fix003 Review Resolution Report

**Overall Status**: **NOT READY FOR PRODUCTION DEPLOYMENT** (Fix003 in progress).

---

## 1. Checkpoint & Worktree Information

- **Protected Baseline**: `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9` (branch `1.4.1`)
- **Protected Baseline APK**: `/home/bob/workspace/bingo_multiplayer/Bingo.apk` (SHA-256 `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc`)
- **Preserved Fix002 Candidate**: `326231022f1bdf305e715b31f9c6625c55fca61b` (branch `fix/heartbeat-fix002`)
- **Fix003 Repair Branch**: `fix/heartbeat-fix003`
- **Fix003 Repair Worktree**: `/home/bob/workspace/bingo_heartbeat_fix003`
- **Starting HEAD**: `326231022f1bdf305e715b31f9c6625c55fca61b`

---

## 2. F001–F008 Finding Status Matrix

| Finding | Priority | Fix002 Re-review Status | Fix003 Target Status | Core Symbols & Locations |
| :--- | :--- | :--- | :--- | :--- |
| **F001** | P1 | PARTIAL — no shared deadline / enforced commit order | PENDING | `RootNavGraph.kt`: turn timers, monotonic projection |
| **F002** | P1 | OPEN — no independent-phone recovery; failed-commit cache bug | PENDING | `OngoingMatchStore.kt`: `recordMatchResultTombstone`, durable file/network adapter |
| **F003** | P1 | PARTIAL — episode eligibility/accepted commit unverified | PENDING | `RootNavGraph.kt`, `LobbyLifecycleEngine.kt`: seed validation |
| **F004** | P1 | PARTIAL — partial snapshots unlock; running time/pause incomplete | PENDING | `RootNavGraph.kt`: `isReconcilingMatch`, complete snapshot gating |
| **F005** | P1 | PARTIAL — live unscoped access and all-account deletion remain | PENDING | `OngoingMatchStore.kt`: unscoped get/clear removal |
| **F006** | P1 | REGRESSION — repeated sync prevents stable recovery and episode counting | PENDING | `OnlineMatchHeartbeatPolicy.kt`: `onStateSynchronized` |
| **F007** | P2 | PARTIAL — history reset/wire coverage, exact time incomplete | PENDING | `RootNavGraph.kt`, `FastPacketCodec.kt`: timeout `turnIds` encoding |
| **F008** | P2 | PARTIAL — code-fixed claims unsupported; missing test mappings | PENDING | `TEST_REPORT.md`, `Fix003ReviewResolution.md` |

---

## 3. Mandatory N01–N35 Regression Register

| ID | Finding / Scenario | Status | Evidence / Notes |
| :--- | :--- | :--- | :--- |
| **N01** | Recovery with full/partial sync and ACKs every 2.5s for 20s | PENDING | Pending Chunk 1 |
| **N02** | New silence after N01, then another complete recovery/outage (episodes 1 -> 2) | PENDING | Pending Chunk 1 |
| **N03** | Unscoped production lookup with A/B saved returns null | PENDING | Pending Chunk 2 |
| **N04** | Normal unscoped cleanup with A/B saved preserves both accounts | PENDING | Pending Chunk 2 |
| **N05** | Required result commit returns false; no accepted result published/cached | PENDING | Pending Chunk 2 |
| **N06** | Repeat N05 with identical result and all writes failing; retry remains failure | PENDING | Pending Chunk 2 |
| **N07** | B absent; A accepts result and exits; B starts separately and gets result | PENDING | Pending Chunk 3 |
| **N08** | Resume attempt after N07 shows final result; unfinished match cannot restart | PENDING | Pending Chunk 3 |
| **N09** | Encode/decode timeout carrying picks at turns [1,3] preserves turnIds | PENDING | Pending Chunk 4 |
| **N10** | Missing/zero seed in active nonzero match rejected | PENDING | Pending Chunk 4 |
| **N11** | Silence at 14,999ms and 15,000ms; continuous absence 45s and 5m | PENDING | Covered in `OnlineMatchHeartbeatPolicyTest` |
| **N12** | Late sync at 26s, ACKs and duplicate sync through 36s | PENDING | Pending Chunk 1 |
| **N13** | Stable gaps 5,000ms versus 5,001ms boundary follows policy | PENDING | Covered in `OnlineMatchHeartbeatPolicyTest` |
| **N14** | Wrong/old connection or recovery ACK; delayed Last Will ignored | PENDING | Covered in `MatchHeartbeatPacketCodecTest` |
| **N15** | Disconnect 10s into a 30s turn; deadline preserved without 2s grace | PENDING | Pending Chunk 5 |
| **N16** | Duplicate expiry and late old-turn expiry: one turn advance | PENDING | Pending Chunk 5 |
| **N17** | Pick races timeout: one ordered accepted history | PENDING | Pending Chunk 5 |
| **N18** | Host and guest connectivity loss; safe waiting state | PENDING | Pending Chunk 5 |
| **N19** | Both apps close >8min and return together: same checkpoint | PENDING | Covered in `OngoingMatchStoreRejoinTest` |
| **N20** | Rejoin with authoritative 12,000ms running remainder preserved | PENDING | Pending Chunk 5 |
| **N21** | Pause at 14,250ms preserved; resume and next turn is 30,000ms | PENDING | Pending Chunk 5 |
| **N22** | Remote pause/resume accepted, then immediate process kill: restores correctly | PENDING | Pending Chunk 5 |
| **N23** | Saved pause true, current snapshot false clears pause | PENDING | Pending Chunk 5 |
| **N24** | Missing local/manual board remains in waiting state | PENDING | Pending Chunk 5 |
| **N25** | Correlated retry on delayed subscription; partial snapshot cannot unlock | PENDING | Pending Chunk 5 |
| **N26** | Exit while recovery pending; start new game resets gate | PENDING | Pending Chunk 5 |
| **N27** | Match 1 history cleared on Match 2 start | PENDING | Pending Chunk 5 |
| **N28** | A/B account switching; startup cleanup cannot touch other account | PENDING | Pending Chunk 2 |
| **N29** | Claim on incident 1/2 or invalid episode rejected | PENDING | Covered in `MatchCoordinatorAndPauseTest` |
| **N30** | Fresh response races eligible claim confirmation | PENDING | Covered in `MatchCoordinatorAndPauseTest` |
| **N31** | Claim/Bingo/surrender race: one canonical final result | PENDING | Covered in `MatchCoordinatorAndPauseTest` |
| **N32** | New/old policy capability combinations explicit decision | PENDING | Pending Chunk 4 |
| **N33** | Concurrent storage writes and fault interleave | PENDING | Pending Chunk 2 |
| **N34** | Rapid reconnect cycles: one effective observer | PENDING | BLOCKED (requires paired device execution) |
| **N35** | Baseline/candidate online pick latency measurement | PENDING | BLOCKED (requires physical hardware touch) |
