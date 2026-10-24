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
