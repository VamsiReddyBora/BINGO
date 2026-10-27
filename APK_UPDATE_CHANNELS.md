# APK updates for everyone or selected accounts — v1.0.4 (Build 76)

## Two supported update types

| Type | Everyone | Selected username/account | Installation |
| --- | --- | --- | --- |
| Supported OTA content | Yes | Yes, from Build 74 | Silent; supported Home text/layout/static images only |
| APK update | Yes, including older app versions through the existing public feed | Yes, from Build 75 | Download in the app, then Android installation approval |

Native Kotlin fixes and new features require an APK. APKs cannot silently install on a normal personal Android phone. OTA content keeps its existing cache, verification, rollback, user switch and Home progress button; see [OTA_CONTENT_UPDATES.md](OTA_CONTENT_UPDATES.md).

**One-time bootstrap:** Build 74 and older installations do not read account-specific APK offers. Install v1.0.4 (Build 76) or newer through the public updater or normal Android installation. Selected-account APK support was introduced in Build 75. After bootstrap, a higher build can be offered only to that account. Publishing a targeted manifest cannot modify an older installed updater.

## Admin operation

Open **App release → APK audience**:

1. Choose **Everyone** or a registered username, such as **@bob**. The current offer changes with that selection.
2. Select a compiled, signed APK or provide its existing repository download URL. Enter the APK's actual version name and build number, notes and optional checksum.
3. Check the displayed audience in the review. Fresh passkey approval is required, using the existing admin security system.
4. Wait for GitHub Pages deployment, then check the selected device. A saved publication does not prove a phone received or installed it.

For **Everyone**, the existing public `version.json`, stable `Bingo.apk` and fallback version metadata are published as before. Older installed apps retain this update path. Existing GitHub Releases and source tags remain separate.

For **@bob**, the service resolves the registered Firebase UID and hashes it. It writes only:

- `app-updates/users/{sha256(uid)}.json`, containing a versioned account-specific offer;
- `app-updates/apks/Bingo-{version}-build{code}-{sha256(apk)}.apk`, an immutable APK file.

It does not change `version.json`, stable `Bingo.apk`, fallback public version metadata, another account's offer or OTA content. Raw usernames/UIDs are not written into public offer manifests. A username rename retains the same account's offer; a newly created account with a different UID does not inherit it.

The targeting controls automatic offers and notifications. GitHub Pages is public file hosting; a copied APK URL can still be manually shared and installed. It is not an account-based license or private download authorization system.

To withdraw a selected offer, choose that account and click **Withdraw selected offer**. A higher revision disables that offer. Its installed APK remains on the device; future checks follow public updates. This does not recall an APK already downloaded or reverse an Android installation.

## Selection rules and examples

- Public Build 76; Bob-only Build 77: Bob is offered 77, everyone else 76.
- Later public Build 78: Bob and everyone else are offered 78. A lower targeted build cannot pin or downgrade Bob.
- Bob already has Build 77; public build is still 76: no reinstall/downgrade is offered.
- Bob logs out and Stuart signs in: Bob's late check/download results cannot become Stuart's offer. Pending install ownership is checked again before opening the installer.
- Bob's targeted APK URL fails: the app reports failure; it never substitutes the public APK for that targeted download.
- Target feed is missing, disabled, invalid or unavailable: public update checks remain usable.

Native build numbers, rather than a higher-looking version label, determine upgrades whenever the build is known. Legacy GitHub Release metadata without a build still uses the existing version comparison; the downloaded archive is checked before installation.

Checks run after the app reaches its main screen, on returning to it (one-minute throttle), manually in Settings and through the existing periodic update worker. Existing notification preferences and session dismissal remain respected. Android background scheduling and force-stop restrictions still apply.

## Verification and preservation

APK downloads have a 12 MiB limit, bounded streaming, expected size and SHA-256 checking when supplied. Before showing Ready to install, Android's package parser verifies the app package, expected version/build, a strictly newer installed build and the original signing certificate. Failed/cancelled/account-changed downloads remove their partial file and preserve the installed application. Download permission and Android's installer remain in control of APK installation.

Targeted publication uses optimistic revisions, immutable image-independent APK paths, non-forced Git reference updates, durable audited operations and retry supersession. A stale pending publication cannot undo a newer withdrawal. Public publication retains its previous flow and tests. No new service, database schema, MQTT game packet or gameplay rule is introduced.

The job report records automated test counts, signing checks, deployment identities and actual Bora delivery status. JVM tests exercise the production resolver and streaming verifier. Browser tests exercise the real UI/Worker/publisher path with fictional stores and signed virtual passkey approval. Actual phone package parsing, installer permission/return flow, notification rendering and background scheduling require device verification; do not present the automated suite as a completed physical-device test.

No production test APK offer, public APK update, player mutation or broadcast should be published merely to run the test suite. Build 75 is a local bootstrap APK until the owner explicitly selects an APK distribution audience.
