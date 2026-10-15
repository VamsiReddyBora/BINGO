package com.bingo.multiplayer.domain.network

import android.content.SharedPreferences
import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.model.Player
import com.bingo.multiplayer.domain.repository.AuthRepositoryTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit test suite validating durable checkpointing, schema migration, non-expiring 1v1 matches,
 * and account-scoped isolation for OngoingMatchStore (R01-R11).
 */
class OngoingMatchStoreRejoinTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private lateinit var fakePrefs: AuthRepositoryTest.FakeSharedPreferences
    private val bingoEngine = BingoEngine()

    @Before
    fun setup() {
        fakePrefs = AuthRepositoryTest.FakeSharedPreferences()
        OngoingMatchStore.customPrefs = fakePrefs
    }

    @After
    fun tearDown() {
        OngoingMatchStore.customPrefs = null
    }

    @Test
    fun `test R01 - Full 1v1 match state serialization and deserialization`() {
        val board = bingoEngine.generateBoard(5, seed = 12345L)
        val matchData = OngoingMatchData(
            roomCode = "ROOM42",
            matchSeed = 987654321L,
            boardSize = 5,
            isHost = true,
            participants = listOf(
                Player(id = "user_kevin", displayName = "Kevin", isHost = true),
                Player(id = "user_stuart", displayName = "Stuart", isHost = false)
            ),
            playerBoard = board,
            opponentBoard = board,
            pickedNumbers = listOf(7, 14, 21),
            pickedByPlayers = listOf("user_kevin", "user_stuart", "user_kevin"),
            turnIds = listOf(1, 2, 3),
            turnNumber = 4,
            currentTurnPlayerId = "user_stuart",
            is1v1Match = true,
            accountUid = "user_kevin",
            protocolVersion = 1,
            controllerEpoch = 1L,
            matchRevision = 5L,
            isPaused = true,
            remainingTurnMillis = 18_000L,
            pauseOwnerId = "user_kevin",
            committedEpisodeCount = 2,
            currentEpisodeId = "ep_abc_1",
            isEpisodeOpen = true,
            peerAvailabilityState = "UNREACHABLE"
        )

        val serialized = json.encodeToString(matchData)
        val decoded = json.decodeFromString<OngoingMatchData>(serialized)

        assertEquals("ROOM42", decoded.roomCode)
        assertEquals(987654321L, decoded.matchSeed)
        assertTrue(decoded.is1v1Match)
        assertEquals("user_kevin", decoded.accountUid)
        assertEquals(listOf(7, 14, 21), decoded.pickedNumbers)
        assertEquals(listOf(1, 2, 3), decoded.turnIds)
        assertEquals(4, decoded.turnNumber)
        assertEquals("user_stuart", decoded.currentTurnPlayerId)
        assertTrue(decoded.isPaused)
        assertEquals(18_000L, decoded.remainingTurnMillis)
        assertEquals("user_kevin", decoded.pauseOwnerId)
        assertEquals(2, decoded.committedEpisodeCount)
        assertEquals("ep_abc_1", decoded.currentEpisodeId)
        assertTrue(decoded.isEpisodeOpen)
        assertEquals("UNREACHABLE", decoded.peerAvailabilityState)
    }

    @Test
    fun `test R02 - Cold-launch recovery from durable state`() {
        val board = bingoEngine.generateBoard(5, seed = 54321L)
        val matchData = OngoingMatchData(
            roomCode = "RECOVER_ROOM",
            matchSeed = 112233L,
            boardSize = 5,
            isHost = false,
            participants = listOf(
                Player(id = "user_me", displayName = "Me", isHost = false),
                Player(id = "user_opponent", displayName = "Opponent", isHost = true)
            ),
            playerBoard = board,
            pickedNumbers = listOf(10, 20),
            turnIds = listOf(1, 2),
            turnNumber = 3,
            currentTurnPlayerId = "user_me",
            is1v1Match = true,
            accountUid = "user_me",
            committedEpisodeCount = 1,
            isPaused = false
        )

        // Save match
        val saved = OngoingMatchStore.saveOngoingMatch(context = null, matchData = matchData)
        assertTrue(saved)

        // Simulate app kill and cold-launch by re-reading through OngoingMatchStore
        val restored = OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_me")
        assertNotNull(restored)
        assertEquals("RECOVER_ROOM", restored?.roomCode)
        assertEquals(listOf(10, 20), restored?.pickedNumbers)
        assertEquals(listOf(1, 2), restored?.turnIds)
        assertEquals(1, restored?.committedEpisodeCount)
        assertFalse(restored?.isPaused == true)
    }

    @Test
    fun `test R03 - Backward compatibility deserializes legacy match JSON with safe defaults`() {
        // Legacy JSON without new 1v1 fields
        val legacyJson = """
            {
                "roomCode": "OLD123",
                "matchSeed": 11111,
                "boardSize": 5,
                "isHost": false,
                "participants": [
                    {"id": "p1", "displayName": "Player 1", "isHost": true},
                    {"id": "p2", "displayName": "Player 2", "isHost": false}
                ],
                "turnNumber": 2,
                "currentTurnPlayerId": "p2"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<OngoingMatchData>(legacyJson)
        assertEquals("OLD123", decoded.roomCode)
        assertEquals(11111L, decoded.matchSeed)
        assertFalse(decoded.is1v1Match)
        assertEquals("", decoded.accountUid)
        assertEquals(1, decoded.protocolVersion)
        assertFalse(decoded.isPaused)
        assertNull(decoded.remainingTurnMillis)
        assertEquals(0, decoded.committedEpisodeCount)
        assertNull(decoded.currentEpisodeId)
        assertFalse(decoded.isEpisodeOpen)
        assertEquals("RESPONSIVE", decoded.peerAvailabilityState)
    }

    @Test
    fun `test R04 - 1v1 match older than 8 minutes does NOT expire`() {
        val tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000L)
        val match1v1 = OngoingMatchData(
            roomCode = "NO_EXP",
            matchSeed = 5555L,
            boardSize = 5,
            is1v1Match = true,
            accountUid = "user_1",
            participants = listOf(
                Player(id = "user_1", displayName = "U1"),
                Player(id = "user_2", displayName = "U2")
            ),
            lastUpdatedAt = tenMinutesAgo
        )

        OngoingMatchStore.saveOngoingMatch(context = null, matchData = match1v1)

        val retrieved = OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_1")
        assertNotNull("1v1 match must never expire after 8 minutes", retrieved)
        assertEquals("NO_EXP", retrieved?.roomCode)
    }

    @Test
    fun `test R04b - Legacy group match older than 8 minutes expires cleanly`() {
        val tenMinutesAgo = System.currentTimeMillis() - (10 * 60 * 1000L)
        val groupMatch = OngoingMatchData(
            roomCode = "GRP_EXP",
            matchSeed = 7777L,
            boardSize = 5,
            is1v1Match = false,
            participants = listOf(
                Player(id = "user_1", displayName = "U1"),
                Player(id = "user_2", displayName = "U2"),
                Player(id = "user_3", displayName = "U3")
            ),
            lastUpdatedAt = tenMinutesAgo
        )

        OngoingMatchStore.saveOngoingMatch(context = null, matchData = groupMatch)

        val retrieved = OngoingMatchStore.getOngoingMatch(context = null)
        assertNull("3+ player match older than 8 minutes must expire", retrieved)
    }

    @Test
    fun `test R05 - Account scoped isolation prevents cross-account match leakage`() {
        val match = OngoingMatchData(
            roomCode = "PRIVATE1",
            matchSeed = 9999L,
            boardSize = 5,
            is1v1Match = true,
            accountUid = "user_alice",
            participants = listOf(
                Player(id = "user_alice", displayName = "Alice"),
                Player(id = "user_bob", displayName = "Bob")
            )
        )

        OngoingMatchStore.saveOngoingMatch(context = null, matchData = match)

        // Same account: retrieved successfully
        val forAlice = OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_alice")
        assertNotNull("Matching account should retrieve match", forAlice)
        assertEquals("PRIVATE1", forAlice?.roomCode)

        // Different account: rejected and returns null
        val forEve = OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_eve")
        assertNull("Different account must not access Alice's ongoing match", forEve)
    }

    @Test
    fun `test R06 - updateMatchGameState updates state and increments match revision`() {
        val board = bingoEngine.generateBoard(5, seed = 77777L)
        val initialMatch = OngoingMatchData(
            roomCode = "REV_TEST",
            matchSeed = 1234L,
            boardSize = 5,
            is1v1Match = true,
            accountUid = "user_me",
            matchRevision = 1L
        )

        OngoingMatchStore.saveOngoingMatch(context = null, matchData = initialMatch)

        val updated = OngoingMatchStore.updateMatchGameState(
            context = null,
            playerBoard = board,
            opponentBoard = board,
            allPlayerBoards = emptyMap(),
            pickedNumbers = listOf(5),
            pickedByPlayers = listOf("user_me"),
            turnNumber = 2,
            currentTurnPlayerId = "user_peer",
            isPaused = true,
            remainingTurnMillis = 22_000L,
            committedEpisodeCount = 1
        )
        assertTrue(updated)

        val loaded = OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_me")
        assertNotNull(loaded)
        assertEquals(2, loaded?.turnNumber)
        assertEquals(listOf(5), loaded?.pickedNumbers)
        assertTrue(loaded?.isPaused == true)
        assertEquals(22_000L, loaded?.remainingTurnMillis)
        assertEquals(1, loaded?.committedEpisodeCount)
        assertEquals(2L, loaded?.matchRevision) // Incremented from 1L to 2L
    }

    @Test
    fun `test R08 - Restoring committed episode count persists across updates`() {
        val board = bingoEngine.generateBoard(5, seed = 99999L)
        val initialMatch = OngoingMatchData(
            roomCode = "EP_TEST",
            matchSeed = 5678L,
            boardSize = 5,
            is1v1Match = true,
            accountUid = "user_bob",
            committedEpisodeCount = 2,
            currentEpisodeId = "ep_old",
            isEpisodeOpen = true
        )

        OngoingMatchStore.saveOngoingMatch(context = null, matchData = initialMatch)

        // Update match state without passing new episode info
        OngoingMatchStore.updateMatchGameState(
            context = null,
            playerBoard = board,
            opponentBoard = board,
            allPlayerBoards = emptyMap(),
            pickedNumbers = listOf(12),
            pickedByPlayers = listOf("user_bob"),
            turnNumber = 2,
            currentTurnPlayerId = "user_peer"
        )

        val loaded = OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_bob")
        assertNotNull(loaded)
        assertEquals(2, loaded?.committedEpisodeCount)
        assertEquals("ep_old", loaded?.currentEpisodeId)
        assertTrue(loaded?.isEpisodeOpen == true)
    }

    @Test
    fun `test R10 - Clear ongoing match wipes storage completely`() {
        val initialMatch = OngoingMatchData(
            roomCode = "WIPE_ME",
            matchSeed = 999L,
            boardSize = 5,
            is1v1Match = true,
            accountUid = "user_temp"
        )

        OngoingMatchStore.saveOngoingMatch(context = null, matchData = initialMatch)
        assertNotNull(OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_temp"))

        val cleared = OngoingMatchStore.clearOngoingMatch(context = null)
        assertTrue(cleared)
        assertNull(OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_temp"))
    }
}
