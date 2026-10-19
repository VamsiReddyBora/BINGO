# Simplified rules implementation log

Started 18 October 2026. Work is based on `SimplifiedGameRules.md` and the 30 findings in
`SimplifiedGameRulesComplianceReview.md`.

## Recovery point

The complete working tree at the start of this work, including existing uncommitted source,
rules, scripts and APK, is archived at:

`/home/bob/workspace/bingo-simplified-rules-fix-20261018-183525/before-source.tar.gz`

The adjacent `before-hashes.json` and `before-status.txt` identify the exact original files.
Restore only files intentionally selected from that archive; do not reset unrelated user work.
No Git commits, pushes or deployments are part of this implementation unless requested.

## Changes in progress

- Added a serializable shared turn-clock policy: 30/5-second budgets, preserved remaining time,
  one welcome-back promotion for absence-started turns, and freeze/resume without a new budget.
- Added a shared result evaluator that keeps offline players eligible and applies caller priority.
  Existing non-caller tie behavior is preserved, as confirmed by the user.
- Existing manual pause and social/lobby features are being preserved. Review findings are being
  addressed through their running call sites, not only through standalone helper changes.

## Verification

- Final source review addresses R01–R30; see `SimplifiedRulesFixReport.md` for each running path.
- Completed automated run: **677 tests / 59 suites; zero failures, errors or skips**.
- Included 112 complete policy/recovery simulations and the isolated production MQTT integration test.
- Intermediate failures were corrected: old assertions expected obsolete miss penalties/identity matching; new fixture timestamps were made consistent with their simulated clocks; saved remainder handling was strengthened against older retained state.
- Android UI/device testing is assigned to the user. Emulator startup was unavailable (`/dev/kvm`: No such device).
- Final signed candidate test build: `/home/bob/workspace/bingo_multiplayer/releases/Bingo-1.5.0-SimplifiedRules-Test.apk`
  - Signed debug/device-test build using the project's existing release signing key.
  - Package: `com.bingo.multiplayer`, version: `1.5.0`, versionCode: `44`, minSdk: 24, targetSdk: 34.
  - File size: 28,875,435 bytes | SHA-256: `bf03f7ad0a336e81b5ac1c474e482b48d3bf676cab6bab4b662286f1da94da58`.
  - Signer certificate SHA-256: `39a428942c3c2629ff3490c944b8b82c7625b22d660b7a2cdf682456d81f60a4`.
  - Built from final source (including the manual-preparation guard). Distinct from a production R8-optimized release (`./gradlew :app:assembleRelease`), which can be generated separately if requested.
- Evidence: `/home/bob/workspace/bingo-simplified-rules-fix-20261018-183525/verification`.


## Implementation changes

- The shared turn clock, presence generation policy, result evaluator, scoped packet gate and retained peer recovery are small separate domain files. Root callbacks use these production helpers.
- Online picks/timeouts consume the shared broker command stream. Local feedback is optimistic; a failed publish cannot privately advance turns or finalize a result. Accepted commands and peer snapshots have match-scoped retained recovery records.
- Reconnects fence prior connection wills, preserve remote status and wait for catch-up. All-closed cold recovery preserves the saved countdown; a single recovery probe is bounded to 1.5 seconds.
- Explicit exits persist before cleanup, retry independently and cannot disconnect a replacement room. Permanent departures remain separate from transient availability.
- Boards, history, accepted checkpoint, presence, clock, chat and final result save together. Conditional writes/clears protect a newer match from delayed recovery work.
- Manual-board readiness uses a consistent eligible roster and a shared start instant. New seeds reset replay state. Manual pause stays available and its controls are versioned and broker-ordered.
- Online match state polling is event-driven. Existing social presence/lobby ping and Nearby transport behavior are preserved.
- `SimplifiedGameRules.md` records the user's frozen-countdown and existing tie-break clarifications. No unrelated social feature, game version, signing identity or notification service was replaced.
