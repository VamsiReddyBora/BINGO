# Legacy account migration and recovery

This is a one-time storage migration, with a small app recovery guard. Gameplay, MQTT packets, turn rules, notification thresholds and online authority are unchanged. The admin directory still reads Firebase only. No legacy accounts are merely inserted into the display.

## Import rules

The planner consumes full readable original backups, canonical username mappings, current Firebase profiles and deletion markers. Verify both the username and stable account identity. Keep original Google, Guest and older Play Games identities; never invent or link Google ownership. Existing Firebase profiles and deleted accounts are excluded. Flag conflicts and unverifiable references instead of creating empty records. Old registry/archive entries are discovery hints and require a verified live backup before import.

The Firebase transaction checks current profile, username reservation, UID/name tombstones and existing child records. Every write uses an absent-document precondition. Import an account and its linked records atomically. Repeat imports skip an already imported identity without overwriting subsequent activity.

Public profile fields, stored statistics, photos, historical last-seen time, match records and verified friend links go to their existing collections. Email, settings and a lossless JSON archive of the full original backup/legacy references go to private records. Older WIN/LOSS history is mapped to the existing match schema. Preserve the full original record privately and leave the source backups untouched. Restore a missing legacy username-to-backup lookup only after verifying its original owner; existing lookups are preserved. This is a compatibility bridge for current/older clients, not a second copy of profile data. The old lookup service has no atomic create-only API; rechecks/readback reduce but do not eliminate its concurrent-write window. Missing historical records are not reconstructed from career counters. Expired invitations and old notification/session credentials are not activated.

## Client recovery

Imported profiles have a versioned legacyMigration source marker; adminRevision remains zero. This avoids representing migration as an administrator edit. recoverLegacyProfile is shared by the existing profile callback and guarded upload transaction. It hydrates a matching original identity once per saved account, keeps newer cached progress, preserves intentional administrator revisions, and stores a per-account legacyMigrationId durably. A later game or administrator reduction is not replayed over by the imported baseline. A late pre-migration backup cannot replace the recovered canonical username, display name or photo. Normal profiles never enter this path.

Use the recovered canonical username for the username index. Persist the recovery marker through restart and reauthentication. Existing public Firebase match-history and friend streams consume the imported records; local theme/sound/notification preferences retain their existing precedence. Guest recovery requires its original saved identity; older Play Games IDs are not automatically converted into a Google account. A database import cannot prove ownership of an unrelated new sign-in.

## Evidence and operation

Two regressions failed against the original production AuthRepository callback before the recovery guard and pass afterward. Permanent regressions cover first recovery, replay protection, newer progress, administrator changes, identity/provider mismatch, canonical username, guest identity, restart and an actual late pre-migration restore. A strengthened canonical-username assertion exposed that late restore race; the original failure is retained in operator evidence and a deterministic production restore-path regression verifies the correction. The migration planner, transaction executor and REST preconditions are tested directly; tests never mutate production data.

The source manifest records actual suite and lint counts. Live backups, inventory, plan, write audit, field verification and rollback references remain in the private operator job. Do not publish player lists or service-account files. The user must install the Pages recovery build for the new client path; actual returning-phone/OS behavior remains physical acceptance. Keep GitHub release assets and original backups unchanged until that acceptance.
