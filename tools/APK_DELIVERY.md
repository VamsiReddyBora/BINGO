# Automatic APK delivery after builds

The user requires every completed APK build on this workspace to overwrite `Bingo.apk` on both configured SSH devices. This applies to debug and release APKs. Do not install the APK automatically.

`assembleDebug` and `assembleRelease` run `tools/deliver_apk.py` after packaging. The script uploads into a temporary file, verifies SHA-256, then replaces the destination's `Bingo.apk`. It verifies the final file too. Both destinations are attempted; a failed transfer is reported as a delivery failure, with the compiled APK still available locally.

The workspace credentials and the two destinations are stored only in `~/.config/bingo/apk-delivery.json` (permissions `0600`, parent directory `0700`). Never copy this file or its password into Git, screenshots, logs or handoff documents. SSH receives the password through its askpass pipe. Existing SSH host keys remain checked; changed keys are not silently accepted.

To retry a delivery without rebuilding:

```bash
python3 tools/deliver_apk.py /home/bob/workspace/Bingo-1.0.apk
```

The latest per-device checksum/status receipt is `~/.config/bingo/apk-delivery-latest.json`; it contains no password. Concurrent local deliveries are serialized so two builds cannot interleave their uploads. Temporary files are cleaned up and the previous `Bingo.apk` remains in place when upload/checksum verification fails before replacement.

Build machines without this workspace's private configuration skip remote delivery. On this configured workspace, an unreachable device is a delivery failure, not a game-code failure. Report it clearly and rerun the command when SSH is available. Do not claim both copies were delivered until both checksums are verified.
