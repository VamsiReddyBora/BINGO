# Codex heartbeat repair journal

Starting checkpoint: 600a2591da5efcf2135f3c8d3e1570aac059bac0.
Protected baseline: a13d39edb0ff6a67d272cac187f1ed9fb294f9e9.
All changes are isolated on fix/heartbeat-codex. No deployment or protected APK replacement.

## 2026-10-15 — Controller and storage repair started

- Replace overlapping online 1v1 mutations with a production serialized host coordinator and versioned MQTT requests/full retained snapshots.
- Remove the local-file “shared” result adapter. Use actual retained broker snapshots, application correlation and confirmed publication.
- Preserve exact timer remainder and account ownership. Test the real coordinator, wire and fault paths.
- Known infrastructure boundary: no automatic authority transfer is safe on the existing serverless broker. Host absence must wait; full guest continuation/claim during host absence remains blocked pending a fenced MQTT authority. Do not report this as resolved availability.
- Rollback: return to starting checkpoint in a separate checkout, preserving new-schema checkpoints and evidence.

## 2026-10-15 — Internal implementation and verification

- Added ReliableMatchCoordinator and ReliableMatchClient; wired all online 1v1 actions, snapshots and timers into them. Legacy AI/LAN/group paths remain separate.
- Added exact deadline/remainder tests, real packet serialization, independent client checkpoint stores, publication/disk faults, replay checks, full chat/board recovery, account and result-receipt tests.
- Early full-suite run: 372 tests, one legacy revision-expectation failure. Fixed compatibility by keeping legacy local revision increments while v2 accepted revisions remain controller-owned.
- Subsequent full-suite checkpoint: 385 tests, zero failures. More race/stress cases were then added; final execution will be recorded separately.
- Real MQTT broker checkpoint passed: negotiated clients, accepted pick, persisted final, writer process exit, independent reader process final recovery. No shared filesystem or deployed backend. Unique verification retained topic deleted afterward.
- Self-review repairs: same-sequence conflicting snapshots rejected; correlated timing-only refresh handled; cancelled-session storage CAS prevents overwriting a rematch; atomic result receipts bind to expected account; handshake ACK validation binds to the exact issued snapshot rather than a subsequently advanced turn.
- Started both existing AVDs without KVM, without wiping data or saving snapshots. ADB/package services responded; boot-complete/screenshots did not become usable. No candidate installation or Android gameplay validation is claimed. Both launched VMs were shut down.
- A duplicate verification build was accidentally launched. Cancelled that duplicate and will rerun final checks sequentially; intermediate mixed build logs/APKs are not final evidence.

## 2026-10-15 — Final self-review corrections

- Added compatibility coverage for the baseline's 10×10 board option; no new size restriction.
- Lobby/start/rematch packets now advertise explicit capability; compact heartbeat encoding preserves it; old packets default unsupported. Runtime HELLO/SYNC still gates activation.
- Recovery normalizes inconsistent saved board data into a waiting preview rather than throwing or generating a new board.
- Bound synchronization ACKs to the exact issued snapshot and kept duplicate challenges stable while the owner advances; added a delayed-ACK regression with no retry-period stall.
- Observation checkpoints now run off the UI thread, require the expected account/match, retry failed writes before exposing actions, and hide opponent judgement when local observation is unavailable.
- Strengthened the preference-failure test to model Android updating memory despite disk failure. It reproduced a rollback/history bug; preserved pre-edit history and restored touched fields/receipt on failure. The targeted fault test then passed.
- Retained publication verification now checks initial/final storage through flagged retained read-back, beyond PUBACK/live self echo.
- Final fresh verification is recorded in TEST_REPORT.md and the evidence directory. Historical intermediate failing logs are retained; they are not final acceptance results.

## 2026-10-15 — Delivery checkpoint and review

- Application implementation committed locally as d4abaac26a06004b97b97a26fb936907ef782126. Additional heartbeat-only regression commit: 194b34b186f7d18af29b1d9e1dc9365593ae6930.
- Final full application verification passed: test/lint/release build, 0 lint errors/fatal and 34 warnings. Added exact repeated-sync and 5,000/5,001ms heartbeat-gap tests afterward; final fresh full unit run passed 398 tests across 39 suites with no failures/errors/skips. Application sources remain identical to the signed build.
- Real MQTT probe passed eight alternating picks and independent-process retained final recovery after writer exit; test topics cleaned up. Median request-to-both-accepted 503ms / p95 554ms, eight headless samples with 50ms resolution. No physical baseline comparison claimed.
- Self-review and all 79 planned-case evidence mappings recorded. Historical Fix003 reports marked superseded and old handoff preserved. Guest availability during original-host absence remains explicitly BLOCKED; paired Android UI remains NOT RUN.
- APK built separately, signature verified, SHA256 262ef4c547941e975a854c5c17825e6495c0fa281aad983a9055afd5443b8e8c. Baseline 159/159 fingerprints and both protected/shared APK hashes reverified unchanged. No push/merge/deployment.
