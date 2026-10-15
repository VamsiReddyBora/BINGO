# Friends list and repeated request toast — repair report

Verified **2026-10-15T19:27:35+05:30**. Branch `fix/friends-list-requests`, application commit `bee8a3b6e0454b8682dd1a21a0a275c4e2d00450`. Based on the completed host-wait implementation `064af5f3b3daa4aae421a9ff9c9fb5f09158931e`.

## What was wrong

1. **Missing friends:** the old sync treated `user_directory` as a complete list of valid accounts. It filtered out friends absent from that directory, replaced the local roster, and saved the reduced roster back to KeyValue. Read-only checks found **one friend in Bob's primary list, all four in the existing Firestore backup, and exactly three of those four absent from the directory**. This reproduces the reported one-friend result. The host-wait update had not directly changed FriendsRepository or FriendRequestManager; this behavior was already in the baseline.
2. **Repeated Kevin request toast:** the MQTT listener unconditionally notified for every FRIEND_REQUEST packet. It did not reject an existing friend, an accepted/declined request, retained packets, or duplicate delivery across app restarts. Bob currently has **zero pending requests** in cloud storage, so an old packet is not a valid new-request notification. The actual packet that caused the phone toast was not captured; the missing validation paths are fixed and tested.
3. **Another route to list loss:** accepting a request previously converted a failed friend-list read into an empty list and then saved only the new friend. Write failures could still report acceptance. Full friend/profile payloads could also exceed the storage URL limit: Bob's four-friend roster was **1,048 encoded bytes even after filtering its large avatar**, against the documented 1,024-byte query limit.

## Corrections and locations

| Location | Correction |
| --- | --- |
| `FriendsRepository.kt` — account setup/reconcile | Restore the union of the existing per-friend backup, primary roster, and account-specific cache. Remove directory-based deletion. Preserve career totals and current streak using the newest career snapshot. |
| `FriendsRepository.kt` — request listener | Confirm the request is still pending for this account after restoring friendships; suppress existing friends, resolved IDs and duplicate event IDs. Persist notification receipts before showing a toast. |
| `FriendsRepository.kt` — removal/account changes | Persist explicit removals; reject stale responses after account switches. Versioned removal/restoration receipts allow a later accepted friendship without allowing older records to undo a new removal. Coalesce overlapping sync jobs. |
| `FriendsDataSource.kt` | Small network/storage port; production repository tests exercise real reconciliation through deterministic adapters. Backup reads are cached for 30 seconds; an incoming friend event refreshes the backup immediately. |
| `FriendsCloudStore.kt` | Distinguish a failed/malformed read from an actual empty list. Never replace friendships after a failed read. Compact the legacy base64 JSON payload; retain every friend identity/name and keep full profiles in the existing backup. |
| `FriendRequestManager.kt` | Ignore retained packets; check persistence results before notifications; preserve requests until acceptance writes succeed. |
| `FirestoreSyncManager.kt` | Read existing friend documents, retain removal receipts, and transactionally record which removal a new acceptance restores. No clock comparison is used for this decision. |
| `RootNavGraph.kt` | Bind the friends repository to UID/username changes and clear visible state on sign-out. Existing toast wording is unchanged. |
| `app/build.gradle` | Copy the new release only into `artifacts/friends-fix/Bingo-friends-fix.apk`. |

The host controller, host-disconnected dialog, turn engine, timers, board synchronization and saved-match implementation are unchanged. Both old baseline APKs and the prior host-wait APK are preserved. No cloud deployment, GitHub push, invitation, friend request, or manual production-data repair was performed.

## Validation

`./gradlew testDebugUnitTest lintDebug assembleRelease --offline --console=plain --max-workers=2`

- **454 tests in 42 suites: 0 failures, 0 errors, 0 skipped.** This includes **35 new tests** using the production FriendsRepository and HTTP storage adapter, plus all 419 existing tests.
- Tested: one-primary/four-backup recovery; offline/malformed responses; duplicate/accepted/declined/stale/wrong-recipient requests; restart receipts; listener closure; account-switch races; coalesced polling; explicit removal; later re-addition including return after acceptance by the other player; stale restoration markers; complete four-friend payload size; avatars and career streak preservation/reset.
- Lint: **0 errors/fatal, 36 warnings**. Two added advisory warnings concern intentional synchronous preference commits for migration/handled receipts; the remaining warning count is unchanged from the host-wait build.
- Signed release passed `apksigner verify`. Certificate matches the prior release.
- All **159 protected 1.4.1 source fingerprints** and the three protected APK hashes still match.
- Diagnostic cloud reads confirmed the recoverable four-friend backup. These reads did not modify live data.
- **Android UI/device checks were not run:** `adb devices` has no connected device. Actual on-phone display, toast behavior and Firebase acceptance writes still need device confirmation; repository/HTTP tests are not presented as a phone simulation.

Evidence: `evidence/final-build.log`, `evidence/unit-results/`, `evidence/test-summary.json`, lint reports, APK signature output, and read-only cloud/size summaries. Earlier successful review builds and the initial timestamp-fixture test failure are retained in the journal/evidence.

## APK and install

[Download Bingo-friends-fix.apk](/home/bob/workspace/bingo_friends_fix/artifacts/friends-fix/Bingo-friends-fix.apk)

Package `com.bingo.multiplayer`, version **1.4.1 (41)**, **7,071,841 bytes**. Install as an update over the existing app; keep app data. Open as Bob with internet access so the existing backup can restore the four-friend list. No new friendship acceptance is required for these four saved records.

SHA256: `14bec2f264ef8de5e6522d499f34ff32fae3207d1a6fdb244cff91824b8b21ca`

Signing certificate SHA256: `39a428942c3c2629ff3490c944b8b82c7625b22d660b7a2cdf682456d81f60a4`

## Phone checks to finish

1. Update Bob's current installation, open online, and confirm all four friends and their profile information appear.
2. Close/open three times and switch background/foreground. The accepted Kevin request must produce no new toast or pending request.
3. With a separate test account, send one genuinely new request: verify a single notification, then accept/decline and reopen to verify it stays resolved.
4. Remove a test friend, reopen/sync, and confirm recovery does not resurrect the removal. Send/accept a new request and verify the later friendship can return, including when acceptance happens while Bob is closed.
5. Briefly go offline and reopen: preserve cached friends; restore cloud data when connectivity returns. Check the existing host-disconnection popup/rejoin on both phones as a device smoke test.

## Rollback/handoff

Use the separate unchanged `/home/bob/workspace/bingo_host_wait` checkout and its original signed `artifacts/host-disconnected-wait/Bingo-host-wait.apk`. Preserve app data and this branch/evidence. Do not reset the protected 1.4.1 branch. Source, fingerprints and manifest are available in this checkout for the next agent.
