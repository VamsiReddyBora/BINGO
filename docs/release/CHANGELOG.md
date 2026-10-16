# 1.4.2 release preparation

## Notification candidate — build 43, 2026-10-16

- Implement authenticated notification sessions and private token/inbox storage with account/generation isolation, durable revocation, strict event types and original expiry.
- Centralize FCM, MQTT and polling presentation; deduplicate system/in-app alerts, enforce notification preferences and guard invitation actions against replacing a running game.
- Support request/acceptance pushes and useful online-alert navigation; add native notification actions and update/broadcast OS notices; remove unused custom layouts.
- Add durable background retry, ACK tombstones, concurrent-invite storage, token/session race protection and actionable Settings status.
- Confirm the Web OAuth audience with the user. New protocol-2 notification service is isolated from the older live match-controller Worker; legacy retirement and old guest/web migration limits remain explicit in the hardening report.
- Preserve the existing host-disconnection policy, game engines, number-pick transport and all previous UI/social changes. Read `TEST_REPORT.md` and `ReleaseManifest.json` for final validation and deployment state. Source baseline and original signed deliverables are backed up.

## Previous UI candidate — build 42

Completed 2026-10-15T23:35:33+05:30. Branch `release/1.4.2`; source fixes `04102f2` (based on UI-polish commit `1943cb8`).

- Centered game/status chat is now compact coloured text with emojis. Filled surfaces, outlines and bubble padding were removed from system notices; player chat bubbles are unchanged.
- Both disconnected-player and disconnected-host cards pass the real username to the shared PlayerAvatar loader. This enables its existing username-keyed memory/disk/friends/profile lookup when START packets intentionally omit avatar URLs. Visible display names are no longer substituted for account usernames.
- Keep the existing deadline, modal restrictions, pause ownership, game state, heartbeats and networking rules. Use the current Compose progress overload to display the same existing deadline.
- Prepare version 1.4.2 / 42. Signed output is automatically named from the version and copied to `releases/`.
- Consolidate the latest app in `/home/bob/workspace/bingo_multiplayer`. Archive 10 historical worktrees outside the workspace, preserving their branches, APKs and local changes. Preserve an independent original 1.4.1 snapshot verified against all 159 baseline fingerprints.
- Archive historical reports, source ZIPs, old APKs, automation output and old generated caches. Remove historical data and a stale root APK from this release branch's tracked tree; retain their originals in backups and Git history. Keep Android, companion web and supporting-service source together without changing service behavior.
- Refresh README and handoff documentation around the single canonical source. Existing signing keys/configuration are retained. No push, deployment or GitHub release performed.

The first incremental build after moving old worktrees hit stale compiler symbol references. Its log is kept in the external backup; a clean build from the canonical folder passed. No game-rule fix was introduced to resolve that build cache issue.
