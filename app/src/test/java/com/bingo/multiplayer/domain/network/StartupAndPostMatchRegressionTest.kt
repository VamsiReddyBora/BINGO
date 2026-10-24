package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StartupAndPostMatchRegressionTest {
    private val host = Player("host", "Kevin", username = "kevin", isHost = true)
    private val guest = Player("guest", "Bob", username = "bob")
    private fun room() = OnlineRoomSession("ABC123", host.id, host.username, host.displayName,
        players = listOf(host, guest), metadataProtocolVersion = 7, sessionId = "lobby-original",
        status = "WAITING", currentSeed = 0L)
    private fun access() = PostMatchLobbyAccess("ABC123", 42L)
    private fun leave() = RoomMessagePacket("HOST_LEFT", roomCode = "ABC123", playerId = host.id,
        senderPlayerId = host.id, seed = 0L, lobbySessionId = "lobby-original")
    private suspend fun PostMatchLobbyAccess.refreshWith(result: Result<OnlineRoomSession?>, away: Boolean = false) =
        refresh(host.id, "lobby-original", { away }) { result }

    @Test fun localAuthenticationFinishesAfterTheExistingIntroWithoutRoomOrInternetDependency() = runTest {
        val auth = MutableStateFlow<AuthState>(AuthState.Authenticated(UserProfile("guest", "bob", "Bob")))
        assertEquals(auth.value, AppLaunchReadiness.awaitLocalAuth(auth))
        assertEquals(1000L, testScheduler.currentTime)
    }
    @Test fun unresolvedLocalAuthHasABoundedHalfSecondGrace() = runTest {
        val auth = MutableStateFlow<AuthState>(AuthState.Loading)
        assertEquals(AuthState.Loading, AppLaunchReadiness.awaitLocalAuth(auth))
        assertEquals(1500L, testScheduler.currentTime)
    }
    @Test fun aResolvedSignedOutAccountRoutesWithoutWaitingForOnlineAuthentication() = runTest {
        assertEquals(AuthState.Unauthenticated, AppLaunchReadiness.awaitLocalAuth(MutableStateFlow(AuthState.Unauthenticated)))
        assertEquals(1000L, testScheduler.currentTime)
    }
    @Test fun finalCheckpointOpensResultAndUnfinishedCheckpointKeepsExistingRejoinPath() {
        val final = OngoingMatchData("ABC123", 42L, 5, isMatchFinished = true, winnerPlayerId = host.id,
            participants = listOf(host, guest), accountUid = guest.id)
        val shown = mutableListOf<OngoingMatchData>()
        assertTrue(SavedMatchOpening.showResultIfFinished(final, shown::add))
        assertEquals(listOf(final), shown)
        assertFalse(SavedMatchOpening.showResultIfFinished(final.copy(isMatchFinished = false), shown::add))
        assertEquals(1, shown.size)
    }
    @Test fun appOnlineHostExplicitlyLeavingClosesTheLobbyWithoutTouchingResult() = runTest {
        val access = access()
        assertEquals(PostMatchLobbyStatus.AVAILABLE, access.refreshWith(Result.success(room())))
        assertTrue(access.acceptDeparture(leave(), host.id, "lobby-original"))
        assertEquals(PostMatchLobbyStatus.CLOSED, access.status.value)
        assertEquals(PostMatchLobbyStatus.CLOSED, access.refreshWith(Result.success(room())))
    }
    @Test fun backgroundProcessKillAndUnavailableBrokerAreTemporaryAndCanRecover() = runTest {
        val access = access()
        for (packet in listOf(leave().copy(type = "PLAYER_DISCONNECTED"), leave().copy(transportOnly = true),
                leave().copy(type = "PLAYER_PRESENCE", presenceStatus = "OFFLINE")))
            assertFalse(access.acceptDeparture(packet, host.id, "lobby-original"))
        assertEquals(PostMatchLobbyStatus.WAITING, access.refreshWith(Result.failure(java.io.IOException("offline"))))
        assertEquals(PostMatchLobbyStatus.WAITING, access.refreshWith(Result.success(null)))
        assertEquals(PostMatchLobbyStatus.WAITING, access.refreshWith(Result.success(room()), away = true))
        assertEquals(PostMatchLobbyStatus.AVAILABLE, access.refreshWith(Result.success(room())))
    }
    @Test fun onlyTheMatchingHostRoomAndLobbySessionCanCloseTheFinishedLobby() {
        val access = access()
        for (packet in listOf(leave().copy(playerId = guest.id), leave().copy(senderPlayerId = guest.id),
                leave().copy(roomCode = "OTHER"), leave().copy(lobbySessionId = "old-lobby"),
                leave().copy(seed = 42L), leave().copy(type = "SURRENDER"))) {
            assertFalse(access.acceptDeparture(packet, host.id, "lobby-original"))
        }
        assertEquals(PostMatchLobbyStatus.CHECKING, access.status.value)
        assertTrue(access.acceptDeparture(leave().copy(type = "LOBBY_EXPIRED"), host.id, "lobby-original"))
    }
    @Test fun returnRequiresAFreshSuccessfulReadBeforeChangingAnyGameState() = runTest {
        val access = access(); var enters = 0; var reads = 0
        assertFalse(access.enterIfAvailable(host.id, "lobby-original", { false }, { reads++; Result.success(null) }) { enters++ })
        assertEquals(0, enters)
        assertTrue(access.enterIfAvailable(host.id, "lobby-original", { false }, { reads++; Result.success(room()) }) { enters++ })
        assertEquals(1, enters); assertEquals(2, reads)
    }
    @Test fun explicitExitWinsAgainstAnEarlierInflightSuccessfulRoomRead() = runTest {
        val access = access(); val response = CompletableDeferred<Result<OnlineRoomSession?>>()
        var enters = 0
        val job = async { access.enterIfAvailable(host.id, "lobby-original", { false }, { response.await() }) { enters++ } }
        runCurrent()
        assertTrue(access.acceptDeparture(leave(), host.id, "lobby-original"))
        response.complete(Result.success(room()))
        assertFalse(job.await()); assertEquals(0, enters)
        assertEquals(PostMatchLobbyStatus.CLOSED, access.status.value)
    }
    @Test fun cancellationAndSlowRoomDiscoveryLeaveARecoverableWaitingState() = runTest {
        val access = access()
        val job = launch { access.refresh(host.id, "lobby-original", { false }) { awaitCancellation() } }
        runCurrent(); job.cancelAndJoin()
        assertEquals(PostMatchLobbyStatus.WAITING, access.status.value)
        assertEquals(PostMatchLobbyStatus.WAITING, access.refresh(host.id, "lobby-original", { false }) { delay(8000L); Result.success(room()) })
        assertEquals(2500L, testScheduler.currentTime)
        assertEquals(PostMatchLobbyStatus.AVAILABLE, access.refreshWith(Result.success(room())))
    }
    @Test fun confirmedClosureRoomReplacementAndAbsentHostPreventStaleLobbyEntry() = runTest {
        for (room in listOf(room().copy(status = "CLOSED"), room().copy(isTerminalClosed = true),
                room().copy(sessionId = "replacement"), room().copy(hostId = "other-host"),
                room().copy(players = listOf(guest)), room().copy(status = "PLAYING", currentSeed = 99L),
                room().copy(players = listOf(host.copy(lobbyReadyStatus = "LEFT_LOBBY"), guest)))) {
            assertEquals(PostMatchLobbyStatus.CLOSED, access().refreshWith(Result.success(room)))
        }
        assertEquals(PostMatchLobbyStatus.AVAILABLE, access().refreshWith(Result.success(room().copy(status = "PLAYING", currentSeed = 42L))))
    }
    @Test fun hostBackgroundingDuringTheCheckCannotEnterTheLobby() = runTest {
        val access = access(); var away = false; var enters = 0
        assertFalse(access.enterIfAvailable(host.id, "lobby-original", { away }, {
            away = true; Result.success(room())
        }) { enters++ })
        assertEquals(0, enters)
        assertEquals(PostMatchLobbyStatus.WAITING, access.status.value)
    }
    @Test fun overlappingReturnChecksDoNotDuplicateEntry() = runTest {
        val access = access(); val response = CompletableDeferred<Result<OnlineRoomSession?>>()
        var enters = 0
        val first = async { access.enterIfAvailable(host.id, "lobby-original", { false }, { response.await() }) { enters++ } }
        runCurrent()
        assertFalse(access.enterIfAvailable(host.id, "lobby-original", { false }, { error("must not run second read") }) { enters++ })
        response.complete(Result.success(room())); assertTrue(first.await()); assertEquals(1, enters)
    }

    @Test fun liveLobbyCheckDoesNotAcceptRetainedStateAfterTheHostStops() = runBlocking {
        val wire = EmqxWireFixture()
        val hs = MqttRoomMetadataTransport(wire, { host.id }, discoveryTimeoutMs = 500L)
        val gs = MqttRoomMetadataTransport(wire, { guest.id }, discoveryTimeoutMs = 500L)
        try {
            assertTrue(RoomMetadataCoordinator(hs).mutate("ABC123", initial = room().copy(players = listOf(host))) { it } is MutationResult.Applied)
            assertNotNull(gs.readLive("ABC123").getOrThrow())
            hs.close()
            assertNotNull("Old state still exists on the broker", gs.read("ABC123").getOrThrow())
            assertNull("Fresh lobby admission must not accept that old state", gs.readLive("ABC123").getOrThrow())
            assertEquals(PostMatchLobbyStatus.WAITING, access().refresh(host.id, "lobby-original", { false }, gs::readLive))
        } finally { hs.close(); gs.close() }
    }
}
