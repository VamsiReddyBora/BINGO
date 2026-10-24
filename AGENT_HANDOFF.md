# Agent handoff — Build 71 admin synchronization

Read SimplifiedGameRules.md and ADMIN_APP_SYNC.md before further changes. Preserve previous regressions and intentional gameplay behavior. Main now includes the updated admin console and Android Firebase application of owner edits; the v1.0.3 GitHub release/tag stays Build 70.

## Relevant paths

- admin/: responsive Command Center, player directory/inspection, storage audit, rooms and match records, passkey-approved broadcasts and Pages update publishing.
- admin_service/: existing authenticated Worker/Durable Object and Firebase/legacy compatibility adapter. Preserve the existing Durable Object name, namespace, credentials, passkeys, policies and deletion markers on deployment.
- CloudAdminPolicy: versioned field edits, one-time local application, public projection and history deletion filtering.
- FirestoreSyncManager: existing profile listener receives admin edits independently of private settings, guarded profile/history transactions, coalesced network retry, UID removal marker listener, live friend roster/profile listeners and canonical username photo lookup.
- AuthRepository: account/session-generation guards, local application without upload feedback, per-UID revision/history filtering, legacy restore barrier, removal without backup, observable dashboard history updates.
- FriendCareerStats/FriendsRepository and PlayerProfileData: reductions and explicit photo removal override older caches; normal results continue accumulating.

A level-only admin edit sets compatible XP, preventing the next game from recalculating level 100 back to 1. History removal does not silently change career statistics. Local theme, sound, notification preferences and all gameplay timing/turn/recovery/winner behavior are preserved.

## Validation

SOURCE_MANIFEST.json records executed case counts, signing identity and source verification. Native tests include 20 new production-path regressions, with previously passing cases preserved. Browser tests exercise the actual UI and authenticated Worker using fictional stores and signed virtual WebAuthn assertions. No real player edits, test passkey enrollment, broadcasts or fake result records were performed. Physical-device acceptance of Build 71 remains to be done by the user; unit/browser results do not certify Android rendering or delivery time.

The release compiler initially exhausted its previous 2 GiB heap. Packaging was retried with a larger heap, one worker and no persistent daemon; that is a workspace build-resource adjustment, not an app behavior change.

## Publication and privacy

Pages carries Build 71 APK/update metadata; GitHub Releases and earlier tags/assets stay unchanged. Main contains current clean source. Build inputs (Firebase file, broker/storage/signing credentials) are supplied privately using the existing templates; never commit personal device addresses, keys, token files, logs or production account data. Keep intentional in-app support contacts. Public builds do not copy APKs to personal devices.

No controller coordinator or continuous remote referee is introduced. Current room inspection is read-only for active/current-protocol rooms. Offline devices receive admin changes only after connectivity and updating the installed APK. Do not restore the removed startup admin polling client.
