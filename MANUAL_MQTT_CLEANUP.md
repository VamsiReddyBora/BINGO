# Manual MQTT cleanup — Bingo 1.0.6, Build 85

This maintenance runs only when the owner requests it. No scheduled, periodic, startup or background cleanup is installed. It does not change the rules or close an active match.

## Admin console

Open Settings → MQTT record cleanup. Click **Preview cleanup** to inspect the observed record count and eligible categories. Preview does not delete records or change player profiles. Click **Clean old records**, confirm the count and approve with your registered device/passkey. The result reports records independently confirmed absent, changed records skipped, and any records recreated or still present. Use a fresh preview for another batch. An expired preview, unavailable broker, missing approval or failed recovery archive does not become an automatic retry.

## Local operator script

Node.js 22 or later is required. Keep the existing private deployment credentials outside this source tree in `~/.config/bingo/maintenance.json`, readable only by the owner. Its fields are `MQTT_URL`, `MQTT_USERNAME`, `MQTT_PASSWORD`, and `SERVICE_ACCOUNT_JSON` (the existing Firebase service account JSON object or JSON string). Use the existing EMQX TLS deployment. Do not paste credentials in a shell command, commit them or place them in a public website.

Preview from the source root:

```sh
node tools/cleanup-retained.mjs --dry-run
```

Run one manual batch:

```sh
node tools/cleanup-retained.mjs --apply
```

An alternate private configuration can be selected with `--config /private/path/maintenance.json`. Running without flags previews only. Each invocation deletes at most 200 records with at most 700 KB of archived payload data. If more eligible records remain, explicitly run again; there is no loop or scheduler.

## What qualifies

- Finished matches: final snapshots agree on an existing winner, all relevant records are coherent, and all recorded activity is older than 24 hours.
- Abandoned matches: normal five-minute inactivity has expired, at least 24 hours of additional quiet is established, every known participant is explicitly absent, and no saved manual pause exists.
- Lobbies: an explicitly closed session older than 24 hours; age alone never closes a waiting or playing lobby.
- Invitations: coherent invitation identity and a valid short expiry that has elapsed, plus a one-minute grace.
- Account mirrors: only exact canonical Firebase duplicates or an explicitly deleted identity whose Firebase profile is absent. Unknown identities, unique photos/keys and conflicting statistics remain untouched.

Presence, friends, profiles, statistics, saved history and other unrecognized topic families are not deletion targets. Cleanup does not award wins, surrender a player or alter game clocks. The Android app now sends redundant legacy account announcements live without retaining new copies; Firebase remains the account store.

## Recovery and concurrency

The script saves a private local before snapshot, Firebase comparison, plan and result under `~/.local/share/bingo/mqtt-cleanup/`. Before either interface deletes, it creates and reads back a private Firebase `mqttMaintenanceArchives` record containing every selected original topic and payload. Admin cleanup keeps this Firebase archive rather than downloading personal data into the browser. These archives are not publicly readable and are not automatically erased.

The executor rereads current data, skips changed groups, checks the group before each exact-topic deletion, waits for broker acknowledgement, and uses a new subscription to confirm absence. Commands are deleted before final snapshots so a large match remains verifiable in a later batch. MQTT does not offer conditional delete transactions: a publisher can still recreate a record concurrently. Such records are reported separately; they are never counted as a confirmed deletion. Closing old app versions during maintenance avoids recreation of redundant retained account mirrors.

If a connection fails partway through, the verified archive remains. Some acknowledged deletions may already have occurred; the operation is not reported as success. Preview again and inspect actual results. Restoring an archive is a deliberate operator action; do not blindly replay stale gameplay over a live match.
