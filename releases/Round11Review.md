# Round 11 — networking takeover review and fixes

Reviewed: 2026-10-20T18:56:41+05:30. Source: the existing local `bingo_multiplayer` checkout, including Antigravity's unfinished Round 10 repair pass. No branch replacement or blanket reset was performed. The user replaced the automatic handoff with a manual review, gameplay-impacting repairs, deferred-work report, APK build and publication.

## Result

Four actionable recovery/metadata findings were confirmed in the submitted repair pass and addressed. One background-resource finding is deferred below. Missing execution evidence is listed separately; it is not counted as an observed gameplay failure. Final test/build/publication results are recorded in the validation section after completion.

The user explicitly chose to fix the cross-device metadata race before publishing. Room metadata now has an authenticated, per-room conditional commit in the existing backend. MQTT continues carrying picks, accepted gameplay events, snapshots and presence. The backend does not choose numbers, advance turns, award victories or run match clocks.

## Round 11 findings and resolutions

| ID | Priority | Confirmed problem | Resolution and production path |
|---|---|---|---|
| R11-F01 | P1 | During a read outage, an obsolete closure was queued before its seed was checked. A pending closure for A then hid cached rematch B. | Reject the obsolete seed before queuing; scope pending-closure guards to their match seed. Retry workers use their actual entry's seed and remove only their own entry. `OnlineRoomRegistry.mutateCloudSession`, `hasPendingClosure`, `queueDurableClosureRetry`, `getRoom`, `getRoomCachedOnly`. |
| R11-F02 | P1 | Retained delivery was treated as proof that manual preparation had finished. Fresh manual matches could bypass design, while running recovery had no explicit persisted preparation phase. | Persist explicit preparing/running state. Use one production routing decision for packet and code-based entry. On actual manual start, persist accepted boards and original turn clock in canonical metadata. Unknown old recovery remains fenced. `LobbyLifecycleEngine.resolveManualPacketIngressTarget`, `RootNavGraph` start/manual/rejoin/rematch paths. |
| R11-F03 | P2 | A stored packet could pass canonical recovery validation with the wrong packet type or sender despite matching its claimed player ID. | Require START_GAME, matching room/seed, current administrative host and sender. Otherwise construct recovery from the validated session, retaining its clock. `OnlineRoomSyncManager.validateAndBuildStartPacket`. |
| R11-F04 | P2 | Same numeric revision keys and read-before-write checks could not order independent devices. A delayed lobby update could replace a committed start or rematch. | Per-room Durable Object conditional commits compare revision and exact seed before persisting. Conflicts retry the transform against the committed record. Legacy records are read until authenticated adoption, then cannot overwrite canonical state. Native discovery and retained-start recovery use canonical records. Unavailable conditional commits do not fall through to unconditional writes. `RoomMetadataStore.kt`, `OnlineRoomRegistry`, `OnlineRoomSyncManager`, `cloudflare_worker/rooms.js`, worker/inbox routing. |
| R11-F05 | P3 — deferred | Repeated calls can launch multiple closure retry coroutines for the same room. Conditional storage prevents stale commits, but the duplicate workers waste HTTP requests during outages. | Coalesce jobs by room and generation in a future resource-hardening change. See `DeferredNetworkingIssues.md`. |

Additional protections included with R11-F04: preserve exact signed 64-bit match IDs as opaque payload text; authenticate mutations using existing notification sessions; reject new participants being added to a running match; confirm a start/rematch was persisted before local dispatch; refresh invalid authorization once; reconcile a lost commit reply; avoid treating service outages as missing rooms or repeating long lookup waits. No test assertion was weakened to accept a broken behavior.

## Recheck of the seven Round 10 findings

| Round 10 finding | Result in this pass |
|---|---|
| F01 — cross-device writes | The prior revision workaround was insufficient; replaced by the conditional backend protocol and controlled concurrency tests. |
| F02 — cache-only closure | Fresh-evidence deferral was present. Its obsolete-seed/pending-closure regression was reproduced and repaired as R11-F01. |
| F03 — privileged controls | Inspected host/sender gates before the relevant Online/Nearby transport and navigation effects. Agent repairs retained. Broader real-device ingress evidence remains deferred. |
| F04 — manual recovery | The prior retained-delivery heuristic remained unsafe; explicit preparation state and shared routing now replace it. |
| F05 — invitation readiness | Agent's transient-failure peer-dispatch suppression retained. Notification authorization now reads canonical room metadata, avoiding stale legacy mirrors. |
| F06 — captured collectors | Agent's `rememberUpdatedState` handler and live transport roster lookup retained. |
| F07 — former-host canonical packet | Agent's shared HTTP/MQTT builder retained and its packet-type/sender validation tightened as R11-F03. |

