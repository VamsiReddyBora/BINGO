# Current Android deliverable

The active release candidate is `Bingo-1.4.2.apk` (version code 43), built from this repository. The `pre-release-ver-1.5` branch tracks this APK, its exact R8 mapping, current validation archive and the original notification-review evidence. Other generated APKs and archives remain ignored. Validation, backend deployment state and SHA256 are recorded in `ReleaseManifest.json` and `docs/release/TEST_REPORT.md`.

Install the same candidate on both phones for physical acceptance testing. The branch name marks the checkpoint for future 1.5 work; the included APK's version remains 1.4.2 / 43. A public GitHub release or app-store release is separate from this branch backup.

Read `docs/release/NotificationHardeningReport-2026-10-16.md` before rollout: build 43 uses a separate authenticated notification service; old APK/web clients do not interoperate with it. Older unsigned guest profiles need a supported sign-in for secure notifications. No app data is automatically wiped or reassigned.
