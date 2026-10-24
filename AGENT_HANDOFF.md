# Agent handoff — Bingo 1.0.3, Build 70

This is the clean source for the v1.0.3 release. Main and tag v1.0.3 identify this same tree; earlier release tags/APKs/source archives remain separate.

Read SimplifiedGameRules.md before gameplay changes. Preserve intentional behavior and previous regressions. New repairs need production-path verification; do not bypass room rules, introduce additional gameplay transports or change MQTT messaging / Firebase data roles.

## Notification changes

- NotificationGoogleAuth/NotificationAuthRecovery reuse the existing same-account Google identity. Foreground approval repairs notification credentials without signing the player out. Workers never start Google UI; bounded HTTP renewal, token replacement registration and account-generation guards remain in place.
- RootNavGraph adds a Home-only NotificationReconnectPrompt. It waits for a rendered foreground Home and existing updates/announcements/invites/joining. Reconnect and Later are explicit choices. Saved decisions/results are scoped to the Activity/account/process so rotation does not nag or lose a matching Google result, and a fresh launch can remind again.
- NotificationOnlineConfig.OFFLINE_INTERVAL_MS and relay FRIEND_ONLINE_INTERVAL_MS both use 1,800,000 ms (30 minutes). Change both together if policy changes. Invitation expiry, delivery lifetime, heartbeat/gameplay timers and friendship authorization are separate and unchanged.
- NotificationPresentation provides one online sentence, with no duplicated Android title/body. Other notification kinds retain their content/actions.
- The exact Home warning is: Note: Unable to connect to the EMQX MQTT broker. Online invitations and match synchronization may be interrupted. Please report this through Developer Note.

## Validation and acceptance

The preceding Build 69 native suite passed 1,142 cases with eight optional skips; 77 relay cases passed. Release verification results for Build 70 and this cleaned source are recorded in SOURCE_MANIFEST.json. The user confirmed working match/online notifications after Google reconnection. No new real-phone rendering certification is implied by JVM tests. Check Home prompt/Later/rotation, 30-minute returns and notification layout on physical devices.

A finished saved checkpoint remains a read-only result; intentional host lobby departure prevents stale lobby reentry. Existing temporary host hold, game rules, result/tie-break behavior and Nearby recovery limits are preserved. Whole-host-process-kill Nearby recovery was not newly added by this release.

## Build and privacy

See README.md. Supply app/google-services.json, local.release.properties and any production signing/deployment inputs privately using the included templates. Credentials, device addresses, account data, logs, local tools and signing keys are excluded. Intentional in-app developer contact links remain.

Do not restore old coordinator/review-loop artifacts into the release source. Keep fixes covered, compare passing case identities, and distinguish rendered device results from source/policy checks. Public source does not copy APKs to personal devices.
