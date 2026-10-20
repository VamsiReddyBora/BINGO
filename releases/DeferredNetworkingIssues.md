# Deferred networking work after Round 11

Gameplay-impacting findings identified in the Round 10 takeover were repaired before this release. The following work is intentionally deferred; test-evidence gaps are not additional reproduced gameplay bugs.

## D01 — P3: coalesce durable closure retry jobs

Location: `app/src/main/java/com/bingo/multiplayer/domain/network/OnlineRoomRegistry.kt`, `queueDurableClosureRetry` and callers in `mutateCloudSession`/`updateRoomStatus`.

Repeated requests for the same pending closure can start multiple retry coroutines. The conditional metadata backend and expected-seed checks protect stored match state, but duplicate requests consume unnecessary network and battery resources during outages.

Future fix: keep one cancellable Job per room and generation; reuse an existing job for the same entry; cancel/replace it only when the intended generation changes. Remove jobs/entries using identity comparisons so an old worker cannot clear a newer intent. Preserve the durable outbox and restart behavior. Test repeated queue calls, generation changes, cancellation and restart restoration through the production scheduler.

## D02 — verification gap: full Android lifecycle and UI execution

The current pass runs native release unit tests, production HTTP/coordinator components, worker tests and backend smoke checks. It does not execute Compose screens, Android task removal, real WorkManager scheduling, OS notification delivery, or real-device MQTT TLS/SUBACK timing.

Future verification: install the same updated APK on both phones and run normal Online/Nearby starts, manual-board setup, interruption/rejoin, rematch, explicit surrender, offline Bingo, pause and five-minute recovery. Include delayed start/ACK packets, invalid host controls, cold restore and countdown boundaries. Capture both screens and logs. Preserve all passing regression cases.

## D03 — verification gap: older evidence and regression mappings

Earlier reports contain helper-only or copied-expression tests for some ACK, ingress, screen rendering and timing cases. Those assertions must not be described as execution of the complete app callback. The new metadata race is covered by actual native HTTP/coordinator code and the actual worker implementation; that does not retroactively verify every older UI claim.

Future work: replace unsupported proof claims with exact production callback/instrumentation mappings and executed affected-version/fixed-version logs. Keep valid existing tests and assertions. A higher test total alone does not establish complete lifecycle correctness.

## D04 — maintenance: warning cleanup and legacy path retirement

Existing compiler warnings and older room-writer compatibility paths remain. Clean them up separately once account migration and compatibility requirements are explicitly checked. Do not remove functions merely because their name looks old. Update every room consumer before retiring legacy discovery; mixed old/new clients do not receive a new-protocol concurrency guarantee.

Do not change SimplifiedGameRules.md, gameplay flow, winner logic, turn budgets, pause controls or social features while handling these follow-ups. Do not restart the old automatic handoff without an explicit user instruction.
