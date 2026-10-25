# Current admin website update

Read ADMIN_USABILITY.md. Server-validated tab session restoration replaces forced logout on every refresh; server expiry/revocation and fresh passkey approval for every mutation remain. Versions are paired by source; unknown status is not treated as offline. Directory filters, profile context, change confirmation and dark/mobile layout are improved. The website updates independently of the unchanged Build73 APK, update feed and GitHub Releases. No Worker deployment or production player mutation is required. Current website verification is recorded in SOURCE_MANIFEST.json; Android validation remains the previous unchanged Build73 run.

# Agent handoff — Build 73 legacy account recovery preview

Read SimplifiedGameRules.md, ADMIN_APP_SYNC.md and MIGRATION_RECOVERY.md before changing this source. Preserve intentional gameplay and every previous passing regression. Main contains clean current source; GitHub Releases and their existing tags/assets are unchanged. This APK is delivered through the existing Pages update feed.

## Current change

The one-time migration planner accepts only verified readable original backups, excludes existing Firebase identities and deletion markers, and uses create-only atomic account transactions. Profiles, statistics, photos, saved matches and verified friends use existing Firebase collections. Private source archives retain recoverable legacy data without publishing it. Missing legacy username lookup aliases may be restored after verifying their original owner; source backup blobs remain untouched. Live plans, credentials, backups and account results stay in the private operator job.

LegacyProfileRecovery is shared by AuthRepository's existing profile callback and FirestoreSyncManager's guarded upload. It applies an imported baseline once per matching original account, persists a recovery marker, preserves newer progress and administrator edits, and keeps the canonical recovered identity/photo when a pre-migration backup arrives late. Migration uses adminRevision zero; it is not an administrator edit. A Guest account still needs its original saved identity, and older Play Games identities are not reassigned to a new Google sign-in.

## Existing behavior to preserve

The admin console and authenticated Worker retain their current compact layout, actions, passkeys, policies and deletion markers. ProfileSyncStartup observes public profiles before storage-session retries. A separate device receipt acknowledges a durable administrator edit; an upload alone is not proof. CloudAdminPolicy retains explicit reductions and photo/history removals. Local preferences and gameplay, MQTT invitations, turn timing, recovery, notifications and winner rules are unchanged.

## Verification

SOURCE_MANIFEST.json records actual complete native and clean-source case counts, preservation of previous passing case identities, lint and signing checks, and service/browser results. Twelve new permanent client cases cover migrated baseline recovery, restart, replay, canonical identity, late backup delivery and the shared recovery guard. Two cases failed against the previous production callback. A strengthened canonical-username assertion also exposed a real late-backup race; its failure was retained and a deterministic restore-path regression added before the final passing runs. Migration service tests exercise planner, executor and REST preconditions with fictional data. Existing browser checks exercise the actual console and protected handlers with virtual WebAuthn, not real player mutations.

Actual live import verification is recorded privately and is separate from automated fictional tests. Returning-phone acceptance, rendering and delivery latency remain physical-device checks. Never infer physical confirmation from unit tests or manufacture a device receipt.

## Build and publication

Version name remains 1.0.3 and version code is 73. Supply signing, Firebase and broker/storage build inputs privately using the existing templates; never commit credentials, device addresses, production account lists or local logs. Keep intentional support contact links. Public builds do not copy APKs to personal devices. Do not deploy a new Worker for this standalone import, reset admin state, introduce a referee, or create a GitHub release without the owner's approval.
