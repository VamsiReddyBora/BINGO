# Bingo 1.4.1 — source cleanup review

Reviewed on 2026-10-15 06:39 IST. Verification completed **2026-10-15 06:49:04 IST** (2026-10-15 01:19:04 UTC). Final APK details are recorded below.

## Result

The Android source did contain unused production code. Confirmed dead implementations, test scaffolding, disabled placeholders and unused imports have been removed from the shipping source set. The restored UI/social features and the existing game engine remain present.

| Production Kotlin | Before this cleanup | After |
| --- | ---: | ---: |
| Source files | 94 | 89 |
| Lines | 41,667 | 39,007 |
| Explicit imports, including deleted/moved files | 2,412 | 2,194 |

**2,660 production Kotlin lines removed.** The comparison starts from the working feature-restoration source, not the original release tag. [Cleanup-only patch](cleanup.patch) and [file inventory](file-changes.json) show the exact changes.

## Findings and fixes

| Location | Finding | Resolution |
| --- | --- | --- |
| `domain/network/FirebaseRoomSessionManager.kt` | An in-memory room simulation was shipped as if it were a live Firebase room manager; no app caller. | Moved into `app/src/test/.../network/support/FirebaseRoomSessionManagerTestModel.kt`. The app continues using its real online transport. |
| `domain/network/NearbyConnectionsSessionManager.kt` | Simulated Nearby transport, latency/drop injection, watchdog and peer policies had no app caller. | Moved into `app/src/test/.../network/support/NearbyConnectionsSessionManagerTestModel.kt`. Actual LAN gameplay still uses `LanP2pSessionManager`. |
| `domain/repository/AuthRepository.kt` | An unused fake Firebase provider manufactured Google/Play Games IDs and token strings; mock login/refresh/profile APIs had no live callers. | Removed the fake provider, related APIs and unused Play Games entry point. Kept actual Google sign-in, saved-session restoration, cloud backup and old persisted provider values. Tests now cover the real entry points. |
| `presentation/components/HeadToHeadScorecard.kt`, `BingoLineStrikesOverlay.kt` | Old score/progress UI was never rendered. | Deleted. Active game bars, board cells, marked lines and the restored historical match scorecard remain. |
| `presentation/components/MultiEmojiFloatingParticleSystem.kt` | A separate particle renderer and configuration had no live caller. | Deleted the unused renderer/configuration and its default-value-only test. Active floating chat reactions and stamp animations remain. |
| `presentation/components/FloatingEmotes.kt` | The earlier `FloatingEmoteBar` had been superseded by the current reaction/chat strip. | Removed the older bar; kept `EmojiReactionStripWithChat`, `FloatingEmotesOverlay` and their active helpers. |
| `presentation/game/GameOverCelebration.kt`, `GameScreen.kt` | A disabled particle wrapper only called completion callbacks; several UI arguments were ignored. | Kept the actual stamp state changes directly and removed the wrapper/ignored arguments. |
| `domain/network/BingoNotificationManager.kt` | Dummy notification triggers, bypass flags, unused timing constants and notification methods that only logged “suppressed”. | Removed the placeholders and their no-op calls. Kept real invite/online notifications, preference/foreground checks and cancellation of notifications from older installations. |
| `domain/network/BingoFcmManager.kt`, `BingoFirebaseMessagingService.kt` | Device self-test sending and special test-message bypass code remained in production. | Removed those diagnostic paths; ordinary FCM invitation delivery remains. |
| `domain/network/AppUpdateManager.kt` | Test update injection and a self-install simulation branch were still present. | Removed them; retained real update checks, downloads and installation. |
| `domain/network/HotspotAndWifiManager.kt` | Uncalled Wi-Fi scan and programmatic connection helpers had obsolete callback/coroutine state. | Removed unused helpers/state; retained active Wi-Fi/hotspot settings, gateway discovery and network-route cleanup. |
| `domain/network/BroadcastMessageManager.kt` | Unwired Android-side admin publishing/preview helpers and a suppressed notification call. | Removed those unused entry points. Receiving/displaying announcements and existing admin tooling remain. |
| `domain/network/PresenceManager.kt`, `FriendsRepository.kt`, models | Unused online/room getters, a friends stream, converters, counters and ignored parameters. | Removed only confirmed unused declarations/arguments; kept actual presence, leaderboard refresh and persistence paths. |
| `domain/network/OnlineRoomRegistry.kt` | JVM-only Base64 fallbacks were mixed into Android code and triggered API-level lint errors. | Production uses Android Base64. Local JVM tests provide their implementation under `app/src/test/java/android/util/Base64.kt`. Compressed, legacy URL-safe and standard Base64 input handling remains tested. |
| `AndroidManifest.xml`, `res/values*/themes.xml` | Unused Bluetooth permissions, a missing location-permission pair and navigation-bar attributes in API-24 resources. | Removed Bluetooth declarations; retained the location permission pair needed by legacy Wi-Fi discovery. API-27 attributes now live in API-27-qualified resources. |

