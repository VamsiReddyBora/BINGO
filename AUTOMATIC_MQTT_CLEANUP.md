# Automatic retained-message maintenance

Authorized on 31 October 2026. This supersedes the earlier manual-only maintenance policy. Android gameplay, invitations, turns and rejoin rules are unchanged; no new APK is required.

## Usage display

Admin → Settings → MQTT record cleanup shows **Bingo retained messages / 2,000**, a usage bar, the observation time and the automatic job's last result/next check. “Refresh usage” is read-only. A disconnected broker shows an unavailable or clearly marked last-known reading, never a fabricated zero.

The counter subscribes to **all `bingo/#` retained topics**, including legacy records, presence, account mirrors and records that are deliberately protected from cleanup. It does not confuse the cleanup candidate count with storage usage. Duplicate deliveries count once; non-retained heartbeats/probes do not count; large/non-JSON retained messages still count. The subscription uses the existing QoS 0 TLS/TCP snapshot path and requires successful subscription and a completion fence. A broad QoS 1 subscription is deliberately avoided: Serverless's 1,000-entry subscriber queue can truncate a retained burst when storage contains close to 2,000 records.

This deployment rejects a broker-root `#` subscription. Consequently the counter is explicitly scoped to Bingo, with the broker's shared 2,000-record capacity as the denominator. Other applications or topics hidden by broker permissions can use additional capacity. The EMQX console remains authoritative for a broker-wide total. Counts are timestamped observations, not an atomic transaction with concurrent publishers.

## What the service removes

| Data | Required evidence and retention |
| --- | --- |
| Finished-match move receipts | Every saved GAME_SYNC is finished and agrees on a valid winner; the newest match activity is at least 10 minutes old. Only command/outcome/exit/accepted_exit receipts are compacted; final board snapshots remain. |
| Finished-match final boards | Same agreement, with all match activity older than 24 hours. |
| Abandoned unfinished match | Additional 24-hour quiet period beyond normal recovery expiry, no saved manual pause, and every participant explicitly absent. Presence records are read-only deletion dependencies. |
| Closed lobby | Explicit CLOSED/terminal metadata older than 24 hours, matching room incarnation and no protected match dependency. A WAITING/PLAYING lobby is never closed just because it is old. |
| Invitations | Valid recipient/event identity, valid short expiry and an additional 60-second grace. |

Unattended maintenance does **not** delete account mirrors, profiles, friends, statistics, match history, global presence, broadcast configuration, unknown namespaces, corrupt records or ambiguous/conflicting match data. The existing manually approved canonical-account cleanup remains available separately.

Match IDs are compared as exact signed 64-bit JSON tokens. Negative IDs and values above JavaScript's safe-integer range work without rounding; mismatched, duplicated, nested-only or out-of-range IDs never authorize deletion.

## Execution and recovery

- The existing admin Durable Object alarm checks every **15 minutes**, independent of any browser or phone. There is no workstation cron, new gameplay server or HTTP request per pick.
- One run handles at most **200 records / 700 KB of archived payloads**. A productive run with remaining eligible backlog schedules the next batch one minute later.
- The existing serialized mutation queue prevents overlapping cleanup runs and conflicting manual mutations. Repeated alarms consult persisted next-run state.
- Every selected batch is archived privately in Firebase `mqttMaintenanceArchives` and read back before deletion. Raw 64-bit JSON payloads are preserved exactly.
- The service rereads eligibility, compares whole-group fingerprints and watches live changes before individual retained clears. Returned players, new commands or changed results cause affected records to be kept.
- Retained empty publishes require broker acknowledgements, followed by an independent absence check. Acknowledgement alone is not reported as confirmed deletion.
- Broker/archive failures preserve the recovery archive and schedule a fresh later attempt. The UI distinguishes failures, running jobs and confirmed results. Usage refresh failure does not undo or conceal the recorded cleanup result.
- Unknown or protected data is never force-deleted just to reach a quota target. An exceptionally large number of active games can still fill a fixed broker quota.

Set the deployment variable `MQTT_AUTOCLEAN_ENABLED` to `"false"` and redeploy to stop automatic cleanup. Manual preview/run and their fresh passkey approval remain available. Keep the existing `control-v1` Durable Object, its storage and secrets during deployment/rollback.

MQTT 3.1.1 has no conditional delete transaction. The rereads and change guards reduce races but cannot provide an atomic delete against a publisher acting at exactly the same instant. A recreated record is reported as present rather than counted as a successful removal.

## Verification and future reviews

- MC-001: exact signed Kotlin Long seed recognition; adjacent rounded IDs rejected.
- MC-002: finished receipt compaction, recent/unfinished/disagreeing boards protected, exact archives and retained final boards.
- MC-003: actual AdminControl alarm path, default-off behavior, restart/duplicate serialization, archive failure and bounded backlog scheduling.
- MC-004: actual MQTT codec/inventory, all Bingo families, duplicate/large/live packets, denied subscriptions, stale cache, authenticated API and browser usage display.

Run `npm test` and `npm run test:browser` in `admin_service`. Real broker/Firebase verification and deployment IDs belong in the private operator job report, not the public source. No emulator/physical-device gameplay test is claimed for this service-only change.
