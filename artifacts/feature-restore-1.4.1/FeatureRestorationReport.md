# Bingo 1.4.1 — UI and social feature restoration

GitHub backup note: this report describes the earlier restoration build. The current source and root APK also include the subsequent [source cleanup](../source-cleanup-1.4.1/SourceCleanupReview.md). Old APK copies and the raw presence trace mentioned below remain local; selected screenshots and test/build evidence are included with the backup.

Verified **2026-10-15 06:00:09 IST** (2026-10-15 00:30:09 UTC).

Signed Android release: **1.4.1**, version code **41**, **7,017,689 bytes**. SHA-256: `2cbb0823ae8c9a3333a06d8cf514a7c4e987f0c6ff51b5f7d23e23e6b2ebadc6`. Signing certificate matches the previous APK, allowing an in-place installation.

## Scope and source

Base: GitHub tag `v1.4.1`, commit `c01252767d96185e503f9b9dcefa11f1cc1fc02d`.
The selected features were extracted from `Bingo_Multiplayer_15_Modules.zip`; corresponding additions in `Bingo_Multiplayer_G2.3.zip` were compared for reference. Integration used the earlier archive and small, targeted changes to the existing 1.4.1 code.

The agreed scope includes the four requested features and the additional QR sharing, statistics/scorecards, and chat typing features.

## Restored features

| Feature | Result |
| --- | --- |
| Host and Player badges | Compact HOST/PLAYER badges in the lobby header; existing room controls retained. |
| Lobby ping | Stable, colour-coded measured ping next to the badge. Unknown connectivity displays an unknown value instead of fabricated latency. MQTT round-trip measurements are preferred when available. |
| Detailed activity | Friends/player profiles can show `playing (vs AI (Easy))`, `playing (vs AI (Master))`, `playing (Online Match)`, `playing (Nearby LAN)`, and `in-lobby (Room CODE)`. Existing base presence states remain compatible. |
| Friends leaderboard | Victories, current streak, and level sorting; podium, refresh, and player-profile access. Stored career totals are preserved when older cloud records omit new fields. |
| Profile QR sharing | Share a username/profile QR and scan a profile QR through the social screen. |
| Room QR sharing | Generate/share a room QR, scan or paste a room code when joining. Payload includes the actual board size. Existing LAN QR sharing is retained. |
| Richer player statistics | Career totals, victories, win rate, current/best streak, level, and head-to-head summaries. Exact opponent identity prevents names such as Bob/Bobby being combined. |
| Match scorecards | Outcome, opponent, personal picks, actual completed lines, duration, and a miniature marked board. Old records with missing metadata display unavailable values. |
| Chat typing | Named animated indicator, throttled typing updates, stop-on-send/dismissal and expiry; uses the existing multiplayer packet transport. |

## Corrections made during integration

- Removed invented levels and the historical-maximum streak logic. A real loss can reset a streak to zero; older totals cannot restore an obsolete streak.
- Preserved existing friend totals when reading legacy records, and prevented an old account's asynchronous refresh from updating a new account.
- Merged cloud match history with local records instead of replacing local history.
- Added metadata defaults so old saved friends, profiles, and matches remain readable.
- Rejected malformed or overly long room QR codes instead of truncating them into a different room.
- Matched scorecards to the immutable match participants, including after a peer leaves. AI and group records do not inherit a stale 1v1 opponent ID.
- The first live test found that the typing handler captured an old navigation value. It now checks the current destination when a packet arrives.
- The first scorecard test found that a forfeit award appeared as five Bingo lines. Scorecards now calculate lines from marked board cells, independently of the awarded result, and support each recorded board size from 5×5 through 10×10.

## Verification

**Release build: PASS. JVM tests: 307 passed, zero failures/errors/skips across 30 suites. Final APK installed successfully on both emulators.**

Command: `./gradlew testDebugUnitTest assembleRelease --offline --console=plain --max-workers=2`. Final build completed successfully in 2m 22s. The added regression checks cover legacy serialization, stale career statistics, exact head-to-head identity, activity labels/freshness, QR validation, typing packet compatibility, and scorecard counts for 5×5 through 10×10 boards.

The initial typing and scorecard failures were corrected and repeated on the final APK:

| Final check | Result | Evidence |
| --- | --- | --- |
| Host/player roles and peer activity | PASS | [Kevin host](screens/29-final-host-lobby.png), [Stuart player](screens/28-final-player-lobby.png), [refreshed Kevin profile on Stuart](screens/28-final-peer-profile.png). Remote profile eventually showed Kevin's 14 matches, 10 victories and streak 1 after its asynchronous refresh. |
| Live remote typing | PASS | [Kevin is typing on Stuart](screens/31-final-stuart-typing.png). |
| Send message and clear typing | PASS | [Stuart receives Hi](screens/32-final-stuart-chat.png); typing indicator cleared. |
| Correct forfeit scorecard | PASS | [Same previous board now shows 0/5 lines](screens/27-corrected-scorecard.png), replacing the earlier incorrect 5/5. |
| Explicit surrender | PASS | [Stuart sees Kevin surrendered](screens/33-final-stuart-surrender-result.png); Kevin returned to the menu. |
| Zero-value scorecard fields | PASS | [Final surrender scorecard](screens/36-final-surrender-scorecard.png): 0 personal picks, 0/5 lines, 57-second duration and an unmarked board, matched to Stuart. |
| Streak reset and retained totals | PASS | [Final dashboard](screens/35-final-dashboard.png): Kevin 15 matches, 10 victories, current streak 0, best streak 5 and level 2. |
| App crash logs | PASS for the inspected smoke window | No AndroidRuntime crash entries in the captured [Kevin](emulator-5554-runtime-errors.log) and [Stuart](emulator-5556-runtime-errors.log) logs. This does not establish an exhaustive crash/ANR guarantee. |

