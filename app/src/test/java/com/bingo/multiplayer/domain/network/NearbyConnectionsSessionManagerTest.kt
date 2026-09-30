package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NearbyConnectionsSessionManagerTest {

    private lateinit var testScope: CoroutineScope
    private lateinit var localTransport: SimulatedNearbyTransport
    private lateinit var remoteTransport: SimulatedNearbyTransport
    private lateinit var sessionManagerLocal: NearbyConnectionsSessionManager

    @Before
    fun setUp() {
        testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        localTransport = SimulatedNearbyTransport(testScope)
        remoteTransport = SimulatedNearbyTransport(testScope)

        localTransport.myEndpointId = "peer_local"
        remoteTransport.myEndpointId = "peer_remote"

        // Connect both simulated transports to each other
        localTransport.simulateConnection("peer_remote", remoteTransport)
        remoteTransport.simulateConnection("peer_local", localTransport)

        sessionManagerLocal = NearbyConnectionsSessionManager(
            transport = localTransport,
            scope = testScope,
            heartbeatIntervalMs = 200L,
            lostPeerTimeoutMs = 600L,
            turnLockWatchdogTimeoutMs = 400L
        )
    }

    @After
    fun tearDown() {
        sessionManagerLocal.disconnect()
        testScope.cancel()
    }

    @Test
    fun testLatencySimulation_150to500ms_payloadArrivesWithoutDeadlock() = runBlocking {
        // Configure network degradation: 150ms to 300ms artificial latency + 20ms jitter
        val degradationConfig = NetworkSimulatorConfig(
            minLatencyMs = 150L,
            maxLatencyMs = 300L,
            jitterMs = 20L,
            packetDropRate = 0.0
        )
        sessionManagerLocal.setNetworkSimulatorConfig(degradationConfig)

        // Lock turn with watchdog
        sessionManagerLocal.lockTurnWithWatchdog()
        assertTrue("Turn must initially be locked", sessionManagerLocal.isTurnLocked.value)

        // Send number pick across high-latency link
        val startTime = System.currentTimeMillis()
        val success = sessionManagerLocal.sendPick(
            endpointId = "peer_remote",
            number = 15,
            turnNumber = 2,
            playerId = "player_1",
            pickedHistory = listOf(5, 15),
            currentTurnPlayerId = "player_2"
        )
        assertTrue(success)

        // Wait for incoming payload at remote transport
        val received = withTimeout(1500L) {
            remoteTransport.incomingPayloads.first()
        }
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals("peer_local", received.first)
        assertTrue("Payload must be PickNumber", received.second is NearbyPayload.PickNumber)
        val pickPayload = received.second as NearbyPayload.PickNumber
        assertEquals(15, pickPayload.number)
        assertEquals(listOf(5, 15), pickPayload.pickedHistory)

        // Verify latency was simulated (at least minLatency - jitter)
        assertTrue("Delivery elapsed ($elapsed ms) must reflect artificial latency", elapsed >= 120L)
    }

    @Test
    fun testTurnLockWatchdog_preventsDeadlockOnPacketDrop() = runBlocking {
        // Configure 100% packet drop on transport to simulate total packet loss
        val dropConfig = NetworkSimulatorConfig(packetDropRate = 1.0)
        sessionManagerLocal.setNetworkSimulatorConfig(dropConfig)

        var watchdogFired = false
        // Send pick that gets dropped with timeout callback
        sessionManagerLocal.sendPick(
            endpointId = "peer_remote",
            number = 9,
            turnNumber = 1,
            playerId = "p1",
            pickedHistory = listOf(9),
            currentTurnPlayerId = "p2",
            onWatchdogTimeout = {
                watchdogFired = true
            }
        )


        // Wait for watchdog timer (400ms timeout configured in setUp)
        delay(600L)

        // Turn lock MUST be released by watchdog to prevent UI deadlock
        assertFalse("Turn lock must be released by watchdog timeout", sessionManagerLocal.isTurnLocked.value)
        assertTrue("Watchdog timeout callback should have fired", watchdogFired)
    }

    @Test
    fun testStateSynchronization_reconcilesMissingHistory() = runBlocking {
        // Simulate remote sending a state sync packet with updated history
        val syncPayload = NearbyPayload.StateSync(
            turnNumber = 4,
            currentTurnPlayerId = "peer_local",
            pickedHistory = listOf(3, 7, 12)
        )

        // Deliver sync to local
        localTransport.receivePayload("peer_remote", syncPayload)

        val latest = withTimeout(1000L) {
            sessionManagerLocal.latestPayloads.first()
        }

        assertEquals("peer_remote", latest.first)
        assertTrue(latest.second is NearbyPayload.StateSync)
        val receivedSync = latest.second as NearbyPayload.StateSync
        assertEquals(listOf(3, 7, 12), receivedSync.pickedHistory)
        assertEquals(4, receivedSync.turnNumber)
    }

    @Test
    fun testLostPeerDetection_triggersPauseOrForfeitEvent() = runBlocking {
        // Disconnect remote so heartbeats stop arriving
        remoteTransport.disconnect()

        // Collect peer status events from session manager
        val event = withTimeout(2500L) {
            sessionManagerLocal.peerStatusEvents.first { it is PeerStatusEvent.PeerLost }
        }

        assertTrue("Lost peer event must be emitted", event is PeerStatusEvent.PeerLost)
        val lostEvent = event as PeerStatusEvent.PeerLost
        assertEquals("peer_remote", lostEvent.endpointId)
        assertTrue(
            "Action must be Pause or Forfeit",
            lostEvent.action is LostPeerAction.Pause || lostEvent.action is LostPeerAction.Forfeit
        )
    }
}
