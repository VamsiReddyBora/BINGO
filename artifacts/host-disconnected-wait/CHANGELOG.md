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
