# Bingo Command Center

Administration at https://vamsireddybora.github.io/BINGO/admin/ with a separate authenticated `bingo-admin-control` Worker. This console does not route gameplay or replace MQTT/Firebase.

## Controls

- Overview with verified player counts, activity, rooms and connection state.
- Firebase player directory: search, active-first/name/level/games/wins/win-rate/recent sorting, status/provider filters, pagination, table/cards and filtered CSV export.
- Fresh player inspection: display name and statistics, photo upload/removal, both sides of a friendship, per-player match history, record deletion, statistics reset and account removal.
- Storage audit: duplicate or unmatched username identities are visible, never automatically erased. The username registry selects the registered identity. Explicit duplicate removal preserves the registered player, index and username-based data.
- Current MQTT v7 room metadata with compact field aliases, host/roster/session/revision inspection, search/status/population/recent sorting. Retained observations have an age; they do not prove the latest pick. Active/current-controller rooms are read-only. Only a verified legacy waiting lobby can be closed here.
- Recent Firebase player-result records, with search/outcome/mode filters. A saved result belongs to one player; these are not invented global unique-match totals.
- Global/direct notices for startup and/or gameplay, explicit withdrawal, durable retries and a separate broker acknowledgement. A PUBACK does not prove that a phone displayed a notice.
- In-app update publishing from a compiled APK (maximum 12 MiB) or an existing repository release/Pages URL. Check version/build/signing identity before approval. The server checks download accessibility, size and SHA-256, stages the APK, commits the stable/unique Pages files and metadata, then verifies the legacy fallback. It never rewrites old GitHub Releases, source tags or Android source. Compilation and GitHub release creation are separate developer actions.
- Passkey administration, recovery, session revocation, operation retries and audit history.

## Access and data

The existing owner password is for first passkey enrollment or recovery with a saved recovery code. Every change requires a fresh server-checked WebAuthn approval bound to that exact action. Devices choose fingerprint, face, screen-lock PIN or a security key; biometric data is never sent to Bingo. Existing sessions, passkeys, policies, deletion markers and audit records must survive deployments.

Firebase is authoritative for current profiles and friends. Reads never repair the directory or restore obsolete legacy accounts. Failed reads are unavailable/stale, not confirmed offline. Pending writes are shown as pending until their required operations finish. Photo removal uses explicit nulls. Account cleanup is bounded and retryable; a minimal UID tombstone remains to prevent Firebase rewrites. Duplicates are not automatically deleted.

Android Build 71 adds live application through the existing Firebase profile listener. Versioned admin edits apply once to the active account and its local cache, including lower statistics and explicit photo removal. Upload transactions read the latest policy before writing; stale backups cannot reverse a newer edit or recreate removed history. A level-only edit adjusts XP to preserve that level after the next game. Friends, profile dialogs and leaderboards observe the same revisions. Firebase roster/profile listeners carry friend additions, removals, restorations and career resets to open screens. A signed-in installation observes its own deletion marker and clears a removed account without backing it up again.

Online changes arrive through Firebase when connectivity permits. Offline players receive them after reconnecting. Build 70 and older require an APK update for this application behavior; a website cannot patch an installed APK. Deletion blocks Firebase writes, but administration does not invent peer-host controller packets or terminate an already running match remotely. No polling admin startup client is restored.

## Configuration and development

Use Node 22 or later. From this directory run `npm ci`, `npm test`, and `node test/compatibility-probes.mjs`. Browser checks need Playwright installed or `PLAYWRIGHT_MODULE` set to its module. Run `npm run test:browser` with `ADMIN_EVIDENCE` pointing to a private evidence directory. Browser mutation fixtures use real production UI/handlers and signed virtual-authenticator assertions, with fictional backing stores.

Worker secrets: `ADMIN_CREDENTIALS`, `MQTT_USERNAME`, `MQTT_PASSWORD`, `SERVICE_ACCOUNT_JSON`, `GITHUB_TOKEN`, and deployment-specific `LEGACY_APP_KEY`. Never commit credentials, tokens, real service-account JSON, recovery codes or owner access files. `keep_vars` preserves existing server variables; a new deployment must configure its own private values. The admin Durable Object ID and migration remain unchanged.

Publish only `admin/` under that directory on gh-pages. Preserve game assets, APKs, version.json and existing release tags. Source changes go to `admin/` and `admin_service/` on main without staging unrelated app changes. No APK update is needed to open the console. Build 71 or later is needed for the corresponding live Android edit handling. The service worker only owns bingo-admin-prefixed image caches and does not cache authenticated data or code.

Deployments, player changes, notices and releases are separate operations. Testing must not send real broadcasts, erase real records, enroll production test keys or publish a fake update. Retain a private deployment/source/feed backup before publishing. A source rollback must not delete Durable Object storage or tombstones.
