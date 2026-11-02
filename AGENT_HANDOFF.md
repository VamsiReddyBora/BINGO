# Agent handoff — Bingo 1.0.9, Build 109

The Android baseline is the clean v1.0.9 release source. Preserve previous releases, their tags, source ZIPs and APKs. Read SimplifiedGameRules.md before gameplay work.

## Features included in Version 1.0.9 (Build 109)

- Relocated Chat button in the waiting lobby into the Players in Lobby card, directly after the Sync button.
- Full-width action controls for Start Match and Ready / Not Ready status.
- Host controls to toggle and add bots to multiplayer waiting lobbies.
- Bot speed selector in lobby: switch between Low (1s), Medium (1.5s), and High (2s) [L, M, H].
- Guaranteed permanent bot removal across cloud metadata and local roster for both host and guest players.
- Fixed chat leak where bot canned messages were triggered during multiplayer under player names.
- Hardened ingress spoofing validation across online and LAN P2P sessions.
- Preserved existing Bingo gameplay, MQTT/Firebase design, invitations, notifications, cleanup and result animations.

## Build and verification

Use JDK17 and Android SDK34. Supply google-services.json, deployment values and optional release signing inputs privately as described in README.md/local.release.properties.example. No private credentials, player records, signing keys, device addresses or local logs belong in the public source.
