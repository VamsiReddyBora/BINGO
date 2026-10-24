# Admin changes in the Android app — Build 71

Install Build 71 or later to apply these controls to the running app. The administration website alone cannot change an older installed APK. Build 71 is delivered as a Pages in-app update; the published v1.0.3 release (Build 70) remains unchanged.

## Expected behavior

- Set level to 100 in the player inspector: the active player's profile, dashboard and relevant profile views update through Firebase. A level-only edit adjusts XP to match, so the next normal game does not immediately drop the level. Subsequent games continue earning XP normally.
- Increase or reduce games played, wins and streaks: exact approved values replace the old values once. Future results continue accumulating. Validation requires wins no higher than games played and current streak no higher than best streak.
- Upload or remove a photo: the active account and public photo views observe the same cloud identity. Removal clears both cached photo sources. Search uses the registered username UID, avoiding an obsolete duplicate account.
- Add or remove a friendship: both Firebase rosters change. Existing social listeners update open friend/lobby views. Removed friendships retain a removal marker so old local backups cannot recreate them; explicit restoration remains possible.
- Remove a match record or clear history: deletion intent is stored with the Firebase profile and applied to local history, dashboard merges and subsequent uploads. Future matches remain allowed. History controls and career-statistics controls are separate.
- Remove an account: its UID deletion marker blocks Firebase writes. An authenticated updated app observes that marker, clears the saved account/history and signs out without creating a fresh backup of the deleted account.
- Notices and update publication use the existing app facilities. The console reports cloud/broker acknowledgement separately from phone display; it cannot certify delivery to a phone that is offline.

Connected clients apply changes as Firebase delivers them. Offline devices apply them after reconnecting. Timing depends on connectivity; this is not a guaranteed millisecond deadline. Gameplay still uses MQTT and stored data uses Firebase. No pick/turn/countdown/disconnection rule changes were made.

## Implementation

Admin changes carry a monotonic revision and versioned field edits in the existing Firebase profile. The Android listener applies only unseen edits to the active account. Private settings are not required for profile updates; local sound/theme/notification preferences retain their existing handling. Profile uploads read the policy in a Firebase transaction before writing, and coalesce network-failure retries. Match uploads check history deletion intent in their transaction. No new remote admin startup polling client is introduced.

## Verification and limits

Regression cases exercise the production profile callback, local persistence, restart/legacy restoration, account guards, history filtering and social repository callbacks. Admin service/browser tests use fictional backing records and real handler/passkey code. Real player records were not edited to manufacture evidence. Physical-device UI/timing acceptance is still required after installing Build 71.

The console inspects current peer-hosted room state; it does not fabricate controller packets or forcibly end a live match. Old APKs need updating to apply the new local behavior. Existing GitHub release tags, APKs and source archives remain separate.
