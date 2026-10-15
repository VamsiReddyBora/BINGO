# Bingo — friends repair and host-wait baseline

Updated 2026-10-15T19:27:35+05:30. Current worktree `/home/bob/workspace/bingo_friends_fix`, branch `fix/friends-list-requests`, application commit `bee8a3b6e0454b8682dd1a21a0a275c4e2d00450`.

Latest task: fix Bob's one-friend list and repeated accepted Kevin request toast; deliver a signed APK. Read [repair report](artifacts/friends-fix/TEST_REPORT.md), [journal](artifacts/friends-fix/CHANGELOG.md), [manifest](artifacts/friends-fix/ReviewManifest.json), and production source fingerprints.

- Read-only diagnosis: primary roster 1, backup 4, directory missing exactly 3, pending requests 0. No real account data changed by tools.
- Reconcile from existing Firestore + cache + primary, without directory deletion. Keep failed reads distinct from empty lists; compact oversized roster payloads; scope state/receipts by account. Reject retained/duplicate/resolved/existing-friend notifications. Preserve explicit removals and recognize a later acceptance using unique removal IDs.
- Validation: 454/454 tests, 42 suites, 35 new regressions; lint 0 errors/fatal and 36 warnings; release/signature verified. Real device checks NOT RUN because no device is connected. See report for exact limits and follow-up checks.
- APK: `artifacts/friends-fix/Bingo-friends-fix.apk`, SHA256 `14bec2f264ef8de5e6522d499f34ff32fae3207d1a6fdb244cff91824b8b21ca`. Same package, version 1.4.1/41 and signing certificate as prior builds. Install over existing app; keep data, open online to recover friends.
- Host-wait behavior remains from base `064af5f3b3daa4aae421a9ff9c9fb5f09158931e`. No controller transfer/referee, gameplay timing changes, deployment, GitHub push, or messages/invitations.
- Protected 1.4.1 checkout `/home/bob/workspace/bingo_multiplayer` and its 159 fingerprints remain intact. Prior host-wait checkout/APK and shared APK unchanged. Roll back via the separate host-wait checkout if needed, preserving this branch and app data.

Prior host-wait implementation/test details remain in `artifacts/host-disconnected-wait/` and the separate `/home/bob/workspace/bingo_host_wait` checkout. The user deferred the earlier autonomous-controller/referee plan; do not silently implement it.
