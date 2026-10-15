package com.bingo.multiplayer.domain

import com.bingo.multiplayer.domain.engine.FriendCareerStats
import com.bingo.multiplayer.domain.engine.HeadToHeadCalculator
import com.bingo.multiplayer.domain.engine.MatchScorecardStats
import com.bingo.multiplayer.domain.model.Friend
import com.bingo.multiplayer.domain.model.MatchRecord
import com.bingo.multiplayer.domain.network.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SmallFeatureRestorationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun forfeitScorecardDoesNotInventBingoLinesFromVictory() {
        val record = MatchRecord("forfeit", "Online Match", "Stuart", true,
            linesCompleted = 5, finalBoardNumbers = (1..25).toList(), markedNumbers = listOf(3, 16))
        assertEquals(0, MatchScorecardStats.completedLines(record.boardSize, record.finalBoardNumbers, record.markedNumbers))
    }

    @Test fun scorecardCountsRealLinesForEverySupportedBoardSize() {
        for (size in 5..10) {
            val engine = com.bingo.multiplayer.domain.engine.BingoEngine()
            var board = engine.generateBoard(size, 42L)
            val picked = mutableListOf<Int>()
            // Covers partial rows, rows, columns and both diagonals as the board fills.
            for (number in board.cells.map { it.number }) {
                picked.add(number)
                board = engine.markCell(board, number, "player", true, picked.size)
                assertEquals(board.completedLinesCount,
                    MatchScorecardStats.completedLines(size, board.cells.map { it.number }, picked))
            }
            assertEquals(2 * size + 2, MatchScorecardStats.completedLines(size, board.cells.map { it.number }, picked))
        }
    }

    @Test fun missingOrInvalidScorecardSnapshotStaysUnknown() {
        assertNull(MatchScorecardStats.completedLines(5, emptyList(), emptyList()))
        assertNull(MatchScorecardStats.completedLines(7, (1..25).toList(), emptyList()))
        assertNull(MatchScorecardStats.completedLines(99, emptyList(), emptyList()))
    }

    @Test fun oldFriendAndMatchRecordsRemainReadable() {
        val friend = json.decodeFromString<Friend>("""{"uid":"u1","username":"bob","displayName":"Bob"}""")
        assertEquals(0, friend.gamesWon)
        assertEquals(1, friend.level)
        val record = json.decodeFromString<MatchRecord>("""{"id":"m1","mode":"Online Match","opponentName":"Bob","didWin":true}""")
        assertTrue(record.finalBoardNumbers.isEmpty())
        assertEquals("", record.opponentUid)
    }

    @Test fun latestLossResetsStreakWithoutLosingCareerTotals() {
        val friend = Friend("u1", "bob", "Bob", gamesPlayed = 10, gamesWon = 7, currentStreak = 3, level = 4)
        val updated = FriendCareerStats.merge(friend, PlayerRegistryEntry("bob", "u1", "Bob",
            gamesPlayed = 11, gamesWon = 7, currentStreak = 0, level = 4))
        assertEquals(0, updated.currentStreak)
        assertEquals(11, updated.gamesPlayed)
        assertEquals(7, updated.gamesWon)
        assertEquals(4, updated.level)
    }

    @Test fun partialLegacyCareerDataDoesNotEraseKnownTotalsOrInventLevel() {
        val friend = Friend("u1", "bob", "Bob", gamesPlayed = 20, gamesWon = 15, level = 2)
        val updated = FriendCareerStats.merge(friend, PlayerRegistryEntry("@BOB", "u1", "Bob"))
        assertEquals(20, updated.gamesPlayed)
        assertEquals(15, updated.gamesWon)
        assertEquals(2, updated.level)
    }

    @Test fun statisticsFromAnotherAccountCannotModifyFriend() {
        val friend = Friend("bob-id", "bob", "Bob", gamesPlayed = 4)
        assertEquals(friend, FriendCareerStats.merge(friend, PlayerRegistryEntry("alice", "alice-id", "Alice", gamesPlayed = 50)))
    }

    @Test fun olderProfileCannotRestoreAStreakThatEndedWithTheLatestLoss() {
        val friend = Friend("u1", "bob", "Bob", gamesPlayed = 11, gamesWon = 7, currentStreak = 0)
        val updated = FriendCareerStats.merge(friend, PlayerRegistryEntry("bob", "u1", "Bob",
            gamesPlayed = 10, gamesWon = 7, currentStreak = 3))
        assertEquals(0, updated.currentStreak)
        assertEquals(11, updated.gamesPlayed)
    }

    @Test fun headToHeadUsesExactIdentityAndAvoidsSubstringMatches() {
        val records = listOf(
            MatchRecord("bobby", "Online Match", "Bobby", true, matchTitle = "Kevin vs Bobby Won"),
            MatchRecord("wrong-id", "Online Match", "Bob", true, opponentUid = "different-id"),
            MatchRecord("bob", "Online Match", "Bob", false, opponentUid = "bob-id")
        )
        val stats = HeadToHeadCalculator.calculate(records, "bob-id", "bob", "Bob")
        assertEquals(1, stats.totalMatches)
        assertEquals(1, stats.losses)
    }

    @Test fun legacyAndDetailedPresenceHaveCompatibleLabels() {
        val legacy = json.decodeFromString<PlayerPresence>("""{"username":"bob","status":"PLAYING","timestamp":1000}""")
        assertEquals("playing", PresenceActivityFormatter.activeLabel(legacy, 1001))
        val rich = legacy.copy(activityDetail = "vs AI (Easy)")
        assertEquals("playing (vs AI (Easy))", PresenceActivityFormatter.activeLabel(rich, 1001))
        assertEquals(rich, json.decodeFromString<PlayerPresence>(json.encodeToString(rich)))
        assertEquals("in-lobby (Room ABC123)", PresenceActivityFormatter.activeLabel(
            rich.copy(status = "IN_LOBBY", activityDetail = "Room ABC123"), 1001))
    }

    @Test fun staleOrUnknownPresenceCannotShowAnActiveGame() {
        val rich = PlayerPresence("bob", "PLAYING", 1000, activityDetail = "Online Match")
        assertNull(PresenceActivityFormatter.activeLabel(rich, 16000))
        assertNull(PresenceActivityFormatter.activeLabel(rich.copy(timestamp = 0), 1000))
        assertNull(PresenceActivityFormatter.activeLabel(rich.copy(status = "OFFLINE"), 1001))
        assertEquals("online", PresenceActivityFormatter.activeLabel(rich.copy(status = "ONLINE"), 1001))
    }

    @Test fun roomQrRoundTripsWithoutChangingTheSelectedRoom() {
        val room = OnlineRoomQrPayload("ABC123", "Kevin", 7)
        assertEquals(room, QrCodeHelper.parseRoomQrContent(QrCodeHelper.createRoomQrContent(room)))
        assertEquals("ABC123", QrCodeHelper.extractRoomCode("Join my Bingo room! Room code: abc123"))
        assertEquals("ABC123", QrCodeHelper.extractRoomCode("bingo://room?code=ABC123"))
    }

    @Test fun invalidQrCannotSilentlyTruncateIntoAnotherRoom() {
        assertNull(QrCodeHelper.parseRoomQrContent("BINGO_ROOM:ABC123extra"))
        assertNull(QrCodeHelper.parseRoomQrContent("bingo://room?code=ABC123extra"))
        assertNull(QrCodeHelper.parseRoomQrContent("""BINGO_ROOM:{"roomCode":"ABC12!"}"""))
        assertNull(QrCodeHelper.parseRoomQrContent("""BINGO_ROOM:{"roomCode":"ABC123","boardSize":99}"""))
        assertNull(QrCodeHelper.extractRoomCode("Please join me"))
    }

    @Test fun profileAndLanQrFormatsRemainDistinct() {
        val profile = PlayerProfileQrPayload("bob", "bob-id", "Bob", 3)
        assertEquals(profile, QrCodeHelper.parseProfileQrContent(QrCodeHelper.createProfileQrContent(profile)))
        assertNull(QrCodeHelper.parseRoomQrContent(QrCodeHelper.createProfileQrContent(profile)))
        val lan = NearbyHostQrPayload("test-wifi", "", "ABC123", "192.168.1.2", "Kevin")
        assertEquals(lan.roomCode, QrCodeHelper.parseQrContent(QrCodeHelper.createQrContent(lan))?.roomCode)
    }

    @Test fun chatTypingKeepsMatchIdentityAndExistingJsonPacketCompatibility() {
        val packet = RoomMessagePacket(type = "CHAT_TYPING", playerId = "bob-id", username = "Bob",
            displayName = "1", seed = Long.MAX_VALUE, timestamp = 1000)
        val decoded = FastPacketCodec.decode(FastPacketCodec.encode(packet))
        assertEquals("CHAT_TYPING", decoded.type)
        assertEquals(Long.MAX_VALUE, decoded.seed)
        assertEquals("1", decoded.displayName)
        assertTrue(decoded.pickedHistory.isEmpty())
    }
}
