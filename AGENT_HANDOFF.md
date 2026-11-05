# BINGO 2.0 / Build 137 release source

The Android runtime is the latest tested build 136 with only version metadata changed to 2.0 / 137. Preserve prior releases, tags, APKs and source archives. Read SimplifiedGameRules.md before gameplay changes. Supply deployment configuration and release signing inputs privately as described in README.md.

Keep the native Admin Control role guard based on the configured immutable account identities, never display names. Both existing administrators use the same visibility and entry policy. Cleanup changes require fresh strict biometric authentication, verified Google/Firebase identity and revision checks. Reconnecting must match the active Bingo account, never silently replace it.

The graph uses shared cloud-monitor history. The phone RTT is separate. Reset is the latest 60 seconds at 0–250 ms, with one-second tick marks and readable ten-second labels. Pinching is centred and cannot pan; explicit horizontal drag navigates history. Bound navigation by the monitor start time and 30-day retention. Auto-fit keeps plotted averages visible and ignores unplotted peaks; peaks remain in the actual-record tooltip. Keep the raw preferred vertical span separate from rounded display bounds so small pinch deltas accumulate.

The owner requested solid connectors across missing/failed checks. Connect actual averages on each side, including adjacent off-screen records, but never invent intermediate samples, zero out failures or extrapolate after the newest observation. Only actual observations get dots and tap values. Preserve null outage records and the temporary selection crosshair; do not restore permanent per-sample peak stems.

The cloud monitor remains three isolated non-retained MQTT echoes every 60 seconds, independent of open apps and game cleanup, with 30 days of Cloudflare SQLite history. Do not change to one-second recording or migrate to Firebase for this release. Preserve both ADMIN/control-v1 and MONITOR/broker-monitor-v1 namespaces, bindings, migrations, secrets and stored state. The minute Cron invokes the monitor only. The backend is packaged as source without a production redeployment.

Keep the permanent 15 dp page gap, original centred splash, Home actions at 185 dp below Nearby Network, shield-only admin entry, fixed dashboard values, readable dark/light threshold colors and removed temporary tuning UI. Retain the PREVIEW label for simulated ingress. Grayscale broker appearance indicates reachability from the phone, not proof of deliberate server shutdown.

SOURCE_MANIFEST.json records source hashes and checks. Automated verification does not certify physical-device appearance, Google consent, biometric interaction, installation or frame rate. Device-specific light-theme status-bar appearance remains an acceptance limitation. Live cleanup regression harnesses are explicitly opt-in and limited to owned fixtures; do not execute them to publish an APK.
