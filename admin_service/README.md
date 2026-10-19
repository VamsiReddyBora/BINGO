# Bingo player administration

The console lives in `../admin/` and is published at https://vamsireddybora.github.io/BINGO/admin/. Its authenticated service is `bingo-admin-control`; it is separate from the existing notification relay. Gameplay picks, turn timing, match recovery and winner selection do not go through this service.

## Owner controls

- Verified player directory; live MQTT presence, detailed activity, room, observation age, app version and provider. Missing observations are **Unknown**, not an invented offline/online result.
- Search, status/provider filters, sorting, table/cards, pagination and filtered CSV export.
- Fresh player inspection; edit display name and statistics with revision/ETag conflict checks.
- Upload a resized JPEG/PNG/WebP photo, or explicitly remove it without falling back to an older photo.
- Add/remove friendships on both sides, preserving the app's removal/restoration markers.
- Inspect cloud match history; delete a record, clear old history or reset statistics separately.
- Permanently remove an account. A minimal deletion marker remains intentionally; linked data is cleaned in bounded, retryable batches.
- Inspect room observations; close a waiting lobby. Active matches cannot be purged as stale lobbies.
- Global/direct announcements and update-manifest management, with explicit confirmation before publication.
- Password rotation, revocable sessions, operation status and an audit trail.

## Data and synchronization contract

`AdminControl` is a Durable Object containing sessions, versioned administrative policies, deletion markers, operation outbox and audit records. Credentials and the Google service-account key are Worker secrets. The static page contains neither an administrator password nor broker credentials.

An accepted mutation is first stored with its audit record. `SYNCED` means the required backing-store/broker operations completed. `PENDING` means the change is durable but some synchronization/cleanup remains; the alarm retries it and the owner can retry from **Audit & sync jobs**. A broker acknowledgement does **not** prove that an individual phone displayed an announcement.

Profile source application and device receipt are different things. `sourceApplied` only describes the stored profile revision; it does not claim that a phone received the change. Client profile corrections apply once so future games can accumulate new statistics normally. Photo removal uses explicit null fields. Match-removal markers prevent an old backup from merging removed records back into an updated app.

The first directory load reads the index and broker observations, then refreshes at most six full profiles per request to stay within Worker limits. Other rows show clearly labelled registry/cached/unavailable data until refreshed; opening a player requests fresh data. A directory outage never authorizes a repair write, hardcoded player insertion or success toast. Only verified directory membership contributes to the total.

The Android integration is `AdminControlClient`, initialized by `BingoApplication`. Background account checks run approximately every 15 seconds, persist policies locally, block removed accounts, correct social/profile backups and filter deleted match history. They do not run in the move/turn packet handler. Explicit owner account removal signs the updated app out without backing that account up again.

## Compatibility and limits

Install the accompanying updated APK to get app-side enforcement. Previously released APKs do not understand these administrative policies. Firestore deletion guards block a removed UID from rewriting its own profile/history, while the console always excludes its tombstone even if legacy directory data reappears.

The older app architecture still has public KeyValue storage and broker credentials inside older APKs. This change cannot revoke every old copy, erase an already downloaded photo on an offline phone, or turn that legacy storage into authenticated storage. Moving all social/account writes behind authenticated APIs requires a separate client migration. Do not tell an owner that all old APKs or all offline caches are already controlled. Retain minimal deletion/removal markers: physically deleting the markers would permit resurrection. Unknown orphaned historical bins cannot be discovered from an absent pointer.

Public endpoints expose only administrative policy fields, minimal deletion identities and profile photos; account email, friends, history, audit data and write operations require an administrator session. Existing Firestore public-read/write policy for **active** accounts is preserved for compatibility; this is not a complete account-security migration.

## Development and verification

```sh
cd admin_service
npm ci
npm test
# Uses an existing Playwright install by default in this workspace:
node test/browser.mjs
# Or set PLAYWRIGHT_MODULE to your Playwright module path.
```

Browser tests render the production frontend and call the actual `AdminControl.fetch` implementation with fictional storage/broker dependencies. Legacy adapter tests exercise real request construction, profile mirrors and checkpointed cleanup with intercepted transports. They do not modify production player accounts. Android tests exercise the actual policy reducer and `AccountSessionManager.backupUserDataSync`, followed by the existing unit suite. Emulator/physical-device tests are not claimed.

## Deploy and recover

```sh
wrangler deploy
wrangler secret bulk /private/path/secrets.json
```

Secret keys: `ADMIN_CREDENTIALS` (operator, salt, PBKDF2-SHA256 hash, 100,000 iterations), `MQTT_USERNAME`, `MQTT_PASSWORD`, `SERVICE_ACCOUNT_JSON`. Generate a unique long password outside the repository; never use an example/default password. Sessions expire after 30 minutes idle or 12 hours total. Settings changes the password and revokes all sessions.

`scripts/firebase-rules.mjs` backs up the deployed rules privately; `--deploy` compiles and deploys the supplied deletion guards. Never commit its private backup directory. Keep public profile queries and large friend-list batch writes compatible.

Publish only the contents of `../admin/` into the `admin/` directory of the existing `gh-pages` worktree. Do not replace the companion game website or unrelated root assets. The admin service worker only deletes caches beginning `bingo-admin-` and never caches authenticated API responses.

Rollback refs created before this work: `backup/admin-main-20261019` and `backup/admin-pages-20261019`. Review `IMPLEMENTATION_LOG.md` before reverting. A frontend rollback does not erase Durable Object policies or deletion markers. Never delete that storage as a rollback shortcut. Preserve real players' later data and pending operations.
