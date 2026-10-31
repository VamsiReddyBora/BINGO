package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class LobbyProfileMessage(val id: String, val playerId: String, val text: String, val revision: Long)

/** One transient message per admitted player. No retained publications or chat history. */
class LobbyMessageSession(private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L }) {
    companion object {
        const val MAX_TEXT = 500
        const val TYPING_EXPIRY_MS = 4000L
        const val TYPING_MIN_INTERVAL_MS = 1200L
        val TYPES = setOf("LOBBY_MESSAGE", "LOBBY_MESSAGES_REQUEST", "LOBBY_MESSAGES_SYNC", "LOBBY_TYPING")
        fun cleanText(value: String) = value.trim().takeIf { it.isNotBlank() && it.length <= MAX_TEXT }
    }
    private val state = MutableStateFlow<Map<String, LobbyProfileMessage>>(emptyMap())
    val messages = state.asStateFlow()
    private val typingState = MutableStateFlow<Set<String>>(emptySet())
    val typingPlayerIds = typingState.asStateFlow()
    private val typingUntil = mutableMapOf<String, Long>()
    private val typingVersions = mutableMapOf<String, Long>()
    private var outgoingTypingVersion = 0L
    private var sentTyping = false
    private var lastTypingAt = 0L
    // Empty-text records are deletion markers. Keep only the latest per member so sync/replays
    // cannot restore an older bubble; consumers see only the nonempty messages.
    private var latest = emptyMap<String, LobbyProfileMessage>()
    private var binding = ""
    private var room = ""
    private var session = ""
    private var me = ""
    private var host = ""
    private var members = emptySet<String>()

    @Synchronized fun bind(roomCode: String, lobbySessionId: String, localId: String, hostId: String, playerIds: Set<String>) {
        val key = "$roomCode|$lobbySessionId|$localId|$hostId"
        if (key != binding) { latest = emptyMap(); clearTyping() }
        binding = key; room = roomCode; session = lobbySessionId; me = localId; host = hostId
        members = playerIds.filter { it.isNotBlank() }.toSet()
        latest = latest.filterKeys { it in members }
        typingUntil.keys.retainAll(members); typingVersions.keys.retainAll(members)
        expireTyping()
        updateVisible()
    }
    @Synchronized fun clear() {
        binding = ""; room = ""; session = ""; members = emptySet(); latest = emptyMap(); clearTyping(); updateVisible()
    }
    @Synchronized fun request(): RoomMessagePacket? = if (room.isBlank() || me !in members) null
        else envelope("LOBBY_MESSAGES_REQUEST")
    private fun envelope(type: String) = RoomMessagePacket(type, playerId = me, senderPlayerId = me,
        roomCode = room, lobbySessionId = session)

    /** Cosmetic, non-retained status. Keystroke bursts never publish more than once per interval. */
    @Synchronized fun typingPacket(active: Boolean): RoomMessagePacket? {
        if (room.isBlank() || me !in members) return null
        val now = elapsedMillis()
        if ((active && sentTyping && now - lastTypingAt < TYPING_MIN_INTERVAL_MS) || (!active && !sentTyping)) return null
        sentTyping = active; lastTypingAt = now
        return envelope("LOBBY_TYPING").copy(displayName = if (active) "1" else "0", sequence = nextTypingVersion())
    }

    @Synchronized fun expireTyping() {
        val now = elapsedMillis()
        typingUntil.entries.removeAll { it.value <= now || it.key !in members }
        typingState.value = typingUntil.keys.filter { it != me }.toSet()
    }

    private fun nextTypingVersion(): Long {
        outgoingTypingVersion = maxOf(System.currentTimeMillis(), outgoingTypingVersion + 1)
        return outgoingTypingVersion
    }

    private fun clearTyping() {
        typingUntil.clear(); typingVersions.clear(); typingState.value = emptySet(); sentTyping = false
    }

    suspend fun send(text: String, publish: suspend (RoomMessagePacket) -> Boolean): Boolean {
        val clean = cleanText(text) ?: return false
        return publishMessage(clean, null, publish)
    }

    /** Delete exactly the bubble confirmed by its owner, without erasing a newer replacement. */
    suspend fun delete(messageId: String, publish: suspend (RoomMessagePacket) -> Boolean): Boolean =
        publishMessage("", messageId, publish)

    private suspend fun publishMessage(text: String, expectedId: String?,
        publish: suspend (RoomMessagePacket) -> Boolean): Boolean {
        val prepared = synchronized(this) {
            if (room.isBlank() || me !in members) return false
            if (expectedId != null && (expectedId.isBlank() || latest[me]?.id != expectedId || latest[me]?.text.isNullOrEmpty())) return false
            val message = LobbyProfileMessage(UUID.randomUUID().toString(), me, text,
                maxOf(System.currentTimeMillis(), (latest[me]?.revision ?: 0L) + 1L))
            Triple(binding, message, envelope("LOBBY_MESSAGE").copy(lobbyMessages = listOf(message), sequence = nextTypingVersion()))
        }
        if (!publish(prepared.third)) return false
        return synchronized(this) {
            if (binding != prepared.first || me !in members) false
            else { merge(prepared.second); true }
        }
    }

    /** Called by the actual room packet handler before any gameplay handling. */
    @Synchronized fun receive(packet: RoomMessagePacket): RoomMessagePacket? {
        if (room.isBlank() || packet.roomCode != room || packet.lobbySessionId != session ||
            packet.playerId !in members || packet.senderPlayerId.ifBlank { packet.playerId } != packet.playerId ||
            (packet.targetPlayerId.isNotBlank() && packet.targetPlayerId != me)) return null
        when (packet.type) {
            "LOBBY_MESSAGE" -> packet.lobbyMessages.singleOrNull()?.takeIf { it.playerId == packet.playerId }?.let {
                if (merge(it)) {
                    typingUntil.remove(it.playerId)
                    typingVersions[it.playerId] = maxOf(typingVersions[it.playerId] ?: 0L, packet.sequence)
                    expireTyping()
                }
            }
            "LOBBY_TYPING" -> {
                if (packet.sequence <= (typingVersions[packet.playerId] ?: 0L) || packet.displayName !in setOf("0", "1")) return null
                typingVersions[packet.playerId] = packet.sequence
                if (packet.displayName == "1") typingUntil[packet.playerId] = elapsedMillis() + TYPING_EXPIRY_MS
                else typingUntil.remove(packet.playerId)
                expireTyping()
            }
            "LOBBY_MESSAGES_REQUEST" -> if (packet.playerId != me) return envelope("LOBBY_MESSAGES_SYNC")
                .copy(targetPlayerId = packet.playerId, lobbyMessages = if (me == host) latest.values.toList()
                    else listOfNotNull(latest[me]))
            "LOBBY_MESSAGES_SYNC" -> if (packet.playerId == host) packet.lobbyMessages.take(members.size).forEach { merge(it) }
                else packet.lobbyMessages.singleOrNull()?.takeIf { it.playerId == packet.playerId }?.let(::merge)
        }
        return null
    }
    private fun merge(message: LobbyProfileMessage): Boolean {
        if (message.playerId !in members || message.id.isBlank() || message.id.length > 80 ||
            (message.text.isNotEmpty() && cleanText(message.text) != message.text) || message.revision <= 0L) return false
        val previous = latest[message.playerId]
        if (previous == null || message.revision > previous.revision ||
            (message.revision == previous.revision && message.id > previous.id)) {
            latest = latest + (message.playerId to message)
            updateVisible()
            return true
        }
        return false
    }
    private fun updateVisible() { state.value = latest.filterValues { it.text.isNotEmpty() } }
}
