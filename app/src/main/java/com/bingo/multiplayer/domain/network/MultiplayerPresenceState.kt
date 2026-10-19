package com.bingo.multiplayer.domain.network

import kotlinx.serialization.Serializable

@Serializable
data class MultiplayerPresenceRecord(
    val connectionId: String,
    val instanceId: String,
    val version: Long,
    val observedAt: Long,
    val status: String
)

@Serializable
data class MultiplayerPresenceState(
    val records: Map<String, MultiplayerPresenceRecord> = emptyMap(),
    val retiredConnections: Set<String> = emptySet()
) {
    fun update(id: String, next: MultiplayerPresenceRecord, isLastWill: Boolean = false): MultiplayerPresenceState {
        if (id.isBlank() || next.connectionId.isBlank() || next.connectionId in retiredConnections ||
            next.status !in setOf("ONLINE", "AWAY", "OFFLINE")) return this
        val old = records[id]
        if (isLastWill) {
            // A will is fixed when CONNECT is sent, so its creation timestamp is not its delivery time.
            if (old == null || old.connectionId != next.connectionId || old.instanceId != next.instanceId) return this
            return copy(records = records + (id to old.copy(status = "OFFLINE", version = Long.MAX_VALUE)))
        }
        if (old != null && old.connectionId == next.connectionId && next.version <= old.version) return this
        if (old != null && old.connectionId != next.connectionId && next.observedAt <= old.observedAt) return this
        return copy(records = records + (id to next), retiredConnections = if (old != null && old.connectionId != next.connectionId)
            retiredConnections + old.connectionId else retiredConnections)
    }

    fun isAbsent(id: String): Boolean = records[id]?.status in setOf("AWAY", "OFFLINE")
}
