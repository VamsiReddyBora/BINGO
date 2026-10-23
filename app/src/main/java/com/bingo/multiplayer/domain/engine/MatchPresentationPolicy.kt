package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.InGameChatMessage
import com.bingo.multiplayer.domain.model.RecentPick
import com.bingo.multiplayer.domain.model.Player
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Pick identity excludes sampling time: a heartbeat must never become a new pick. */
data class PickIdentity(val number: Int, val playerId: String, val turn: Int) {
    companion object { fun of(pick: RecentPick?) = pick?.let { PickIdentity(it.number, it.pickedByPlayerId, it.turnNumber) } }
}

class PickFeedbackTracker(initial: RecentPick?) {
    private var last = PickIdentity.of(initial)
    fun observe(pick: RecentPick?, suspended: Boolean): Boolean {
        val next = PickIdentity.of(pick) ?: return false
        if (next == last) return false
        last = next
        return !suspended
    }
}

@androidx.annotation.Keep
@Serializable
data class InterruptionPromptState(
    val episodeId: String = "",
    val episodeCount: Int = 0,
    val deadlineMillis: Long = 0L,
    val decided: Boolean = false
) {
    fun remainingSeconds(now: Long): Int = ((deadlineMillis - now + 999L) / 1000L).coerceIn(0, 15).toInt()
    fun decide() = copy(decided = true)
    companion object {
        fun open(previous: InterruptionPromptState, episode: String, count: Int, now: Long, paused: Boolean): InterruptionPromptState =
            if (episode.isBlank() || previous.episodeId == episode || count < previous.episodeCount) previous
            else InterruptionPromptState(episode, count, now + 15_000L, paused)
        fun merge(current: InterruptionPromptState, incoming: InterruptionPromptState): InterruptionPromptState = when {
            incoming.episodeCount < current.episodeCount -> current
            incoming.episodeId == current.episodeId -> current.copy(decided = current.decided || incoming.decided,
                deadlineMillis = listOf(current.deadlineMillis, incoming.deadlineMillis).filter { it > 0 }.minOrNull() ?: 0)
            else -> incoming
        }
    }
}

object MatchSystemMessages {
    /** Filter the view, never the shared/saved conversation used by other phones. */
    fun visibleTo(chat: List<InGameChatMessage>, viewerId: String, players: List<Player>): List<InGameChatMessage> {
        val viewer = players.firstOrNull { LobbyLifecycleEngine.isPlayerIdMatch(it.id, viewerId) }
        val ownNames = listOfNotNull(viewer?.displayName, viewer?.username).map { it.trim() }.filter { it.isNotBlank() }
        val unambiguousNames = ownNames.filter { name ->
            players.none { !LobbyLifecycleEngine.isPlayerIdMatch(it.id, viewerId) &&
                (it.displayName.trim().equals(name, true) || it.username.trim().equals(name, true)) }
        }
        return chat.filter { message ->
            if (!message.isSystemMessage || viewerId.isBlank()) true
            else if (message.presencePlayerId.isNotBlank()) {
                !LobbyLifecycleEngine.isPlayerIdMatch(message.presencePlayerId, viewerId)
            } else {
                // Compatibility for old checkpoints/peers that did not carry a
                // presence subject. Match complete known notices, never player chat
                // or ambiguous duplicate display names.
                val text = message.text.trim().trimStart { !it.isLetterOrDigit() }
                unambiguousNames.none { name ->
                    listOf("minimized the app", "minimized the game", "disconnected", "is back", "reconnected").any { status ->
                        text.equals("$name $status", ignoreCase = true)
                    }
                }
            }
        }
    }

    /** One identity per accepted closed turn, shared by online, nearby and recovery. */
    fun turnSkipped(match: Long, playerId: String, playerName: String, closedTurn: Int, now: Long): InGameChatMessage? {
        if (match == 0L || playerId.isBlank() || closedTurn <= 0) return null
        val name = playerName.ifBlank { "Player" }
        return notice(match, playerId, "turn-skipped:$closedTurn", "⏳ $name's turn was skipped.", now)
    }

    fun notice(match: Long, owner: String, event: String, text: String, now: Long): InGameChatMessage {
        val digest = MessageDigest.getInstance("SHA-256").digest("status:$match:$owner:$event".toByteArray())
        return InGameChatMessage(id = ByteBuffer.wrap(digest).long, text = text,
            isSelf = false, senderName = null, timestamp = now, isSystemMessage = true)
    }
    fun merge(chat: List<InGameChatMessage>, notices: List<InGameChatMessage>): List<InGameChatMessage> {
        // Accepted player chat already has authoritative order. Phone wall clocks
        // can differ; inserting local notices must never reorder that conversation.
        val result = chat.distinctBy { it.id }.toMutableList()
        val ids = result.mapTo(mutableSetOf()) { it.id }
        notices.forEach { notice ->
            if (ids.add(notice.id)) {
                val index = result.indexOfFirst { it.timestamp > notice.timestamp }
                result.add(if (index < 0) result.size else index, notice)
            }
        }
        var seenHostNotice = false
        val sanitized = mutableListOf<InGameChatMessage>()
        for (msg in result) {
            val isHostNotice = msg.isSystemMessage && (msg.text.contains("is the host") || msg.text.contains("are the host"))
            if (isHostNotice) {
                if (seenHostNotice) continue
                seenHostNotice = true
            }
            sanitized.add(msg)
        }
        return sanitized
    }
}
