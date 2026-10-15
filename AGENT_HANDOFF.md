# Bingo 1.4.2 release candidate handoff

Updated 2026-10-15T23:35:33+05:30. **Only active source: `/home/bob/workspace/bingo_multiplayer`**, branch `release/1.4.2`. Make future changes here; do not create another active source copy. Read README.md, docs/release/CHANGELOG.md and docs/release/TEST_REPORT.md.

APK: `releases/Bingo-1.4.2.apk`, 7,096,410 bytes, SHA256 `fae9910f3bdfe54dd70e8ba6eed4581dd6becb350b9c3069fc22e7fe0d5e7f79`. Version 1.4.2 / 42 was selected as the next patch release candidate. Package/signing certificate are unchanged. Clean build from this exact folder passed 481 tests / 46 suites; lint 0 errors / 36 warnings. ReleaseManifest.json and validation/mapping archives are in releases/. APK/archive files are ignored by Git.

Current fixes: compact text-only centered status chat with approved colours/emojis; correct username forwarding into both popup avatar loaders; account usernames are used instead of display-name guesses. The existing shared avatar cache/profile loader is reused. Domain/gameplay/network source is unchanged; host-only pause/resume, the 15-second decision deadline, blocking host-disconnection hold and synchronization behavior are preserved. Existing friends/badges/ping/social additions remain.

Backups: `/home/bob/backups/bingo-release-preparation-2026-10-15`. Contains 10 archived worktrees with their local changes/APKs, historical main-folder files and an independent original `baseline-1.4.1` snapshot (159 fingerprints verified). Original branch `1.4.1` remains `a13d39e`; previous UI-polish source `1943cb8` and APK are retained. The main checkout now contains latest release code and is no longer the original baseline. A full Git bundle and a copy of the current release deliverables are saved in the backup root when finalization completes.

No upload, tag, push, deployment or GitHub release was performed. User will test on physical devices; do not run more simulations without a new request. Popup photo rendering still needs the user's device check. The earlier unexplained closure was never confirmed fixed; existing Settings → Copy app diagnostics remains. Use this APK's mapping for future crash retracing.