## Permanent regression evidence

- `ReleaseRecoveryRegressionTest`: production obsolete-close/cache/read paths; canonical packet type/sender; fresh/running/unknown manual routing; accepted manual metadata boards/clock; canonical service outage classification.
- `RoomMetadataStoreTest`: independent native HTTP clients with a held write after its read; complete start payload preservation; held join versus closure; obsolete close versus rematch; unavailable storage; actual HTTP cancellation; lost write acknowledgement reconciliation.
- `cloudflare_worker/test/rooms.test.js`: the real worker route and per-room serial storage implementation, including conflicting commits, rematch seed rejection, terminal closure, exact 64-bit legacy adoption, unauthenticated writes and forged actors.
- Existing baseline and later regressions are retained. The legitimate-closure fixture now supplies fresh matching MQTT evidence; its original closure assertions remain intact.

Executed before proof: `failing-before-tests.log` records both reported production tests failing on the submitted source. New packet-type/routing/conditional-protocol cases have source-before review plus passing-after production-component tests; no executable failing-before run is claimed for those new tests. Root Compose navigation and real Android lifecycle events were source-reviewed rather than executed on devices.

## Rules and deployment

`SimplifiedGameRules.md` remains unchanged: SHA-256 `204fb52d6156257e982cfb51620e0e618f8b8cf5f415cd03ab127870d78e318d`. Existing 30-second/5-second budgets, frozen remaining-time recovery, five-minute inactivity, explicit exits, manual pause and Bingo eligibility remain the governing rules.

Backend deployed: `bingo-notifications-v2`, version `3fe80719-3e7b-4079-9a9f-81e4daf67aee`. Uses the existing Durable Object binding with separate room instance names; existing notification-directory data is preserved. Health reports room metadata protocol 3. Production smoke checks are read-only and check unauthenticated rejection; no live player's room was mutated for testing.

All participants should install the updated APK for the new metadata protocol. Online lobby creation/start/rejoin require metadata-service availability; active picks and turn processing do not make an additional per-pick backend request. No physical-device latency claim is made.

Rollback references: local source snapshot and binary backups in `/home/bob/workspace/bingo-round11-takeover-20261020-181058`; original main commit `11ce9522353b0838368fdee90c98d6799b119edd`. Preserve canonical room records when planning a rollback; do not blanket-reset the checkout or blindly revert backend storage semantics.

## Final validation

- `./gradlew :app:testReleaseUnitTest :app:assembleRelease --rerun --console=plain`: **BUILD SUCCESSFUL**. **764 passed, 0 failed, 1 skipped** (existing opt-in live MQTT test).
- All **746 required baseline cases** and all **753 Round 10 passing cases** still pass. No required case is missing/skipped.
- `npm test --prefix cloudflare_worker`: **61 passed, 0 failed**, including all original 54 notification tests and seven backend metadata cases.
- `apksigner verify --print-certs`: verified; signer certificate matches the previous APK.
- Package `com.bingo.multiplayer`, version name **1.0**, version code **46**. APK size **7,310,068 bytes**.
- APK SHA-256: `2645eb3094be953096567e39ed5df0fe6bd927c5df62e74ec298367295a592c6`.
- Live backend: health confirms metadata protocol 3; absent reserved-code lookup returns an empty record; unauthenticated mutation returns 401. An initial transient non-JSON network response was retried successfully; this is not a measured latency test.
- `git diff --check`: passed. Rules hash unchanged. Existing compiler warnings remain; no claim of warning-free compilation is made.
- Fresh evidence: `/home/bob/workspace/bingo-round11-takeover-20261020-181058/final-verified-build.log`, `final-unit-test-results.json`, `baseline-validation.json`, `notification-and-metadata-tests.log`, `apk-verification.json`, `deployed-backend-smoke.json`.

Post-fix source review rechecked the four repaired causes, their production wiring, original Round 10 repairs and new backend/client conflict paths. The P3 retry-job duplication and listed execution gaps remain deferred. This is scoped code/component verification, not a promise of zero possible bugs or completed physical-device testing.
