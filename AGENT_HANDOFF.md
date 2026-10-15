# Bingo — temporary host-disconnection hold

Updated **2026-10-15T18:49:35+05:30**. Current worktree `/home/bob/workspace/bingo_host_wait`, branch `fix/host-disconnected-wait`.

The user explicitly deferred control transfer/referee and approved this temporary policy: when Kevin (fixed host/controller) is unreachable, freeze Stuart's game behind a non-dismissible **Host Disconnected** popup until complete recovery. Do not implement the earlier uninterrupted guest-play proposal without a new user plan.

Application commits: `e3c1eb32368bb8ce21061842948922ef3a29825b` and `f74da6e34e9a4982b8009711760a153fe7f0b5c9`. Starting checkpoint `100325391c23ab306f0c40b56e7cea30d5fb8f17` remains intact in `/home/bob/workspace/bingo_heartbeat_codex`.

Read [implementation](artifacts/host-disconnected-wait/IMPLEMENTATION.md), [test/review report](artifacts/host-disconnected-wait/TEST_REPORT.md), [journal](artifacts/host-disconnected-wait/CHANGELOG.md), [manifest](artifacts/host-disconnected-wait/ReviewManifest.json), and [exact new tests](artifacts/host-disconnected-wait/evidence/RegressionTestIndex.md).

- Final unit run: **419 tests / 40 suites**, 0 failures/errors/skips; 21 additional tests.
- Lint: 0 errors/fatal, 34 warnings; signed release build passed.
- Live MQTT: 8 alternating picks, actual 15-second silence, blocked/frozen guest, recreated host recovery, and separate-JVM final-result recovery pass. Stores are independent; verification topics cleaned up. Third-incident terminal claim is a prepared fixture.
- Dialog's actual dismissal properties/message are tested. Android taps, Back, lifecycle/force-stop, dialog layering and physical latency remain **NOT RUN** because no device is connected and KVM is unavailable. Do not call these device tests passed.
- Both phones require this APK (explicit lobby capability 3 and hostWaitVersion=1 runtime negotiation). AI/LAN/groups retain their existing paths.
- Active duration and turn timing freeze; manual pause survives recovery; zero expires once after recovery. Host returning alone observes 15 seconds before continuing the preserved match under existing guest-absence rules. No offline timeout backlog.
- No controller transfer, new backend, broker-rule change or per-move HTTP. Existing original-single-installation/honest-client limits remain.

APK: `artifacts/host-disconnected-wait/Bingo-host-wait.apk`, 7,063,659 bytes, SHA256 `9db6b81303c28c6d1e66b55b0ffd9f47af758f0c343dd27ca7eb2e7fc2bb0eba`. Signing certificate SHA256 `39a428942c3c2629ff3490c944b8b82c7625b22d660b7a2cdf682456d81f60a4` matches baseline. Build/verify commands are in the test report. SDK configuration is local/ignored; do not expose credentials.

Protected restored baseline: branch 1.4.1, commit a13d39edb0ff6a67d272cac187f1ed9fb294f9e9, checkout `/home/bob/workspace/bingo_multiplayer`. All 159 fingerprints and protected/shared/prior-candidate APKs unchanged. No push/merge/release/deployment. Preserve ongoing-save data and evidence when checking out rollback commits separately.

The prior full-disconnection plans/reviews are historical context. Current delivery scope is this temporary host hold. Remaining work: execute paired Android validation and a physical baseline/candidate latency comparison; record both screens and accepted turn/remainder. Never infer Android success from pure/component tests.