Kotlin syntax-tree inspection identified unused imports while preserving operator/delegation imports. A repository-wide reference check, manifest/framework entry-point review, compiler checks and Android lint were used alongside it. A declaration was not deleted solely because its name had no direct Kotlin caller.

## Retained deliberately

- `BingoApplication`, `MainActivity`, the FCM service, notification receiver, WorkManager entry points and socket overrides are framework entry points.
- `BingoEngine`, `BingoAiPlayer`, manual-board rules, the packet codec, online room transport, ongoing-match store, QR helpers and restored statistics calculations remain. The lobby engine has only a whitespace change.
- Three public lifecycle/cooldown utility methods are exercised by domain tests and retained as tested APIs: `onLocalToggleReady`, `getInviteRemainingSeconds`, and `getFriendRequestRemainingSeconds`. They are listed in [remaining-symbol audit](remaining-single-reference-symbols.json).
- Existing serialized fields and old provider/result values remain readable. Migration/cancellation code for existing installations has a purpose.
- The separate `web/` client, Cloudflare FCM relay, administration script, signing material, source ZIPs and previous artifacts were retained. They are separate projects/assets; their mere presence does not put them in the Android APK. This cleanup audits the Android shipping source and its tests, not a rewrite of those separate clients/tools.
- The original multiplayer disconnection/rejoin policies are unchanged. Their redesign remains a separate task.

## Verification

**PASS:** `testDebugUnitTest lintDebug assembleRelease --offline --console=plain --max-workers=2` completed successfully in **3m 50s**. **304 tests passed, zero failures/errors/skips across 30 suites. Android lint: zero errors, 47 warnings.** [Build log](build.log), [test XML results](unit-test-results/), [lint report](lint-results.html).

Signed APK: **1.4.1**, version code **41**, **6,985,826 bytes**. SHA-256: `6c5e014b4a8a699e1506393e79bb6bb3959ccb72e2d6fa85cc425f1118005ccc`. Its certificate matches the previously delivered APK.

The [DEX class audit](apk-class-audit.json) confirms 9,552 → 9,459 defined classes. The two simulation managers and fake auth provider are absent; live online/LAN transports remain; the test Base64 implementation is not packaged.

The [source fingerprints](source-fingerprints.json) record the exact reviewed source. **Device smoke: PASS on Kevin's emulator.** In-place installation and launch succeeded. Saved identity/theme and career totals remained intact: 15 matches, 10 victories, level 2, current streak 0, best streak 5, 1125 XP. [Menu](screens/01-clean-menu.png), [dashboard](screens/02-clean-dashboard.png), [profile QR](screens/03-clean-profile-qr.png), [host lobby](screens/04-clean-host-lobby.png), [room QR](screens/05-clean-room-qr.png).

Private room Z3NGD4 was created and exited. The role badge, measured ping and generated QR rendered correctly. [Final menu](screens/06-clean-final-menu.png). No new match was played and no invitation was sent. No AndroidRuntime crash entries appeared in the captured [runtime log](emulator-runtime-errors.log). The emulator started for this check was stopped afterward.

The emulator process failed during the first installation attempt and was restarted; the final installation/startup check passed. Full two-device gameplay, camera scanning and physical-device disconnection testing were not repeated during this cleanup pass.

The updated suite has **304 tests**. Two retired mock token-refresh tests and one unused particle-default test were removed because their production implementations were removed. Google login, session persistence, legacy profile reading, career preservation and sign-out checks were updated to exercise the actual repository paths. Tests for the moved session models remain explicitly test-only simulations; they do not prove live network behavior.

Android lint has reached **zero errors**, with **47 warnings** remaining. Warnings include newer dependency suggestions, older-device API deprecations, icon/resource presentation checks, singleton-context heuristics and existing UI/style checks. This review does not label the project warning-free or claim a mathematical proof that every possible unused declaration is absent.

## Delivery

- [Updated signed APK](../../Bingo.apk)
- [APK manifest](manifest.json)
- [Cleanup-only patch](cleanup.patch)
- [Changed-file inventory](file-changes.json)
- [Production metrics](metrics.json)
- Source before this cleanup: `source-before-cleanup.zip` is retained locally and excluded from the GitHub source backup.

The prior feature-restoration APK/report remains separately preserved in `artifacts/feature-restore-1.4.1/`.
