# Host-disconnection hold — change journal

Start: 100325391c23ab306f0c40b56e7cea30d5fb8f17.
Branch: fix/host-disconnected-wait.
User-approved temporary policy: host absence freezes guest gameplay behind a non-dismissible popup; synchronize and resume the exact saved state when host returns. Controller transfer/referee is deferred. Preserve baseline and previous APK/evidence.

## Implementation started

- Preserve 15-second verified heartbeat detection and existing guest-absence policy.
- Add persistent guest hold, correlated recovery and exact remainder reconciliation.
- Add blocking UI, tests and a separate candidate APK. No deployment or protected APK replacement.

## Implementation and review

- Added a guest-only blocking host popup using production DialogProperties with Back/outside dismissal disabled. Closed other pending guest dialogs; blocked existing root Back/exit paths during the hold.
- Added exact frozen guest checkpoint persistence and fixed-host system recovery holds. Recovery now requires full persisted SYNC, matching application ACK and correlated READY before guest unlock.
- Preserved manual pause; added accepted active-play duration so host-wait time is excluded. Zero-second expiry is held and processed once after successful recovery.
- Retained original guest-absence behavior and complete board/chat/result recovery. No authority transfer or new backend.
- First run: 398 tests, one prior startup assertion failed because the new policy intentionally keeps the guest locked until application ACK/READY. Updated that regression to delay a later recovery ACK while still testing owner advancement and duplicate handling.
- Targeted new tests initially found two immediate-persistence gaps in the host-wait marker. Persisted the frozen marker in the hold event, with retry on failure. Targeted rerun passed.
- Full intermediate verification: 414 tests pass, Android lint/release build succeeds. Final verification follows additional self-review regressions below.
- Review hardened cold start: unpublished local candidates reconcile against real broker truth; missing shared proof remains blocked; stale retained samples cannot undo a known accepted pause; an accepted final result overrides leftover wait metadata. Added regressions for each.
- Review hardened UI wiring: immediate blocking even if diagnostic writes fail, cancelled-start generation guards, serialized host installation identity, and local guest observation metadata preservation.
- Final screen review found the existing manual-pause dialog could cover the new host popup. Suppressed the manual-pause dialog while host-disconnected; retained its underlying pause state for recovery. The preceding build is intermediate; a new final build will include this UI correction.

## Final verification and delivery

- Final application commits: e3c1eb32368bb8ce21061842948922ef3a29825b and f74da6e34e9a4982b8009711760a153fe7f0b5c9. No application changes after the final build.
- Final full unit run: 419 tests / 40 suites, zero failures/errors/skips. Twenty-one additional production regressions; exact XML names indexed. Android lint: 0 errors/fatal, 34 warnings. Release build succeeds.
- Real MQTT final run: eight alternating picks, actual 15-second host silence, frozen guest at 14,678ms, recreated host recovery retaining board/picks/turn. Independent returning JVM recovers accepted final after writer exit; verification topic cleaned up. Third-incident result uses a prepared fixture. Median request-to-both-accepted 334ms / p95 405ms across eight headless samples, 50ms polling resolution; no physical baseline comparison.
- Signed APK: 7,063,659 bytes; SHA256 9db6b81303c28c6d1e66b55b0ffd9f47af758f0c343dd27ca7eb2e7fc2bb0eba. Certificate matches baseline.
- Reverified 159/159 baseline fingerprints and protected/shared/prior-candidate APK hashes unchanged. No push/merge/release/deployment.
- Android device interactions and lifecycle/physical latency remain NOT RUN: no connected device, /dev/kvm unavailable. The current report and handoff identify these limits; no device PASS is claimed.
