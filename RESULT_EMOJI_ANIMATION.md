# Result emoji animation — final release preset

Bingo 1.0.5, Build 84. The owner finalized these values after testing the adjustable Build 83:

| Value | Final preset |
|---|---|
| Emoji count | 50 |
| Upward speed | 2.00x |
| Falling speed | 0.70x |
| Launch spread | 900ms |
| Emoji size | 0.80x |
| Rotation | 2.00x |
| Area top / bottom | 20% / 90% from top |
| Area width / horizontal centre | 96% / 50% |
| Sideways drift | 1.95x |

Win: 🥳😍🤩💥🎊🎉🔥🩵💚💛❤️‍🔥🎈🎁🎇🎆

Loss: 🤐😵‍💫😩🤧💀🙊💔🤦🏻‍♂️🥀🙆🏻‍♂️😱🫪😑😭🙄

Every burst samples complete emoji sequences randomly from the corresponding fifteen-entry palette. All fifty launch within 900 ms, rise quickly across the chosen area, slow continuously and fall below the screen. These speed values determine duration (approximately 4.14 seconds depending on the seeded paths); the match clock never changes. No hold or shared target point is added. Existing raster caching, reusable frame output and lifecycle cancellation remain. The 120 Hz hint is a platform preference, not a measured FPS guarantee.

## Final Settings UI

Settings → Animations keeps only Winning emoji animation, an on/off switch. The preview buttons, sliders, emoji pickers, copy/reset controls and their inactive state/persistence helpers are removed. Previously saved testing keys are ignored, so upgrading users also receive the final preset. The saved on/off choice remains intact. A compile-time master flag can disable the feature.

## Integration and coverage

The actual result renderer calls ResultEmojiMotion.create(result, seed), which uses this fixed preset. GameScreen retains the existing one-shot finish policy: only eligible win/loss events animate. Runner/offline cases do not animate; recreating, toggling or returning to a finished screen cannot replay the result. Decorations do not intercept taps or send sound, haptics, network events or gameplay writes.

ResultEmojiMotionTest verifies exact palettes/defaults, actual production speed ratios, random mixes, complete ZWJ/skin-tone sequences, area bounds, launch timing, continuous trajectories, finite frames and reusable render output. AnimationPreferencesTest verifies the on/off switch and ignores retired testing keys on upgrades. ResultEmojiPlaybackTest retains finish/replay/lifecycle eligibility coverage.

Coverage changes are intentional requirements, rather than removed gameplay regressions: adjustable-preset persistence, copied values, editable selections and duplicate-party-face weighting were superseded by the owner's fixed-preset and remove-adjustments request. Neutral trajectory/extreme-value tests remain. No gameplay, network, storage, rule or notification behavior changes are included.

Native visual acceptance and measured hardware FPS are not exercised by JVM checks. Verification totals are recorded in SOURCE_MANIFEST.json after the release checks finish.

Verification: 1,251 Android tests passed, zero failures and eight optional live/device skips. The clean public source passed the same cases. All gameplay regressions were retained; retired adjustment/picker checks were replaced by final-preset and upgrade tests. Release lint: zero errors, 34 existing warnings. Original signing certificate and APK alignment verified. No new physical-device/FPS measurement was performed.

Verification: 1,252 Android tests passed, zero failures and eight optional live/device skips. The clean public source passed the same cases. All gameplay regressions were retained; retired retained-account checks were replaced by live-publication checks preserving their payload/key assertions. Release lint: zero errors, 34 existing warnings. Original signing certificate and APK alignment verified. No new physical-device/FPS measurement was performed.
