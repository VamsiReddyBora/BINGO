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
            committedEpisodeCount = 1,
            accountUid = "user_me"
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
            currentTurnPlayerId = "user_peer",
            accountUid = "user_bob"
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

        // Unscoped clear preserves scoped account save
        OngoingMatchStore.clearOngoingMatch(context = null, accountUid = null)
        assertNotNull(OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_temp"))

        // Scoped clear removes the specified account
        val cleared = OngoingMatchStore.clearOngoingMatch(context = null, accountUid = "user_temp")
        assertTrue(cleared)
        assertNull(OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_temp"))
    }

    @Test
    fun `test HB11 - Result tombstone prevents rejoining concluded match and clears ongoing match`() {
        val matchData = OngoingMatchData(
            roomCode = "TOMB_ROOM",
            matchSeed = 445566L,
            boardSize = 5,
            is1v1Match = true,
            accountUid = "user_me",
            participants = listOf(
                Player(id = "user_me", displayName = "Me"),
                Player(id = "user_peer", displayName = "Peer")
            )
        )
        OngoingMatchStore.saveOngoingMatch(context = null, matchData = matchData)
        assertNotNull(OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_me"))

        // Record final result tombstone
        val recorded = OngoingMatchStore.recordMatchResultTombstone(
            context = null,
            roomCode = "TOMB_ROOM",
            matchSeed = 445566L,
            winnerUid = "user_me",
            winReason = "Opponent Unreachable (Claimed Victory)",
            episodeCount = 3,
            accountUid = "user_me"
        )
        assertTrue(recorded)

        val tombstone = OngoingMatchStore.getMatchResultTombstone(context = null, 445566L, accountUid = "user_me")
        assertNotNull(tombstone)
        assertEquals("user_me", tombstone?.winnerUid)
        assertEquals("Opponent Unreachable (Claimed Victory)", tombstone?.winReason)
        assertEquals(3, tombstone?.episodeCount)

        // Attempting to retrieve ongoing match for this concluded seed returns null and wipes ongoing match
        val retrieved = OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_me")
        assertNull("Concluded match with tombstone must not be retrievable", retrieved)
    }

    @Test
    fun `test HB03 - clearNullableEpisodeFields clears currentEpisodeId when episode closes`() {
        val matchData = OngoingMatchData(
            roomCode = "EP_CLOSE_ROOM",
            matchSeed = 778899L,
            boardSize = 5,
            is1v1Match = true,
            accountUid = "user_me",
            committedEpisodeCount = 2,
            currentEpisodeId = "ep_778899_2_12345",
            isEpisodeOpen = true,
            peerAvailabilityState = "UNREACHABLE"
        )
        OngoingMatchStore.saveOngoingMatch(context = null, matchData = matchData)

        // When recovery completes, currentEpisodeId becomes null and isEpisodeOpen becomes false
        val updated = OngoingMatchStore.updateMatchGameState(
            context = null,
            committedEpisodeCount = 2,
            currentEpisodeId = null,
            isEpisodeOpen = false,
            peerAvailabilityState = "RESPONSIVE",
            clearNullableEpisodeFields = true,
            accountUid = "user_me"
        )
        assertTrue(updated)

        val loaded = OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_me")
        assertNotNull(loaded)
        assertNull("currentEpisodeId must be cleared to null when clearNullableEpisodeFields is true", loaded?.currentEpisodeId)
        assertFalse("isEpisodeOpen must be updated to false", loaded?.isEpisodeOpen == true)
        assertEquals("RESPONSIVE", loaded?.peerAvailabilityState)
    }

    @Test
    fun `test HB13 - Legacy match migrated to scoped key on first read`() {
        val legacyJson = """
            {
                "roomCode": "LEGACY_MIGRATE",
                "matchSeed": 332211,
                "boardSize": 5,
                "isHost": true,
                "participants": [
                    {"id": "user_migrated", "displayName": "Migrated Player", "isHost": true},
                    {"id": "user_opponent", "displayName": "Opponent", "isHost": false}
                ],
                "turnNumber": 1,
                "currentTurnPlayerId": "user_migrated",
                "accountUid": "user_migrated",
                "is1v1Match": true
            }
        """.trimIndent()

        // Place legacy record directly in fakePrefs under KEY_MATCH_DATA
        fakePrefs.edit().putString("active_match_data", legacyJson).commit()

        // Read using accountUid "user_migrated"
        val loaded = OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_migrated")
        assertNotNull("Legacy match should be migrated and retrieved", loaded)
        assertEquals("LEGACY_MIGRATE", loaded?.roomCode)

        // Verify that the scoped key now exists in prefs
        assertTrue(fakePrefs.contains("active_match_data_user_migrated"))

        // Different account cannot see it
        val forOther = OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_other")
        assertNull("Different account must not see migrated match", forOther)
    }

    @Test
    fun `test F005 - Participant B cannot adopt A-owned record during legacy migration`() {
        val legacyJson = """
            {
                "roomCode": "A_OWNED_ROOM",
                "matchSeed": 998877,
                "boardSize": 5,
                "isHost": true,
                "participants": [
                    {"id": "account_a", "displayName": "Player A", "isHost": true},
                    {"id": "account_b", "displayName": "Player B", "isHost": false}
                ],
                "turnNumber": 1,
                "currentTurnPlayerId": "account_a",
                "accountUid": "account_a",
                "is1v1Match": true
            }
        """.trimIndent()

        // Legacy record has explicit owner "account_a"
        fakePrefs.edit().putString("active_match_data", legacyJson).commit()

        // Account B requests it
        val forB = OngoingMatchStore.getOngoingMatch(context = null, accountUid = "account_b")
        assertNull("Account B must NOT adopt Account A's record even though B is a participant", forB)
        assertFalse("Scoped key for B must NOT be created from A's data", fakePrefs.contains("active_match_data_account_b"))
    }

    @Test
    fun `test F002 - Tombstone immutability rejects conflicting write and allows idempotent identical write`() {
        val firstRecorded = OngoingMatchStore.recordMatchResultTombstone(
            context = null,
            roomCode = "TOMB_ROOM",
            matchSeed = 112233L,
            winnerUid = "account_winner",
            winReason = "Bingo",
            episodeCount = 0,
            accountUid = "account_winner"
        )
        assertTrue(firstRecorded)

        // Conflicting write (different winner)
        val conflictingRecorded = OngoingMatchStore.recordMatchResultTombstone(
            context = null,
            roomCode = "TOMB_ROOM",
            matchSeed = 112233L,
            winnerUid = "account_impostor",
            winReason = "Bingo",
            episodeCount = 0,
            accountUid = "account_winner"
        )
        assertFalse("Conflicting second result write must be rejected", conflictingRecorded)

        val tombstone = OngoingMatchStore.getMatchResultTombstone(context = null, 112233L, accountUid = "account_winner")
        assertNotNull(tombstone)
        assertEquals("account_winner", tombstone?.winnerUid)

        // Identical write is idempotent
        val identicalRecorded = OngoingMatchStore.recordMatchResultTombstone(
            context = null,
            roomCode = "TOMB_ROOM",
            matchSeed = 112233L,
            winnerUid = "account_winner",
            winReason = "Bingo",
            episodeCount = 0,
            accountUid = "account_winner"
        )
        assertTrue("Identical result write is idempotent and accepted", identicalRecorded)
    }

    @Test
    fun `test F007 - Durably saving received pause and resume updates pause metadata and remaining milliseconds`() {
        val initialMatch = OngoingMatchData(
            roomCode = "PAUSE_ROOM",
            matchSeed = 554433L,
            boardSize = 5,
            is1v1Match = true,
            accountUid = "user_me",
            isPaused = false,
            remainingTurnMillis = 28_000L
        )
        OngoingMatchStore.saveOngoingMatch(context = null, matchData = initialMatch)

        // Remote pause applied
        val pauseUpdated = OngoingMatchStore.updateMatchGameState(
            context = null,
            isPaused = true,
            remainingTurnMillis = 14_250L,
            pauseOwnerId = "user_opponent",
            accountUid = "user_me"
        )
        assertTrue(pauseUpdated)

        val pausedMatch = OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_me")
        assertNotNull(pausedMatch)
        assertTrue(pausedMatch?.isPaused == true)
        assertEquals(14_250L, pausedMatch?.remainingTurnMillis)
        assertEquals("user_opponent", pausedMatch?.pauseOwnerId)

        // Remote resume applied
        val resumeUpdated = OngoingMatchStore.updateMatchGameState(
            context = null,
            isPaused = false,
            remainingTurnMillis = 14_250L,
            pauseOwnerId = "",
            accountUid = "user_me"
        )
        assertTrue(resumeUpdated)

        val resumedMatch = OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_me")
        assertNotNull(resumedMatch)
        assertFalse(resumedMatch?.isPaused == true)
        assertEquals(14_250L, resumedMatch?.remainingTurnMillis)
        assertEquals("", resumedMatch?.pauseOwnerId)
    }

    @Test
    fun `test F007 - Actual turn IDs preserved across skipped turns in OngoingMatchStore`() {
        val initialMatch = OngoingMatchData(
            roomCode = "TURN_ID_ROOM",
            matchSeed = 667788L,
            boardSize = 5,
            is1v1Match = true,
            accountUid = "user_me",
            pickedNumbers = listOf(5),
            pickedByPlayers = listOf("user_me"),
            turnIds = listOf(1),
            turnNumber = 2
        )
        OngoingMatchStore.saveOngoingMatch(context = null, matchData = initialMatch)

        // Turn 2 was skipped due to timeout. Turn 3 picks 19.
        val updated = OngoingMatchStore.updateMatchGameState(
            context = null,
            pickedNumbers = listOf(5, 19),
            pickedByPlayers = listOf("user_me", "user_opponent"),
            turnIds = listOf(1, 3),
            turnNumber = 4,
            accountUid = "user_me"
        )
        assertTrue(updated)

        val loaded = OngoingMatchStore.getOngoingMatch(context = null, accountUid = "user_me")
        assertNotNull(loaded)
        assertEquals(listOf(5, 19), loaded?.pickedNumbers)
        assertEquals(listOf(1, 3), loaded?.turnIds)
    }
}
