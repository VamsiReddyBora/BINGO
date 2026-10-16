# Notification hardening change log

Started 2026-10-16, Asia/Kolkata. Baseline commit: `422ee84c911a1c2226a6eb3e4a17d2d5c0a740ce`.

- Working branch: `fix/notifications-1.4.2`.
- Baseline branch: `backup/pre-notification-hardening-20261016`.
- Original signed APK and manifest: `/home/bob/backups/bingo-notification-hardening-2026-10-16`.
- Added typed notification policy, persistent account/generation store, shared ledger, verified identity, private API, durable work, centralized presentation and actionable registration status.
- Replaced public token lookup and arbitrary-token sends with private recipient inbox records and authenticated notification submissions. Added all four FCM kinds, one original expiry, native system actions, exact-event cleanup and preference enforcement.
- Added active/recoverable-match guards and consumed notification intents. Online taps open a friend profile. Existing game engine, host-disconnection hold and pick transport are preserved.
- Fixed token/session revision races, cross-account delayed posting, deferred popup cleanup, active toast cancellation, social cross-channel duplication and fail-open corrupt ledger behavior.
- Final cold-start inspection fixed session enrollment reading an absent `AuthRepository.activeInstance`: it now reads the persisted profile even when FCM launches the process without an Activity. Display-name fallback keeps enrollment schema valid.
- Handling a different invitation no longer clears the currently displayed invitation's replay state. Final notification posting holds only the account/presentation lock; friendship reads stay outside it to avoid repository lock inversion.
- Added account-scoped update/broadcast system alerts. Removed four unused custom notification/button resources.
- Added protocol-2 Worker modules, SQLite Durable Object inbox/outbox, auth and payload validation, recipient consent, idempotency, revocation, throttling and bounded retries. Service-account material is read from a Worker secret, never bundled.
- Configured the user-confirmed Web OAuth client ending `n1r5lp.apps.googleusercontent.com` in Android and Worker. Android OAuth package/SHA linkage still requires a real sign-in check.
- Added 31 Android notification regressions and 53 relay tests. Complete intermediate Android run passed 512 tests, lint 0 errors / 32 warnings and release assembly. A final build includes the last account-switch fix and isolated endpoint; its authoritative result is in `TEST_REPORT.md`.
- Actual SQLite/workerd smoke passed five checks. Google OAuth and FCM validation both returned HTTP 200 with `validate_only: true`; no notification was delivered.
- Read-only live configuration inspection found `bingo-fcm-relay` has an older `MATCHES` / `BingoMatch` namespace, migration `v1-match-authority`, version `2df41829-59de-480d-8f6a-e65b06043a18`. This checkout does not contain that controller. Prepared a separate `bingo-notifications-v2` service and updated only the notification endpoint to avoid overwriting it. Existing old-service retirement is a distinct open item.
- Android candidate is 1.4.2 / 43, signed with the existing certificate. Both phones must upgrade for protocol-2 notifications. Older unsigned guests and unchanged web notification clients have explicit migration limits in `NotificationHardeningReport-2026-10-16.md`.
- Final build/deployment status, checksums and artifacts are recorded in `TEST_REPORT.md`, `releases/ReleaseManifest.json` and the handoff. No real push, friend request, invitation or gameplay simulation has been sent/performed in this task.

Rollback source with the baseline branch in this same source folder; preserve current work before switching. Do not wipe application data. Backend rollback and APK upgrade/downgrade must be considered separately. The original APK remains in the backup directory.
