# Current Android deliverable

The active release candidate is `Bingo-1.4.2.apk` (version code 43), built from this repository. APKs and release mapping archives are generated deliverables and remain ignored by Git. Validation, backend deployment state and SHA256 are recorded after the build in `ReleaseManifest.json` and `docs/release/TEST_REPORT.md`.

Install the same candidate on both phones for physical acceptance testing. Publishing/uploading the release is a separate action.

Read `docs/release/NotificationHardeningReport-2026-10-16.md` before rollout: build 43 uses a separate authenticated notification service; old APK/web clients do not interoperate with it. Older unsigned guest profiles need a supported sign-in for secure notifications. No app data is automatically wiped or reassigned.
