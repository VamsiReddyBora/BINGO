package com.bingo.multiplayer.domain.notifications

/** Match FRIEND_ONLINE_INTERVAL_MS in cloudflare_worker/wrangler.toml. */
object NotificationOnlineConfig {
    const val OFFLINE_INTERVAL_MS = 1_800_000L
    // Restore this to 3_600_000L and the matching relay setting for the one-hour policy.
}
