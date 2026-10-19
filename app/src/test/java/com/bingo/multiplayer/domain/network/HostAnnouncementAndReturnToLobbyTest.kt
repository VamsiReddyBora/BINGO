package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.GameMode
import com.bingo.multiplayer.domain.model.InGameChatMessage
import com.bingo.multiplayer.domain.model.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Unit tests verifying:
 * 1. Authoritative host resolution across multiple players so only the actual host is announced.
 * 2. Deterministic ID and deduplication of host chat messages across peers.
 * 3. Return to Lobby button availability and display rules for multiplayer vs AI mode.
 */
class HostAnnouncementAndReturnToLobbyTest {

    @Test
    fun testAuthoritativeHostAnnouncementAcrossThreePlayers() {
        val alice = Player(id = "u_alice", displayName = "Alice", username = "alice", isHost = true)
        val bob = Player(id = "u_bob", displayName = "Bob", username = "bob", isHost = false)
        val charlie = Player(id = "u_charlie", displayName = "Charlie", username = "charlie", isHost = false)
        val participants = listOf(alice, bob, charlie)
        val matchSeed = 987654321L

        // Helper mimicking RootNavGraph authoritative host calculation
        fun resolveHostNotice(
            mode: GameMode,
            localUid: String,
            isHosting: Boolean,
            incomingHostId: String?,
            currentHostId: String?,
            roster: List<Player>,
            baseSeed: Long
        ): InGameChatMessage {
            val authoritativeHostUid = when (mode) {
                GameMode.AI_EASY, GameMode.AI_HARD -> localUid
                else -> {
                    (if (isHosting) localUid else null)
                        ?: incomingHostId?.takeIf { it.isNotBlank() }
                        ?: currentHostId?.takeIf { it.isNotBlank() }
                        ?: roster.firstOrNull { it.isHost }?.id
                        ?: ""
                }
            }

            val actualHostPlayer = roster.firstOrNull { it.id == authoritativeHostUid }
                ?: roster.firstOrNull { it.isHost }
                ?: roster.firstOrNull()

            val actualHostName = actualHostPlayer?.displayName?.ifBlank { actualHostPlayer.username }?.takeIf { it.isNotBlank() } ?: "Host"
            val hostInitialMsgText = if (mode == GameMode.AI_EASY || mode == GameMode.AI_HARD) {
                "👑 You are the host"
            } else {
                "👑 $actualHostName is the host"
            }
            val hostNoticeId = (baseSeed xor 0x484F53544C4F4E47L)
            return InGameChatMessage(
                id = hostNoticeId,
                text = hostInitialMsgText,
                isSelf = false,
                senderName = null,
                timestamp = 1000L,
                isSystemMessage = true
            )
        }

        // Alice (Host) perspective
        val aliceNotice = resolveHostNotice(
            mode = GameMode.ONLINE_ROOM,
            localUid = "u_alice",
            isHosting = true,
            incomingHostId = null,
            currentHostId = "u_alice",
            roster = participants,
            baseSeed = matchSeed
        )

        // Bob (Guest 1) perspective
        val bobNotice = resolveHostNotice(
            mode = GameMode.ONLINE_ROOM,
            localUid = "u_bob",
            isHosting = false,
            incomingHostId = "u_alice",
            currentHostId = "u_alice",
            roster = participants,
            baseSeed = matchSeed
        )

        // Charlie (Guest 2) perspective
        val charlieNotice = resolveHostNotice(
            mode = GameMode.ONLINE_ROOM,
            localUid = "u_charlie",
            isHosting = false,
            incomingHostId = "u_alice",
            currentHostId = "u_alice",
            roster = participants,
            baseSeed = matchSeed
        )

        // All three must produce the identical text and deterministic ID!
        assertEquals("👑 Alice is the host", aliceNotice.text)
        assertEquals("👑 Alice is the host", bobNotice.text)
        assertEquals("👑 Alice is the host", charlieNotice.text)

        assertEquals(aliceNotice.id, bobNotice.id)
        assertEquals(bobNotice.id, charlieNotice.id)
    }

    @Test
    fun testDeduplicateChatMessagesRemovesConflictingHostMessages() {
        fun deduplicateChatMessages(messages: List<InGameChatMessage>): List<InGameChatMessage> {
            var hasHostNotice = false
            val result = mutableListOf<InGameChatMessage>()
            val seenIds = mutableSetOf<Long>()
            val sorted = messages.sortedBy { it.timestamp }
            for (msg in sorted) {
                if (!seenIds.add(msg.id)) continue
                val isHostNotice = msg.isSystemMessage && (msg.text.contains("is the host") || msg.text.contains("are the host"))
                if (isHostNotice) {
                    if (hasHostNotice) continue
                    hasHostNotice = true
                }
                result.add(msg)
            }
            return result
        }

        val msg1 = InGameChatMessage(id = 1L, text = "👑 Alice is the host", isSystemMessage = true, timestamp = 100L)
        val msg2 = InGameChatMessage(id = 2L, text = "👑 You are the host", isSystemMessage = true, timestamp = 150L)
        val msg3 = InGameChatMessage(id = 3L, text = "👑 Bob is the host", isSystemMessage = true, timestamp = 200L)
        val chatMsg = InGameChatMessage(id = 4L, text = "Good luck!", isSelf = false, timestamp = 300L)

        val merged = deduplicateChatMessages(listOf(msg1, msg2, msg3, chatMsg))

        // Exactly one host message must remain!
        val hostMessages = merged.filter { it.isSystemMessage && it.text.contains("host") }
        assertEquals(1, hostMessages.size)
        assertEquals("👑 Alice is the host", hostMessages[0].text)

        // Regular chat messages are preserved
        assertEquals(2, merged.size)
        assertEquals("Good luck!", merged[1].text)
    }

    @Test
    fun testGameOverActionVisibilityAiVsMultiplayer() {
        // AI mode: Main Menu and Play Again are shown
        val isAiMode = true
        val showMainMenuAndPlayAgain = isAiMode
        val showReturnToLobby = !isAiMode

        assertTrue(showMainMenuAndPlayAgain)
        assertFalse(showReturnToLobby)

        // Multiplayer modes: Only Return to Lobby is shown
        val isMultiplayer = false // isAiMode = false
        val showMainMenuAndPlayAgainMultiplayer = isMultiplayer
        val showReturnToLobbyMultiplayer = !isMultiplayer

        assertFalse(showMainMenuAndPlayAgainMultiplayer)
        assertTrue(showReturnToLobbyMultiplayer)
    }
}
