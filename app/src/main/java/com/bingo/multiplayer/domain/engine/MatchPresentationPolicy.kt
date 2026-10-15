package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.InGameChatMessage
import com.bingo.multiplayer.domain.model.RecentPick
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
        return result
    }
}