Final tests used private room **MGD6C9**. Manual pause/resume was used during setup; the final chat/typing exchange happened with the ordinary turn countdown running. The earlier match used private room **9XMYN3**. Screenshots before `27-` document the earlier integration build; `27-` onward document the final APK.

The initial signed build installed successfully over Kevin and Stuart's existing installations. Login, settings and saved history remained available. Actual screenshots and UI dumps are in [screens](screens/).

| Check | Evidence and observed result |
| --- | --- |
| Dashboard and saved totals | [Dashboard](screens/02-dashboard.png); before test matches, Kevin retained 13 matches, 9 victories and level 2. |
| Profile QR | [Share profile dialog](screens/03-profile-qr.png); QR, username and copy/share controls rendered. |
| Leaderboard sorting | [Victories](screens/06-leaderboard-full.png), [Streak](screens/07-leaderboard-streak.png), [Level](screens/08-leaderboard-level.png); Bob and Kevin sorted by the selected stored statistic. |
| Detailed profile | [Bob profile](screens/09-player-profile.png); career statistics and legacy head-to-head fallback rendered. |
| Host badge, ping and QR | [Host lobby](screens/12-host-lobby-final.png), [Room QR](screens/11-room-qr.png); controls fit and the displayed room code matched. |
| Two-device lobby | [Kevin host](screens/16-host-pair-final.png), [Stuart player](screens/17-player-lobby.png); both joined room 9XMYN3 with the correct role badge. Peer activity included the room code. |
| Normal match traffic | [Kevin board](screens/18-kevin-game.png), [Stuart board](screens/19-stuart-game.png), [received pick UI dump](screens/20-stuart-after-pick.xml); Kevin picked 16, Stuart received it with the normal next turn, then Stuart picked 3. |
| Chat | [Stuart receives Kevin's message](screens/22-stuart-chat.png); ordinary chat remained functional. Initial typing failure is retained as [failure evidence](screens/21-initial-typing-failure.png). |
| Scorecard regression | [Initial error](screens/25-initial-scorecard-line-error.png); [Corrected result](screens/27-corrected-scorecard.png) shows 0/5 real lines. |
| Dark theme | [Dark dashboard](screens/26-dark-dashboard.png); readable cards and controls, then original light theme restored. |
| Presence protocol | Local-only `own-presence-observations.jsonl`; real MQTT observations of Kevin/Stuart lobby and online-match activity. Subscriber sent no gameplay packets. |

Unit-test XML results, the release build log, APK metadata and protected-file comparisons are included alongside this report. Automated JVM simulation tests are not equivalent to physical-device end-to-end tests.

## Integration boundaries and remaining checks

The original game engine, turn/lobby engine, packet codec, online room transport, ongoing-match store and LAN session transport are byte-identical to 1.4.1. Build configuration, manifest and signing configuration retain the baseline. See [protected-file comparison](baseline-protected-files.json).

Navigation was changed only to supply presentation activity, chat typing and scorecard metadata. The experimental disconnection/rejoin authority changes from the later ZIPs were not brought into this restoration. The original 1.4.1 disconnection policies remain in effect: during the initial smoke match, repeated missed turns reached its existing disconnect/forfeit outcome. This APK does not establish the separate temporary-disconnection requirements discussed earlier.

Still requiring dedicated device testing: physical camera QR scanning, sharing to other Android applications, mixed-version clients, full LAN gameplay, offline/slow-cloud leaderboard recovery, and the separate disconnection/rejoin scenarios. Larger scorecard boards are covered by calculation tests; their full UI layouts have not yet been checked on every display size.

Historical matches that never stored an opponent ID or a board snapshot cannot supply those missing details retrospectively. Statistics from a legacy remote profile may differ from locally retained history until that profile is refreshed by the existing account/match sync.

Smoke matches use the emulator accounts' normal match-history and career-stat recording. Two test matches were retained: room 9XMYN3 ended through the baseline disconnect/forfeit handling; room MGD6C9 ended through Kevin's explicit surrender. Kevin changed from 13 matches / 9 wins to 15 matches / 10 wins; his current streak correctly returned to zero after surrender. No statistics or history were manually reset. No invitation was sent to @bob or another physical player.

Kevin was left on the main menu in the original light theme. The Stuart emulator started for testing was stopped after the completed match. The AI activity labels are covered by code/unit checks, not an additional played AI match in this smoke run.

## Delivery

- Earlier restoration APK: retained locally as `Bingo-1.4.1-restored-features.apk`; the [current root APK](../../Bingo.apk) includes the subsequent cleanup.
- [APK manifest](manifest.json)
- [Build log](build.log)
- [Unit-test XML results](unit-test-results/)
- [Changed source-file fingerprints](source-files.json)
- Original root/workspace APK copies are preserved as `previous-root-Bingo.apk` and `previous-workspace-Bingo.apk` in this artifact directory.
