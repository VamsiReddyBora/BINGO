package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.UserProfile

/** Apply an imported baseline once per saved account, before a first local profile upload. */
internal fun recoverLegacyProfile(local: UserProfile, snapshot: Map<String, Any?>): UserProfile {
    val marker = snapshot["legacyMigration"] as? Map<*, *> ?: return local
    if ((marker["schema"] as? Number)?.toInt() != 1 || marker["source"] != "keyvalue-extendsclass") return local
    val id = marker["id"] as? String ?: return local
    if (!id.matches(Regex("[A-Za-z0-9_-]{8,96}")) || local.legacyMigrationId == id) return local
    if (snapshot["uid"] != local.uid || snapshot["provider"] != local.provider.name || local.adminRevision > 0L) return local
    fun count(key: String): Int? = (snapshot[key] as? Number)?.toLong()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
    val played = count("gamesPlayed") ?: return local
    val won = count("gamesWon") ?: return local
    val streak = count("currentStreak") ?: return local
    val best = count("bestStreak") ?: return local
    val level = count("level")?.takeIf { it >= 1 } ?: return local
    val xp = count("xp") ?: return local
    val name = (snapshot["username"] as? String)?.trim()?.lowercase()?.removePrefix("@") ?: return local
    if (!name.matches(Regex("[a-z0-9][a-z0-9_.-]{1,63}")) || won > played || best < streak) return local
    val photo = (snapshot["avatarBase64"] as? String)?.takeIf { it.isNotBlank() }
    val photoUrl = (snapshot["avatarUrl"] as? String)?.takeIf { it.startsWith("https://") }
    return local.copy(
        username = name,
        displayName = (snapshot["displayName"] as? String)?.takeIf { it.isNotBlank() } ?: local.displayName,
        gamesPlayed = maxOf(local.gamesPlayed, played), gamesWon = maxOf(local.gamesWon, won),
        currentStreak = if (local.gamesPlayed > played) local.currentStreak else streak,
        bestStreak = maxOf(local.bestStreak, best), level = maxOf(local.level, level), xp = maxOf(local.xp, xp),
        avatarBase64 = photo ?: local.avatarBase64,
        avatarUrl = photoUrl ?: if (photo != null) null else local.avatarUrl,
        legacyMigrationId = id
    )
}
