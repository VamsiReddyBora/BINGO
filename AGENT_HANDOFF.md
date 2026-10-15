# Bingo 1.4.1 backup and agent handoff

Prepared on **2026-10-15 (Asia/Kolkata)** for the GitHub backup branch **`1.4.1`** in `VamsiReddyBora/BINGO`.

## Start here

This branch preserves the current working Android source after restoring selected UI/social features and cleaning unused production code. The base is release tag **`v1.4.1`**, commit **`c01252767d96185e503f9b9dcefa11f1cc1fc02d`**. The backup branch contains additional work; the original release tag remains unchanged.

**The separate reliable 1v1 disconnection/rejoin redesign has not been implemented by this restoration.** Original 1.4.1 disconnection policies remain. Do not describe this backup as passing all reconnect scenarios.

Read these reports before making changes:

1. [Final source cleanup review](artifacts/source-cleanup-1.4.1/SourceCleanupReview.md).
2. [Earlier feature restoration report](artifacts/feature-restore-1.4.1/FeatureRestorationReport.md). Its test count and APK describe the earlier build; the cleanup report and root APK describe the current build.

## Agreed work completed

The user requested these additions from older source ZIPs while keeping fresh 1.4.1 gameplay:

| Feature | Current behavior / main locations |
| --- | --- |
| Host/Player badges | Lobby header role badges in `presentation/lobby/LobbyScreen.kt`. |
| Lobby ping | Measured, colour-coded ping through `domain/network/NetworkPingMonitor.kt` and lobby UI; unknown latency displays an unavailable value. |
| Detailed activity | AI difficulty, online match, nearby LAN and room activity via `domain/network/PresenceActivityFormatter.kt`, `PresenceManager.kt` and navigation. |
| Friends leaderboard | Victories, streak and level views in `presentation/social/FriendsLeaderboardView.kt`; refresh through `domain/network/LeaderboardSyncManager.kt`. |
| Profile/room QR | Profile sharing/scanning and online room sharing/scanning in `presentation/social/`, `presentation/online/` and `domain/network/QrCodeHelper.kt`. Existing LAN QR remains. |
| Richer statistics and scorecards | `domain/engine/FriendCareerStats.kt`, `HeadToHeadCalculator.kt`, `MatchScorecardStats.kt`, social dashboard and `MatchScorecardDialog.kt`. |
| Chat typing | Throttled typing/stop packets with expiry in existing transport and `presentation/game/InGameChatSpace.kt` / `presentation/navigation/RootNavGraph.kt`. |

Production Kotlin paths above are relative to `app/src/main/java/com/bingo/multiplayer/`.

Integration fixes preserve old career totals, allow a real loss to reset current streak to zero, fence asynchronous refreshes by account, merge match history, match exact opponent identities, validate room QR inputs, preserve board size and store actual match snapshots. Scorecard line counts come from marked cells, independently of a surrender/forfeit award. Missing historical metadata remains unavailable rather than invented.

The donor was `Bingo_Multiplayer_15_Modules.zip`; relevant additions in `Bingo_Multiplayer_G2.3.zip` were compared. Experimental later disconnection/authority rewrites were not imported. The donor ZIPs are local archives, not required build inputs.

## Cleanup completed

- Removed **2,660 production Kotlin lines** from the restored source: 94 to 89 Kotlin files, 41,667 to 39,007 lines.
- Moved unused in-memory Firebase/Nearby session models into `app/src/test/.../network/support/`. Tests of these models are simulations, not live networking evidence.
- Removed fake auth APIs, unused UI renderers, disabled particle wrappers, diagnostic notification/update paths, confirmed unused helpers and imports.
- Preserved framework entry points, legacy serialized values, real Google sign-in, live online/LAN transports and active gameplay/UI.
- Kept Android Base64 in production and a JVM-only implementation under `app/src/test/java/android/util/Base64.kt`.
- Corrected resource API qualifiers and manifest lint errors. Separate `web/`, FCM relay and admin tooling remain; this was an Android shipping-source cleanup.

The [cleanup patch](artifacts/source-cleanup-1.4.1/cleanup.patch) compares cleanup with the already restored source. Use Git's diff against `v1.4.1` to review the full restoration plus cleanup.

## Current verified build

