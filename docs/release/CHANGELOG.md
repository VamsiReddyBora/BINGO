# 1.4.2 release preparation

Completed 2026-10-15T23:35:33+05:30. Branch `release/1.4.2`; source fixes `04102f2` (based on UI-polish commit `1943cb8`).

- Centered game/status chat is now compact coloured text with emojis. Filled surfaces, outlines and bubble padding were removed from system notices; player chat bubbles are unchanged.
- Both disconnected-player and disconnected-host cards pass the real username to the shared PlayerAvatar loader. This enables its existing username-keyed memory/disk/friends/profile lookup when START packets intentionally omit avatar URLs. Visible display names are no longer substituted for account usernames.
- Keep the existing deadline, modal restrictions, pause ownership, game state, heartbeats and networking rules. Use the current Compose progress overload to display the same existing deadline.
- Prepare version 1.4.2 / 42. Signed output is automatically named from the version and copied to `releases/`.
- Consolidate the latest app in `/home/bob/workspace/bingo_multiplayer`. Archive 10 historical worktrees outside the workspace, preserving their branches, APKs and local changes. Preserve an independent original 1.4.1 snapshot verified against all 159 baseline fingerprints.
- Archive historical reports, source ZIPs, old APKs, automation output and old generated caches. Remove historical data and a stale root APK from this release branch's tracked tree; retain their originals in backups and Git history. Keep Android, companion web and supporting-service source together without changing service behavior.
- Refresh README and handoff documentation around the single canonical source. Existing signing keys/configuration are retained. No push, deployment or GitHub release performed.

The first incremental build after moving old worktrees hit stale compiler symbol references. Its log is kept in the external backup; a clean build from the canonical folder passed. No game-rule fix was introduced to resolve that build cache issue.
