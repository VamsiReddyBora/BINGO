package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.MatchRecord
import com.bingo.multiplayer.domain.model.UserProfile

/** Versioned owner edits in the existing Firebase profile, independent of gameplay packets. */
internal data class CloudAdminPolicy(
    val revision: Long = 0L,
    val fields: Map<String, Change> = emptyMap(),
    val deletedMatchIds: Set<String> = emptySet(),
    val clearMatchesBefore: Long = 0L
) {
    data class Change(val revision: Long, val value: Any?)

    fun applyTo(local: UserProfile): UserProfile {
        if (revision <= local.adminRevision) return local
        val edits = fields.filterValues { it.revision > local.adminRevision && it.revision <= revision }
        fun text(key: String, old: String) = if (key in edits) edits[key]?.value as? String ?: old else old
        fun photo(key: String, old: String?) = if (key in edits) edits[key]?.value as? String else old
        fun number(key: String, old: Int) = (edits[key]?.value as? Number)?.toInt() ?: old
        return local.copy(
            displayName = text("displayName", local.displayName),
            avatarUrl = photo("avatarUrl", local.avatarUrl),
            avatarBase64 = photo("avatarBase64", local.avatarBase64),
            gamesPlayed = number("gamesPlayed", local.gamesPlayed),
            gamesWon = number("gamesWon", local.gamesWon),
            currentStreak = number("currentStreak", local.currentStreak),
            bestStreak = number("bestStreak", local.bestStreak),
            level = number("level", local.level),
            xp = number("xp", local.xp),
            adminRevision = revision
        )
    }

    fun permits(record: MatchRecord) = record.id !in deletedMatchIds &&
        (clearMatchesBefore <= 0L || record.timestamp > clearMatchesBefore)

    /** Projection used by public cards when an older app has not applied the owner edit yet. */
    fun publicProjection(data: Map<String, Any?>): Map<String, Any?> {
        val applied = (data["adminAppliedRevision"] as? Number)?.toLong() ?: 0L
        val edits = fields.filterValues { it.revision > applied && it.revision <= revision }.mapValues { it.value.value }
        val projected = data + edits
        return if ("avatarBase64" in edits) projected + ("avatarUrl" to (projected["avatarBase64"] ?: projected["avatarUrl"]))
            else projected
    }

    companion object {
        private val editable = setOf("displayName", "avatarUrl", "avatarBase64", "gamesPlayed", "gamesWon",
            "currentStreak", "bestStreak", "level", "xp")
        fun from(data: Map<String, Any?>): CloudAdminPolicy {
            val revision = (data["adminRevision"] as? Number)?.toLong() ?: 0L
            val raw = data["adminChanges"] as? Map<*, *>
            val fields = if (raw != null) raw.mapNotNull { (key, value) ->
                val change = value as? Map<*, *> ?: return@mapNotNull null
                val field = key as? String ?: return@mapNotNull null
                if (field !in editable || !change.containsKey("value")) return@mapNotNull null
                field to Change((change["revision"] as? Number)?.toLong() ?: 0L, change["value"])
            }.toMap() else if (revision > 0L) editable.filter { data.containsKey(it) }
                .associateWith { Change(revision, data[it]) } else emptyMap()
            return CloudAdminPolicy(revision, fields,
                (data["adminDeletedMatches"] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty(),
                (data["adminClearMatchesBefore"] as? Number)?.toLong() ?: 0L)
        }
    }
}