| Item | Result |
| --- | --- |
| Android version | `1.4.1`, version code `41`, minSdk 24 / targetSdk 34 |
| Current signed APK | [Root `Bingo.apk`](Bingo.apk), **6,985,826 bytes** |
| APK SHA-256 | `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc` |
| Signing certificate SHA-256 | `39a428942c3c2629ff3490c944b8b82c7625b22d660b7a2cdf682456d81f60a4` |
| JVM tests | **304 tests / 30 suites**, zero failures, errors or skips |
| Android lint | **0 errors, 47 warnings** |
| Release build | Passed; final verification completed **2026-10-15 06:49 IST** |

Validation command used on the current source:

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease --offline --console=plain --max-workers=2
```

Use online dependency resolution on a new checkout if caches are absent. Configure the local Android SDK through `local.properties` or the usual environment settings; local SDK paths and build caches are not backup contents. Preserve signing configuration and avoid printing credentials.

Before this backup, all **159 files under `app/src`** were checked against [the final build's SHA-256 fingerprints](artifacts/source-cleanup-1.4.1/source-fingerprints.json): no differences or extra files. Existing test XML/lint results and the root APK were rechecked. This backup task itself changes documentation and Git history, not gameplay source, and does not rerun the full build.

## Device evidence and limits

The earlier restored build passed Kevin/Stuart emulator checks for joining, role badges, normal number picks, chat, remote typing, explicit surrender and saved scorecards/statistics. Integration failures in typing and scorecard line counts were corrected and repeated before cleanup.

The final clean build was installed over Kevin's saved installation and checked for startup, dashboard, profile QR, host room creation, HOST badge, ping, room QR and return to menu. No AndroidRuntime crash appeared during that inspected smoke window. The final cleanup smoke did not play another two-device match.

- Kevin retained 15 matches, 10 victories, current streak 0, best streak 5, level 2 and 1125 XP after the earlier test matches. These are real retained test records, not manually reset statistics.
- No invitation was sent to `@bob` during these restoration/cleanup checks.
- The emulator started for cleanup verification was shut down afterward; no test match is left running.
- Physical-camera QR scanning, external Android sharing targets, mixed-version clients, complete LAN gameplay, slow/offline cloud recovery and exhaustive paired disconnect/rejoin cases still need dedicated device testing.
- Older records without opponent IDs or board snapshots cannot retrospectively recover those missing details.
- Passing JVM simulation tests does not establish a live multiplayer or exhaustive edge-case guarantee.

## Future disconnection/rejoin work

The user's broader goal is a recoverable 1v1 match when a player backgrounds, loses network, swipes the app away or returns later. The original requested rules include accurate opponent status, no automatic surrender merely from unavailability, normal 30-second turns with one skip per expiry, preserved boards/picks/chat, synchronized current player/turn/time, no stale timeout-driven skips, and no switch into AI mode. Explicit surrender and legitimate Bingo victory must continue working.

Those requirements are future work relative to this backup. The original baseline missed-turn behavior did reach a disconnect/forfeit outcome during restoration testing. Do not claim that behavior has been fixed. Pause/continue/claim-victory policy choices discussed separately also need an explicit agreed specification before changing gameplay rules.

For that work, inspect the real runtime paths: `domain/network/OnlineRoomSyncManager.kt`, `OnlineRoomRegistry.kt`, `OngoingMatchStore.kt`, `domain/engine/LobbyLifecycleEngine.kt`, `presentation/navigation/RootNavGraph.kt` and `presentation/game/GameScreen.kt`. Do not base fixes on the test-only session models. The user prefers MQTT for gameplay; keep any future authority decision explicit and avoid silently reintroducing experimental backend changes.

## Backup contents and resuming

- This branch includes current source/resources/tests, the root signed APK, both review reports and selected linked build/test/lint/screenshot evidence.
- Old APK copies, source ZIPs, build caches, local SDK configuration, raw presence traces, abandoned live-play automation and intermediate audit outputs remain local; they are not needed to build this snapshot.
- Repository signing/configuration files inherited from the base are preserved. No backend deployment, release publication, release-tag update or change to remote `main` is part of this backup.
- The root README's older release badges predate this backup; use this handoff and the current manifest for snapshot status.

To resume on another machine:

```bash
git clone --branch 1.4.1 https://github.com/VamsiReddyBora/BINGO.git
cd BINGO
git status
```

Read this file, the two review reports and the current source before planning further changes. Prefer a new working branch based on this snapshot so the backup stays identifiable.
