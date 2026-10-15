# 1.4.2 validation

Completed 2026-10-15T23:35:33+05:30 from the canonical `/home/bob/workspace/bingo_multiplayer` folder.

- Clean `testDebugUnitTest lintDebug assembleRelease`: **passed**. Kotlin incremental compilation was disabled for this relocation build and compiler execution ran in-process.
- **481 tests / 46 suites; 0 failures, errors or skips.**
- Lint: 0 errors / 36 warnings.
- Existing release certificate verified. Package `com.bingo.multiplayer`; version 1.4.2 / 42.
- APK: `releases/Bingo-1.4.2.apk` (7,096,410 bytes), SHA256 `fae9910f3bdfe54dd70e8ba6eed4581dd6becb350b9c3069fc22e7fe0d5e7f79`.
- Source inspection: system notice branch has no filled Surface/background; both popup avatar paths forward username into the existing shared loader.
- Domain/gameplay/network sources match `1943cb8` byte for byte. RootNavGraph changes are limited to using the actual opponent username for profile identity.
- Workspace has one active Bingo folder; original 1.4.1 snapshot matches 159 fingerprints, its branch is unchanged, and the previous UI-polish APK hash is preserved.
- Full build log, unit XML, lint and signature output are in `releases/Bingo-1.4.2-validation.zip`. Exact R8 mapping is in `releases/Bingo-1.4.2-mapping.zip`.

Physical UI/photo rendering was not simulated; the user will validate on phones. The earlier unexplained closure was never established as fixed; its existing Settings → Copy app diagnostics feature is preserved.
