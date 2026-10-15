# Bingo heartbeat repair — current handoff

Updated **2026-10-15, Asia/Kolkata**. Active worktree: `/home/bob/workspace/bingo_heartbeat_codex`; local branch: `fix/heartbeat-codex`.

**NOT READY for the full intended behavior.** Independent code repairs are implemented, built and tested. The guest still waits when the original host is unavailable: guest gameplay, claim and confirmed surrender require an enforceable MQTT authority/ownership solution. Full Android paired-device and baseline latency verification also remain outstanding. Do not report “all fixed except hardware.”

## Read first

- [Repair and self-review](artifacts/heartbeat-codex/RepairReview.md): F001–F008, actual changes and remaining limits.
- [Verification report](artifacts/heartbeat-codex/TEST_REPORT.md): 398 tests, precise evidence layers, all original 44 + additional 35 planned cases.
- [Exact new test names](artifacts/heartbeat-codex/evidence/RegressionTestIndex.md): 36 newly added test methods.
- [Ownership/storage boundary](artifacts/heartbeat-codex/OWNERSHIP_AND_STORAGE.md): original single host; retained broker recovery; missing guest availability.
- [Review manifest](artifacts/heartbeat-codex/ReviewManifest.json), [journal](artifacts/heartbeat-codex/CHANGELOG.md), [source hashes](artifacts/heartbeat-codex/SOURCE_FINGERPRINTS.json).
- Original requirements/plans in `docs/`; latest independent review: `/home/bob/workspace/HeartbeatImplementationFix003Review.md`.

## Checkpoints

1. Protected restored baseline `a13d39edb0ff6a67d272cac187f1ed9fb294f9e9`, branch `1.4.1`, checkout `/home/bob/workspace/bingo_multiplayer`. All 159 fingerprinted files and protected APK unchanged. Tag `v1.4.1` is the older release; do not move it.
2. Starting Fix003 `600a2591da5efcf2135f3c8d3e1570aac059bac0`. Its “all code fixed” reports were inaccurate; historical files now carry superseded notices.
3. Application implementation `d4abaac26a06004b97b97a26fb936907ef782126`.
4. Additional heartbeat boundary tests `194b34b186f7d18af29b1d9e1dc9365593ae6930`. Application source is identical to the APK build checkpoint.
5. Later local evidence/handoff commit: see `git log fix/heartbeat-codex`; manifests intentionally avoid a self-referential hash.

## Actual implementation

`ReliableMatchCoordinator` accepts all online 1v1 actions through one serialized path. `ReliableMatchClient` runs the production protocol; RootNavGraph submits requests and renders accepted snapshots. All complete board layouts, real pick/turn identities, accepted chat, exact timing/pause, host-observed episodes and canonical final results are synchronized. Legacy gameplay packets/timers cannot mutate a new 1v1 match. AI/LAN/groups retain legacy paths.

The original host installation is the only writer; no takeover is inferred from silence. Local pending/accepted state is distinct. Actual retained QoS1 MQTT state is on `bingo/v4/match/{room}/{seed}/state`; requests use the room topic. Initial/final Android publications verify exact retained read-back. No per-move HTTP or new deployed backend. Statistics/history receipts are durable, atomic and account-bound. Ownerless legacy data is preserved without adoption; late old-match writes are rejected.

## Verified

- Final full unit run: **398 tests / 39 suites**, zero failures, errors or skips.
- Full application build/release/lint: success, **0 lint errors/fatal, 34 warnings**. It also ran 396 tests before the two test-only boundary additions.
- Real MQTT: actual production clients negotiate and accept eight alternating picks; independent returning JVM recovers a retained final after writer JVM exit. Test device stores are separate. Third episode is a prepared fixture. Unique test topics cleaned up.
- MQTT request-to-both-accepted metric: eight samples, median 503ms / p95 554ms, 50ms sampling resolution. This is not physical tap latency or a baseline comparison.
- Two existing AVDs attempted without KVM; no usable boot-complete screenshots. No candidate installed, no data wiped, no invitations sent. Android gameplay **NOT RUN**. Both launched VMs stopped.

## Build and artifacts

Candidate: `artifacts/heartbeat-codex/Bingo-codex.apk`, **7,055,459 bytes**.

SHA256: `262ef4c547941e975a854c5c17825e6495c0fa281aad983a9055afd5443b8e8c`.

Verified signing certificate SHA256: `39a428942c3c2629ff3490c944b8b82c7625b22d660b7a2cdf682456d81f60a4`, matching baseline.

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease :app:exportCodexVerificationClasspath \
  -I artifacts/heartbeat-codex/verify-classpath.init.gradle \
  --rerun-tasks --offline --console=plain --max-workers=2
python3 artifacts/heartbeat-codex/run_live_mqtt.py
```

Preserve existing live-probe evidence before rerunning. SDK configured locally in ignored `local.properties`. Do not expose broker credentials, Firebase secrets or signing configuration.

## Continue safely

The user asked to fix all findings. Continue that objective when the missing authority/hosting configuration is supplied. A separate MQTT authority must have one enforceable writer and durable state; inventing another app-side “host” or a retained owner flag does not close the gap. User was asked where it could run, and asked for an explanation; explained Kevin-host-offline/Stuart-waiting. No hosting location has been supplied yet.

Complete the 79-case/device matrix, especially both roles, app lifecycle/network faults, timer labels, manual board/chat preservation, result UI, statistics and the baseline latency comparison. Do not infer device PASS from unit tests or screen previews. Retention quota/acknowledged-final cleanup also needs a rollout policy.

No push, merge, release or deployment was performed. Never overwrite `/home/bob/workspace/bingo_multiplayer/Bingo.apk` or shared `/home/bob/workspace/Bingo.apk`; their hashes are in the manifest. Roll back through a separate checkout at a preserved commit; retain new-schema saves and evidence rather than resetting/deleting user data.
