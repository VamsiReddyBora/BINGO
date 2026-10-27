# Bingo OTA content updates — Build 74

## What this adds

Build 74 introduces small, data-only updates downloaded from the existing GitHub Pages site. The installed app can update supported Home wording, limited card layout values and an optional static Home background without another APK installation. The update foundation must first be installed through an APK; v1.0.4 (Build 76) includes it.

This does not download executable code. Kotlin fixes, gameplay rules, network handlers, new screens, permissions and new renderers still require an APK. It is not a general native hotpatch system. Build 75 separately adds public or account-specific APK offers; see [APK_UPDATE_CHANNELS.md](APK_UPDATE_CHANNELS.md).

Current visuals remain the defaults. No Ambient Glow, Sudoku, gameplay change or content pack is enabled by this implementation alone. MQTT gameplay and Firebase storage keep their existing roles.

## User experience

- An animated button immediately before the Home ping shows a content download or application in progress. It briefly shows a checkmark after application. Routine checks do not show a download animation.
- Tapping it opens status, download progress, **Check now**, an automatic-update switch and **Restore previous content**. The same controls are available through **Settings → Content updates**.
- The indicator also reflects an existing APK download. An APK still requires Android installation; the dialog says this explicitly.
- Downloads can be staged in the background. Activation requires the authenticated account's foreground main container, outside a match. Leaving that screen invalidates its activation permission.
- A failed, invalid or incompatible download retains the saved content. Errors appear in update details rather than pretending a download is still running.

## Supported payload

| Field | Limit or purpose |
| --- | --- |
| `home.ai.title` | 48 characters |
| `home.ai.description` | 140 characters |
| `home.online.title` | 48 characters |
| `home.online.description` | 140 characters |
| `home.nearby.title` | 48 characters |
| `home.nearby.description` | 140 characters |
| `home.developerNote` | 60 characters |
| `home.serverNote` | 400 characters; does not change broker-error detection |
| `homeStyle.verticalPaddingDp` | Online/Nearby cards: integer 8–20; omitted keeps 16 |
| `homeStyle.titleSp` | Online/Nearby cards: integer 13–18; omitted keeps 15 |
| `homeStyle.descriptionSp` | Online/Nearby cards: integer 10–14; omitted keeps 12 |
| `homeStyle.descriptionMaxLines` | Online/Nearby cards: integer 1–3; omitted preserves existing wrapping |
| `homeStyle.backgroundOpacity` | 0–0.15; default 0.08 |
| `background` | Optional PNG/JPEG/WebP, at most 2 MiB, each dimension 1–2048 pixels |

Text is plain, nonblank, single-line text with no control characters. Unknown keys and incompatible schemas are rejected. Admin image upload accepts PNG/JPEG and resizes through the browser to a maximum 1280-pixel edge; Android also checks decoded bounds and downsamples large images. No arbitrary remote URLs, HTML, ZIP files or scripts are accepted.

## Publishing from the admin console

The source includes a **Silent content updates** panel under **App releases**. Deployment of the updated admin service and admin files is required before that panel is available live. Publication uses the existing owner session, fresh action-bound passkey approval, GitHub credential, operation retries and audit trail.

1. Choose **Everyone** or a registered username, then load its current revision.
2. Open the wording/layout sections and enter only the overrides needed. Leave unused fields unset.
3. Set compatible app builds (minimum 74; maximum 0 means no upper bound).
4. Review the target and payload, then approve the publication with the existing device verification.
5. Wait for GitHub Pages deployment. A saved GitHub commit is not proof that a phone downloaded or applied it.
6. Check the target phone's update details and actual Home appearance. No per-phone content-application acknowledgement is implemented in this first version.

The server resolves usernames to registered Firebase UIDs and publishes SHA-256 UID filenames. Public files contain neither a raw username nor a raw UID. A hashed target is routing, not secret access control; do not put private data in content packs.

Only `content/` is changed by a content publication. Existing APKs, `version.json`, GitHub Releases, source tags and player statistics are not rewritten.

## Feed format and revisions

Global feed: `https://vamsireddybora.github.io/BINGO/content/index.json`

Target feed: `https://vamsireddybora.github.io/BINGO/content/users/{sha256(uid)}.json`

Assets: `https://vamsireddybora.github.io/BINGO/content/assets/{sha256(bytes)}.png` (or `.jpg`/`.webp`).

Example global text update, assuming revision 1 is the next unused revision:

```json
{
  "schema": 1,
  "revision": 1,
  "audience": "ALL",
  "minBuild": 74,
  "maxBuild": 0,
  "enabled": true,
  "inheritGlobal": false,
  "strings": { "home.online.description": "Host or join multiplayer rooms with friends." },
  "homeStyle": { "backgroundOpacity": 0.08 },
  "background": null
}
```

Each audience has an increasing integer revision. Never edit bytes at an existing revision or replace an immutable checksum-named image. The app remembers the revision and manifest digest and rejects old or changed-at-the-same-revision payloads. Admin publishing checks the expected revision and uses a non-forced Git reference update to reject concurrent changes.

A targeted pack takes priority over the global pack. To return a target to public content, publish a **higher targeted revision** with `inheritGlobal: true`, empty strings, default style and no background. To restore built-in content, publish a higher revision with `enabled: false`, empty strings, default style and no background. Removing a targeted file alone does not revoke an already installed targeted override.

## Timing, storage and recovery

- Entering the foreground main container checks without blocking launch, throttled to once per minute per account. **Check now** bypasses that throttle.
- WorkManager requests a connected, non-low-battery check every six hours. Android decides actual execution; force-stop and power restrictions can delay it.
- Content checks use dedicated bounded HTTPS requests to Pages. There is no added HTTP request per pick or change to match authority.
- State and images are private to a hash of the UID. Active, previous and staged revisions are retained with an atomic state pointer. Unused content images and partial downloads are cleaned up.
- Network failure preserves the saved content. If the local state itself is unreadable, built-in defaults remain available; do not assume all local storage damage can recover automatically.
- **Restore previous content** restores the previous revision, or built-in defaults when there is no previous revision. The reverted revision is blocked locally so the next check does not immediately reinstall it. A newer revision can still apply.
- Disabling automatic updates is per account on that installation. Re-enabling allows staged content to apply and starts a check.

## Verification and operation boundaries

Tests exercise the production update coordinator, strict manifest parser, real streaming file copy/checksum path with substituted HTTP responses, and actual admin UI/service/publishing handlers using fictional GitHub/player stores and a virtual passkey. The exact run counts, build identity and delivery receipt are recorded in the private job report.

These tests do not establish physical-device rendering, Android image decoding, AtomicFile interruption behavior or WorkManager scheduling. Verify the installed Build 74 on a phone before claiming a complete device-level OTA test.

Do not publish a fake production pack, notify real players, enroll production test passkeys or modify player records during verification. Back up the current Pages commit and service version before deploying. Keep credentials, account data, APK signing material and operator evidence outside public source packages. A service rollback must preserve the existing `control-v1` Durable Object and its storage.
