# Build 48 — live invitation investigation and fixes

Updated: 2026-10-20T19:50:14+05:30. The user confirmed the affected phone was running build 46, not the locally supplied build 47. Build 48 includes build 47's fixes plus the production-service repair and visible build identifier below.

## Confirmed causes and actions

| ID | Confirmed problem | Fix / location | Evidence |
|---|---|---|---|
| I48-01 | Cold room discovery failed against the deployed backend with HTTP 503. Its legacy-read deadline was 2.5 seconds, shorter than observed healthy 4–5 second storage responses. | `cloudflare_worker/rooms.js`: bounded 8-second legacy fetch budget. `RoomMetadataStore.kt`: 20-second metadata request/read budget permits two sequential migration reads; MQTT picks and turn processing are unchanged. | Live two-account probe failed at room lookup before deployment. Controlled worker test failed 503 vs expected 200; native HTTP test failed with the old six-second client deadline. Both regressions pass after repair. |
| I48-02 | Old regression tests were publishing synthetic metadata to the production MQTT broker. Live fresh subscriptions did not receive newly retained invitations; the real cold gameplay check also failed. | Backed up and removed 56 unequivocal test records: invalid non-six-character room codes and exact host/prefix fixtures from `NetworkRound1RegressionTest`. Added per-test metadata publisher isolation to that class. No real six-character room was removed and no original assertion was weakened. | Before/after live native invitation and gameplay tests; retained-message inventory and full backup. Both live tests pass after cleanup. Capacity pressure is inferred from the recovery after removal; no management-console quota reading was obtained. |
| I48-03 | The phone's build was unclear because Settings showed only the release version. | `SettingsScreen.kt`: show `Version 1.0 · Build 48`, read from installed package metadata using PackageInfoCompat. No hardcoded fallback to an old release. | Compiled APK version/signature inspection; actual Settings rendering remains for the user's phone check. |

Build 47's verified-canonical-room readiness and queued MQTT resend repair remain included. The old release's duplicate metadata-publication gate was not restored. Home ping remains a value only. Authentication, conditional room commits, friend data, board rules, pause/exit behavior and turn budgets remain unchanged.

## Legacy comparison

Inspected `legacy/1.4.1` and commit `4692635`'s GameInviteManager. The invitation topic remains `bingo/v3/invites/<username>`, with live foreground presentation. Older code attempted MQTT before cloud backup; it also treated some cloud errors as sent. This repair preserves authenticated inbox/push registration and truthful sent/queued results. It does not restore the older public invitation directory or bypass closed-room/host checks.

## Executed live checks

- `live-backend-probe.log`: two owned guest notification identities authenticated successfully, but cold room lookup failed 503 before the timeout repair. Their registrations were revoked.
- `live-backend-after.log`: after deployed version `9180067e-9b37-42d4-b640-472f7d4596fc`, owned identity → conditional room commit → invitation event (202) → recipient inbox (200) → acknowledgement and room closure all passed. Both test registrations were revoked. No real user was invited. Observed event-registration and inbox reads were 774ms and 278ms for that run; these are workspace measurements, not phone latency guarantees.
- `LiveInvitationIntegrationTest`: actual GameInviteManager publisher → private TLS broker → actual listener → foreground flow passed. A newly connected listener received the retained invitation. The exact generated private topic was cleared afterward.
- `SimplifiedLiveMqttTest`: both real native managers received the same accepted pick, then recovered picks, boards and clock after both original clients closed; durable explicit surrender produced the expected result. All original assertions remain.
- Failed and passing live XML/logs, controlled before/after logs and `owned-unit-fixture-retained-backup.json` are preserved in `/home/bob/workspace/bingo-live-invitation-20261020-193547`.

[EMQX documentation](https://docs.emqx.com/en/cloud/latest/deployments/retain_message.html) documents retained delivery and storage limits. Monitor retained capacity as usage grows; this pass clears only source-identifiable test artifacts, not other users' stored games.

## Final validation and artifact

- Full release validation: `BINGO_LIVE_MQTT=1 ./gradlew :app:testReleaseUnitTest :app:assembleRelease --rerun --console=plain` — BUILD SUCCESSFUL.
- **777 passed, 0 failed, 0 skipped**, including the two real-broker integration cases. All 774 prior passing unit cases remain passing.
- Backend: **64 passed, 0 failed**, including the real slow-response regression.
- Post-suite broker audit: zero new retained records from the isolated legacy unit fixtures.
- Signed package: `com.bingo.multiplayer`, **1.0 (48)**; signer matches previous releases.
- APK: **7,310,064 bytes**. SHA-256 `feeecafba3c95a9af4bd32034fb9ea8f666ae8f57007647fa5c2280bc6ecdd81`.
- Local download: `/home/bob/workspace/Bingo-1.0-build48.apk`; standard copies also updated at `/home/bob/workspace/Bingo-1.0.apk` and `releases/Bingo-1.0.apk`.
- Rules SHA-256 unchanged: `204fb52d6156257e982cfb51620e0e618f8b8cf5f415cd03ab127870d78e318d`. No assertions removed or weakened.
- Validation completed: 2026-10-20T19:54:08+05:30. Source commit and published asset verification are recorded in this release's exact-source link and the local `publication-verification.json` after upload.


## Limits

These are real service/native-transport checks and JVM/worker regressions. They do not execute Android Compose, Google sign-in on the user's phone, device-specific WorkManager behavior, physical FCM presentation, or OS background restrictions. A successful backend inbox test is not a claim of a visible Android push notification. Install the same APK on both phones and check Settings' build number before testing a fresh invitation. Previously documented P3 closure-retry resource duplication and broader UI/lifecycle evidence gaps remain explicit. No zero-bug or all-edge-case guarantee is made.
