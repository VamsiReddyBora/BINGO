package com.bingo.multiplayer.domain.engine

import com.bingo.multiplayer.domain.model.*
import com.bingo.multiplayer.domain.network.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LobbyAndChatPresentationTest {
    private val now = 100_000L
    private val friends = listOf(Friend("kevin", "kevin", "Kevin"), Friend("phil", "phil", "Phil"), Friend("otto", "otto", "Otto"))
    private val players = listOf(Player("bob", "Bob", username = "bob"), Player("kevin", "Kevin", username = "kevin"))
    private fun presence(status: String, time: Long = now) = PlayerPresence(status = status, timestamp = time)
    private fun notice(id: Long, name: String, subject: String, text: String = "$name disconnected") =
        InGameChatMessage(id = id, text = text, isSelf = false, isSystemMessage = true, presencePlayerId = subject)

    @Test fun onlineArrivalMovesOttoAheadOfOfflineFriends() {
        val result = LobbyFriendOrdering.onlineFirst(friends, mapOf("otto" to presence("ONLINE")), now)
        assertEquals(listOf("otto", "kevin", "phil"), result.map { it.uid })
        assertEquals(listOf("kevin", "phil", "otto"), friends.map { it.uid })
    }

    @Test fun availableFriendsPrecedeBusyAndOfflineWithoutChangingActions() {
        val map = mapOf("kevin" to presence("PLAYING"), "otto" to presence("ONLINE"))
        assertEquals(listOf("otto", "kevin", "phil"), LobbyFriendOrdering.onlineFirst(friends, map, now).map { it.uid })
        assertTrue(FriendPlayAvailability.of(null, now).canContact)
        assertEquals(FriendPlayAvailability.BUSY, FriendPlayAvailability.of(map["kevin"], now))
    }

    @Test fun ordinaryHeartbeatsDoNotShuffleFriendsWithinTheSameStatus() {
        val all = friends.associate { it.username to presence("ONLINE") }
        assertEquals(friends, LobbyFriendOrdering.onlineFirst(friends, all, now))
        assertEquals(friends, LobbyFriendOrdering.onlineFirst(friends, all + ("otto" to presence("ONLINE", now + 1000)), now + 1000))
    }

    @Test fun expiredPresenceDoesNotPromoteAnOfflineFriend() {
        val map = mapOf("kevin" to presence("ONLINE", now - 15_000), "otto" to presence("ONLINE"))
        assertEquals(listOf("otto", "kevin", "phil"), LobbyFriendOrdering.onlineFirst(friends, map, now).map { it.uid })
    }

    @Test fun usernameNormalizationAndFreshPresenceBeatCachedOfflineFlag() {
        val source = listOf(friends[0], friends[2].copy(username = " @Otto ", isOnline = false))
        assertEquals("otto", LobbyFriendOrdering.onlineFirst(source, mapOf("otto" to presence("ONLINE")), now).first().uid)
    }

    @Test fun ownerDoesNotSeeTheirOwnMinimizedDisconnectedOrReconnectedNotices() {
        val shared = listOf("Bob minimized the app", "Bob disconnected", "🟢 Bob reconnected").mapIndexed { i, text ->
            notice(i.toLong(), "Bob", "bob", text)
        }
        assertTrue(MatchSystemMessages.visibleTo(shared, "bob", players).isEmpty())
        assertEquals(shared, MatchSystemMessages.visibleTo(shared, "kevin", players))
        assertEquals(3, shared.size)
    }

    @Test fun typedNoticesUseIdentityEvenWhenTwoPlayersHaveTheSameName() {
        val roster = players + Player("other-bob", "Bob")
        val mine = notice(1, "Bob", "bob")
        val other = notice(2, "Bob", "other-bob")
        assertEquals(listOf(other), MatchSystemMessages.visibleTo(listOf(mine, other), "bob", roster))
    }

    @Test fun normalizedPlayerIdentityAlsoHidesOwnPresenceNotice() {
        assertTrue(MatchSystemMessages.visibleTo(listOf(notice(1, "Bob", "u_bob")), "@bob", players).isEmpty())
    }

    @Test fun realPlayerChatAndGeneralSystemMessagesRemainVisible() {
        val ordinary = InGameChatMessage(1, "Bob disconnected", senderPlayerId = "bob")
        val general = InGameChatMessage(2, "Let's play!", isSystemMessage = true)
        assertEquals(listOf(ordinary, general), MatchSystemMessages.visibleTo(listOf(ordinary, general), "bob", players))
    }

    @Test fun oldSavedPresenceNoticesWithoutMetadataAreAlsoHiddenForTheirOwner() {
        val old = Json.decodeFromString<InGameChatMessage>("""{"id":1,"text":"🟢 Bob reconnected","isSystemMessage":true}""")
        assertEquals("", old.presencePlayerId)
        assertTrue(MatchSystemMessages.visibleTo(listOf(old), "bob", players).isEmpty())
        assertEquals(listOf(old), MatchSystemMessages.visibleTo(listOf(old), "kevin", players))
    }

    @Test fun ambiguousLegacyNamesNeverHideAnotherPlayersNotice() {
        val old = InGameChatMessage(1, "Bob disconnected", isSystemMessage = true)
        assertEquals(listOf(old), MatchSystemMessages.visibleTo(listOf(old), "bob", players + Player("other-bob", "Bob")))
    }

    @Test fun snapshotCodecRetainsPresenceSubjectAndFiltersPerViewerAfterRejoin() {
        val shared = listOf(notice(1, "Bob", "bob"), notice(2, "Kevin", "kevin"))
        val packet = RoomMessagePacket(type = "GAME_SYNC", seed = 42L, chatMessages = shared)
        val restored = FastPacketCodec.decode(FastPacketCodec.encode(packet)).chatMessages
        assertEquals(shared, restored)
        assertEquals(listOf(shared[1]), MatchSystemMessages.visibleTo(restored, "bob", players))
        assertEquals(listOf(shared[0]), MatchSystemMessages.visibleTo(restored, "kevin", players))
    }

    @Test fun acceptedTimeoutsProduceTheSameVisibleNoticeInOnlineAndNearbyModes() {
        val boards = players.associate { it.id to BingoEngine().generateBoard(5, if (it.id == "bob") 11L else 12L) }
        for (peerMode in listOf(false, true)) {
            val context = MultiplayerGameplayContext(42, "kevin", "kevin", players, players, allowPeerTimeouts = peerMode)
            val state = MultiplayerGameplayState(emptyList(), boards.getValue("kevin"), boards.getValue("bob"), boards, 1, "bob", 0)
            val packet = MultiplayerGameplayReducer.timeoutPacket(42, "kevin", "bob", 1, "kevin")
            val accepted = MultiplayerGameplayReducer.timeout(packet, context, state)
            assertTrue(accepted.accepted)
            val message = MatchSystemMessages.turnSkipped(42, "bob", "Bob", 1, now)
            assertNotNull("Accepted timeout must have a chat notice in either mode", message)
            assertTrue(message!!.isSystemMessage)
            assertEquals("⏳ Bob's turn was skipped.", message.text)
            assertEquals(listOf(message), MatchSystemMessages.visibleTo(listOf(message), "bob", players))
            assertEquals(listOf(message), MatchSystemMessages.visibleTo(listOf(message), "kevin", players))
            assertFalse(MultiplayerGameplayReducer.timeout(packet, context, accepted.state).accepted)
        }
    }

    @Test fun replayedTimeoutAndSnapshotCannotDuplicateTheSameSkippedTurnNotice() {
        val first = MatchSystemMessages.turnSkipped(42, "bob", "Bob", 1, now)
        val replay = MatchSystemMessages.turnSkipped(42, "bob", "Bob", 1, now + 5000)
        assertNotNull(first); assertNotNull(replay)
        assertEquals(first!!.id, replay!!.id)
        assertEquals(listOf(first), MatchSystemMessages.merge(listOf(first), listOf(replay)))
        assertNotEquals(first.id, MatchSystemMessages.turnSkipped(42, "bob", "Bob", 3, now + 10000)!!.id)
        assertNotEquals(first.id, MatchSystemMessages.turnSkipped(43, "bob", "Bob", 1, now)!!.id)
    }

    @Test fun invalidTurnsDoNotGenerateMisleadingSkipNotices() {
        assertNull(MatchSystemMessages.turnSkipped(42, "bob", "Bob", 0, now))
        assertNull(MatchSystemMessages.turnSkipped(42, "", "", 1, now))
        assertNull(MatchSystemMessages.turnSkipped(0, "bob", "Bob", 1, now))
    }
}
