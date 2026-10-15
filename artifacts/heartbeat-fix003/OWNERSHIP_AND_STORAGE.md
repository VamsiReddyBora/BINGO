> **Historical Fix003 report — superseded.** These claims describe commit `600a259`, not the current repair. The independent review reproduced remaining defects. Use [Codex repair review](../heartbeat-codex/RepairReview.md) and [current test report](../heartbeat-codex/TEST_REPORT.md). Do not treat this historical document as current PASS/readiness evidence.

# Ownership and Storage Capability Assessment (Fix003)

**Prepared**: 2026-10-15 (Asia/Kolkata)  
**Branch**: `fix/heartbeat-fix003`  
**Repository**: `VamsiReddyBora/BINGO`  

---

## 1. Executive Summary

This document evaluates the distributed ownership and storage capabilities of the Bingo application architecture in accordance with `HeartbeatImplementationPlanFix003.md`.

| Guarantee Area | Target Guarantee | Existing Underlying Infrastructure | Capability Assessment | Implemented Mechanism & Safety Boundary |
| :--- | :--- | :--- | :--- | :--- |
| **Control Ownership & Takeover** | Safe control transfer while host is absent without dual-master split-brain | EMQX Serverless MQTT (`p0812f88.ala.asia-southeast1.emqxsl.com:8883`) | **NO ATOMIC LOCKING / CAS** on broker | Sequencer ownership is strictly pinned to host epoch. When host is disconnected, guest enters **visible waiting state**; timer and mutations are suspended. Unilateral guest takeover without consensus is **BLOCKED**. |
| **Final Result Recovery** | Concluded match result recoverable by absent peer after winner exits | MQTT Retained Topics (`bingo/v3/match_result/{seed}`) + Local Preferences | **SUPPORTED VIA RETAINED TOPICS & LOCAL STORAGE** | Result is published with `isRetained = true` to MQTT and committed to local SharedPreferences. Absent peer checks retained topic upon reconnect. For headless JVM testing, a persistent shared file adapter bridges separate processes without in-memory static caching. |
| **Account Isolation** | Scoped storage isolation preventing account leakage | Android `SharedPreferences` XML files | **SUPPORTED** | Strict account UID scoping (`active_match_data_{uid}`). Global mirror writes removed. Unscoped fallback and prefix-wide deletion eliminated. |
| **Atomic Durability** | Crash-safe atomic state commits | `SharedPreferences.Editor.commit()` | **SUPPORTED** | Synchronous background `.commit()`. In-memory cache is only populated AFTER disk commit returns `true`. Failed writes fail closed. |

---

## 2. Infrastructure Capability Audit

### 2.1 MQTT Broker (`NetworkConfig.BROKER_URL`)
- **Endpoint**: `ssl://p0812f88.ala.asia-southeast1.emqxsl.com:8883`
- **Protocol**: MQTT v3.1.1 over TLS (Paho client).
- **Guarantees**:
  - Message delivery with QoS 1 (`waitForCompletion()`).
  - Retained messages (`isRetained = true`): broker stores the last message on a topic and delivers it immediately to any new subscriber.
  - Last Will and Testament (LWT): publishes disconnect notice if socket terminates abruptly.
- **Limitations**:
  - Broker does **not** provide distributed locking, lease fencing, or atomic Compare-And-Swap (CAS).
  - Two clients publishing to the same topic results in last-writer-wins.

### 2.2 Cloud KeyValue Storage (`NetworkConfig.KEYVALUE_API_URL`)
- **Endpoint**: `https://keyvalue.immanuel.co/api/KeyVal`
- **Capabilities**: Simple REST key-value store (`GetValue`, `UpdateValue`). Query parameters limited to 1024 bytes.
- **Limitations**:
  - Non-transactional; separate HTTP GET and PUT without optimistic concurrency control.
  - Unsuitable for fast turn-by-turn gameplay synchronization.

### 2.3 Local Storage (`OngoingMatchStore`)
- **Mechanism**: Android `SharedPreferences` (XML persistence per file).
- **Guarantees**:
  - `.commit()` writes synchronously to disk on an I/O dispatcher.
  - Multi-process mode (`MODE_MULTI_PROCESS`) is deprecated by Android and does not guarantee cross-process atomicity.
  - For headless JVM testing across distinct JVM processes, a file-backed shared store or adapter provides durable cross-process verification without relying on process-shared static memory.

---

## 3. Ownership & Takeover Safety Design (F001)

1. **Host as Sequencer**:
   - The match host serves as the authoritative turn sequencer.
   - Turns are bound to monotonic deadline projection (`remainingTurnMillis`).
2. **Absence & Safety Wait**:
   - If local transport disconnects, turn progression is suspended on that device.
   - If peer is silent for > 15,000ms, "Opponent not responding" dialog appears.
   - If host disconnects, the match cannot unilaterally promote the guest to authoritative sequencer without broker-backed consensus; doing so risks dual-master state upon host reconnect.
   - Therefore, guest waits safely; if host does not return, guest may choose **Claim Victory** on episode 3+ or explicit surrender.

---

## 4. Final Result Persistence & Recovery Design (F002)

1. **Immutability & Canonical Identity**:
   - Result tombstone contains: `matchSeed`, `roomCode`, `winnerUid`, `reason`, `claimEpisodeCount`, `claimEpisodeId`.
   - Conflicting writes return `false` and are strictly rejected. Identical writes return `true` (idempotent).
2. **Durability First**:
   - In `OngoingMatchStore`, the disk write via `.commit()` occurs **before** any in-memory cache update.
   - If disk commit returns `false`, no cache entry is created, no finished state is published, and subsequent retries are not falsely acknowledged as successes.
3. **Cross-Process Recovery**:
   - In production, winner publishes retained MQTT packet to `bingo/v3/match_result/{seed}` before exiting.
   - Returning absent peer subscribes to `bingo/v3/match_result/{seed}` to retrieve the final result.
   - In JVM unit/probe testing with separate JVM processes (`Fix002SeparateProcessProbe`), a file-backed persistence layer bridges separate JVM instances so absent peer retrieves the tombstone without sharing process memory.
