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
