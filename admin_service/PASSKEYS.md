# Device verification and admin redesign

Implemented 19 October 2026. Changes are limited to the admin website and its service. The game engine, notification relay, APK and release manifest are outside this change.

## Owner setup

1. Open https://vamsireddybora.github.io/BINGO/admin/ in a browser supporting passkeys, on your own phone or computer with a screen lock.
2. On first setup only, enter the existing administrator password. This opens a five-minute setup session which cannot inspect players or change game data.
3. Name the passkey, choose **Create my passkey**, and complete your device prompt.
4. Download the eight recovery codes and store them privately away from that device. Each works once with your recovery password. They are shown only when created; the server stores hashes.
5. Future visits use **Unlock with passkey**. Each save, deletion, publication, friend/photo change and other mutation asks for a new device verification. Reading and filtering do not prompt. Lock ends the server session; refreshing the page also locks the browser interface.
6. In **Settings & security**, add a backup passkey, remove a redundant key or replace recovery codes. The final registered key cannot be deleted. Removing a key locks all sessions. Recovery replaces old keys and locks all sessions.

The browser and operating system choose fingerprint, face verification, screen-lock PIN or a suitable security key. A website cannot require fingerprint specifically. Fingerprint/face data never reaches Bingo. Only public keys and signed WebAuthn responses are used by the service. Owner enrollment must happen on the owner's device; no test credential is enrolled in production.

## Enforcement

- `PasskeySecurity` uses `@simplewebauthn/server` 14.0.3 and requires user verification for registration, login and approvals. Expected origin is the exact Pages origin; RP ID is `vamsireddybora.github.io`, not the Worker hostname.
- Password login alone cannot unlock the console after enrollment. Old password-only bearer sessions are rejected. Bearer tokens remain in memory, with no browser storage restoration. Sessions expire after 30 minutes idle or 12 hours total; setup/recovery sessions expire after five minutes.
- A random server challenge expires after two minutes and is consumed once. An approved action yields a one-use proof valid for one minute, bound to session, HTTP method, route, JSON body hash and idempotency ID. Tampering, replay, missing verification, wrong origin/RP and absent user verification are rejected before new mutations. Returning an already completed idempotent result performs no new writes.
- First registration and recovery revoke preceding sessions. Password plus one hashed, single-use recovery code opens only replacement enrollment; it cannot read or edit players. Password and public authentication endpoints retain throttling.
- Authentication metadata lives in the existing admin Durable Object. Never delete that storage to reset a password, roll back styling or clear a directory. Deletion policies, pending operations and audit records must survive.

## Executed evidence

Evidence: `/home/bob/workspace/bingo-admin-passkeys-20261019/` (private local artifacts, no credentials in public source).

- The pre-change test `password bootstrap cannot inspect players before passkey enrollment` failed with actual 200 versus required 403 (`before-passkeys.log`). The same test passes with setup-only restrictions.
- 59 service/adapter/security tests passed. Existing adapter/control regressions were retained, with real P-256 keys, COSE registration and signed assertions added to the production handler tests. Coverage includes wrong origin/RP/signature/challenge, absent user verification, expiration/replay, payload/session binding, recovery and key deletion.
- 21 production UI browser checks plus the uncaught-error check passed (22 total). Chromium used a virtual CTAP2 authenticator with actual WebAuthn ceremonies; the backend verified signatures without verifier stubs. Existing profile/statistics, photos, friends, match/account deletion, stale data, delayed inspector, mobile, cache isolation and lock checks were preserved. New checks include enrollment/recovery download, mandatory sign-in after reload, canceled device approval with no write, and key settings. Desktop/mobile light/dark screenshots are saved.
- Eight checks passed against a separately deployed Cloudflare Worker with an isolated Durable Object and a test owner password. Real deployed registration, login, signature verification, rejection without approval, exact payload binding, one-time proof, rejection without UV and logout were exercised. Only that isolated object's authentication metadata changed. No production player records were used for mutation tests.

Physical fingerprint/face/PIN hardware is not tested remotely. Owner enrollment remains a required one-time user action. Existing legacy-storage/old-APK limitations in README still apply; passkeys do not migrate the game's public legacy storage.

## Rollback and continuity

Source backup: `backup/admin-before-passkeys-20261019` at `25d0b50`. Review later commits before reverting only the affected admin paths. Never hard-reset another agent's changes or remove Durable Object data. Once keys are enrolled, keep the passkey-aware service and UI together; an old password-only frontend cannot operate against the hardened service. Never restore password-only authentication as a styling rollback.

Publish only `admin/` to the existing `gh-pages` worktree. Preserve unrelated game assets and the OTA manifest. The temporary runtime-test Worker has no production broker, storage or service-account secrets and may be deleted after verification.
