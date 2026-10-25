## Admin website usability update

- Restore a server-validated tab session on refresh; preserve expiry, Lock and per-action device approval.
- Pair app version/build correctly; make missing status and last activity clear.
- Use pure black in dark mode; retain compact desktop/mobile controls.
- Add build/sync filters, clickable status counts, @username search and precise profile-change confirmation.
- Age manual snapshots without polling; preserve drafts and selected build filters.
- Keep Android Build 73, Pages APK/update feed and GitHub Releases unchanged.

## Build 73 — legacy migration recovery preview

- Add create-only, verified legacy-account migration planning and transactional import.
- Recover imported account statistics/photo/canonical username before a local profile upload.
- Persist a one-time recovery marker; preserve later games, admin edits and existing accounts.
- Keep original legacy backups and all current gameplay behavior.

## Build 72 — Pages preview

- Recover live admin edits when the app Google account and Firebase storage session differ.
- Start the public profile observer before storage authentication; retry on failure and foreground return.
- Confirm exact admin revision only after durable local application by the active account.
- Show cloud-save and app-application status separately; preserve unsaved forms on receipt refresh.
- Compact navigation, directory and profile controls without removing existing actions.
- Preserve existing gameplay rules and GitHub release assets.

# Version 1.0.3 — Build 70

- Offer Reconnect or Later on Home when notification Google authorization needs approval. Preserve the current account and wait for startup and other dialogs.
- Repair notification credential renewal, token registration and friendship synchronization, with delivery-stage diagnostics.
- Require 30 minutes of continuous absence for another friend-online alert. Display the online sentence once.
- Use the approved EMQX broker warning and preserve its existing connection grace and tab state.
- Preserve gameplay rules, multiplayer, Nearby, AI, friends, photos, startup and saved-result behavior. Publish a separate signed APK and clean source archive.

# Version 1.0.2 — Build 67

Fix startup waiting on saved-match room lookup, gate automatic startup dialogs on real UI readiness, show completed saved results without live rejoin, and prevent returning to a closed or unresponsive post-match lobby. Temporary host absence remains recoverable. Restore the EMQX Home-note wording and preserve its outage state across tab switches. Preserve all existing gameplay and notification policies.

# Changelog

## 1.0.1 — Build 66

- Friend-online alerts require one continuous hour of absence before a new return announcement. Short app switches and ordinary reconnects remain silent.
- Persist availability and announcement identities across app restarts. Retried deliveries reuse the original event and expiry.
- Increase online-alert delivery time to two minutes for updated APKs, use high-priority FCM, and preserve compatibility with older receivers.
- Present valid FCM invitations immediately, followed by inbox synchronization. Preserve invitation actions, expiry and duplicate protection.
- Identify Android notification categories that are blocked and include private delivery stages in Copy app diagnostics.
- Keep existing gameplay rules and earlier fixes. Publish this version with its own APK/source archive; retain the previous release separately.

## 1.0 — Build 65

Previous published baseline: https://github.com/VamsiReddyBora/BINGO/releases/tag/v1.0.0
