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
