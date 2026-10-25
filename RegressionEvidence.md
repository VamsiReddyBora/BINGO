# Admin sync repair — regression evidence

## ADMIN-SYNC-001 — restored app account prevented profile observation

Production paths: AuthRepository.startCloudSynchronization → ProfileSyncStartup.beginProfileSynchronization/openProfileStorageSession → FirestoreSyncManager.startRealtimeProfileListener.

Cause: the old authentication-first gate rejected a local Google app account when Firebase retained an anonymous session. The public Firebase profile is readable under the existing rules, but its observer was never attached. A saved admin edit could consequently remain unapplied.

Executed before proof: the original startup order was extracted unchanged into the shared production orchestration and six startup scenarios were run. Five failed and one passed. The repaired orchestration starts observation first, retains a valid storage session, preserves guest binding and retries account-scoped failures. The final suite and all previous passing case identities are checked before publishing. Local job evidence: startup-before-tests.log (six cases, five failures), startup-after-tests.log, android-package.log, clean-source-tests.log, admin-tests.log and browser-final/results.json. The source manifest records final counts.

Permanent cases: ProfileSyncStartupTest (observer order, failed authentication, anonymous storage, token fallback, guest binding); AuthRepositoryTest.adminRevisionIsAcknowledgedOnlyAfterDurableApplicationForTheSameAccount.

## ADMIN-SYNC-002 — upload marker was not app-application confirmation

Production paths: FirestoreSyncManager.acknowledgeAdminRevision, AuthRepository.applyCloudAdminSnapshot/profileAdminAppliedRevision, admin_service/model.profileSyncState, worker.player and admin/app refresh.

The profile upload no longer writes the device receipt. A separate transaction confirms the exact server revision only after durable local application by the active account. Old upload markers and old builds are insufficient. Unit tests check revision/account guards; service tests check old receipts, stale data and receipt-only metadata; browser tests exercise cloud-save → waiting → app receipt and preservation of an unsaved form.

Before-proof limit: the false-confirmation state is reproduced with old marker fixtures in the real status projection. No physical phone receipt was manufactured, and no real player records were edited for evidence.

## UI and acceptance limits

The actual console and protected handlers are exercised in browser tests with fictional records and signed virtual WebAuthn. Desktop/mobile and light/dark screenshots are inspected. Existing controls and prior checks remain. This does not certify real Android rendering, radio delivery or every device state. The owner will install the Pages preview and verify admin-to-phone behavior in the morning. No GitHub release is created in this repair.


## LEGACY-RECOVERY-001 — imported profile could be replaced by initial local defaults

Affected production paths: AuthRepository.applyCloudAdminSnapshot and FirestoreSyncManager.syncUserProfile. Before proof: two permanent AuthRepository migration cases fail against the previous callback with a verified migration marker and adminRevision zero. After proof: shared recoverLegacyProfile is called by both the callback and guarded upload; account-scoped durable marker prevents replay. The full suite, source-copy verification and source manifest record final evidence. No regular profile/gameplay branch requires migration metadata.

Permanent tests: migratedFirebaseProfileRestoresStatisticsBeforeAnyLocalUpload, migrationDoesNotReplayOverANewGameOrAnAdminReduction, migrationMarkerSurvivesRestartWithoutReplayingOldStatistics, LegacyProfileRecoveryTest, and admin_service/test/migration.test.js. The planner/REST executor tests include existing data, deletion and concurrent-write checks, idempotence, older history and private archival fidelity. Actual live data verification is separate from fictional automated tests. Physical returning-device acceptance is not claimed from unit tests.

## LEGACY-RECOVERY-002 — late legacy restore replaced the recovered identity

Production path: AuthRepository.restoreCloudUserData after applyCloudAdminSnapshot. A full suite run with a strengthened canonical-username assertion failed because a pending pre-migration backup reset the saved username. The failure is retained in the private operator evidence. The restore path now retains the migrated identity and photo when the backup lacks its recovery marker, while preserving existing statistics/history merging. Permanent deterministic regression: AuthRepositoryTest.latePreMigrationBackupCannotReplaceRecoveredIdentityAndPhoto. The canonical cached username assertion remains enabled in migratedFirebaseProfileRestoresStatisticsBeforeAnyLocalUpload. Final results are recorded in SOURCE_MANIFEST.json.
