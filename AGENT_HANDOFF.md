# Agent handoff — Bingo 1.0, Build 65

## Current implementation

- Preserve the active rules in `SimplifiedGameRules.md`, including the Nearby-only host-hold clarification.
- `NearbyMatchSession` shares `MultiplayerTurnClock` with Online mode. It holds guests until a validated snapshot restores the match, fences older hold epochs and correlates guest reconnect responses.
- `LanP2pSessionManager` separates unexpected EOF/silence from voluntary departure, reconnects to the same host and serializes outgoing packets. Fifteen seconds of unexpected silence changes presence without awarding a forfeit.
- `RootNavGraph` connects lifecycle, packet, timer and board callbacks. `GameScreen` uses the existing non-dismissible host dialog.
- Preserve earlier MQTT shutdown protection, lobby friend ordering, universal avatars, offline notifications and self-presence chat filtering.

## Verification and limits

Build 65: 1,039 tests passed, eight optional network tests skipped, no failures/errors; release lint zero errors and 33 warnings. All 1,024 prior test cases were retained. New regression suites: `NearbyMatchSessionTest` and `NearbyTransportRecoveryTest`; real TCP tests reproduced the original false-departure defects before the fix.

Physical-device/hotspot acceptance remains necessary. Cold Nearby recovery after Android kills the whole host process was not added. Use the same updated build on all Nearby participants. Do not claim complete hardware or zero-bug certification from unit tests.

## Build configuration

Read `README.md`; credentials are supplied through ignored local files/environment. The production signing key and actual Firebase client file are not part of this source. Keep the public configuration templates empty.

## Change discipline

Keep fixes fixed. Preserve existing intentional behavior. Exercise actual production callbacks/helpers in regression tests, retain previous assertions and distinguish source review from executed Android UI proof. Do not add key-pinning handshakes, MQTT storage or external match authorities without an explicit requirement.
