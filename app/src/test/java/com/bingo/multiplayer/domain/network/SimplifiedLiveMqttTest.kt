package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Opt-in production MQTT integration test. Uses unique test IDs and cleans only its own retained topics. */
class SimplifiedLiveMqttTest {
    private object NoCloud : RoomRegistryGateway {
        override suspend fun sync(code: String, player: Player, known: List<Player>, banned: Set<String>): OnlineRoomSession? = null
        override suspend fun ready(code: String, uid: String, status: String, version: Long) {}
        override suspend fun remove(code: String, uid: String) {}
        override suspend fun close(code: String) {}
    }
    private suspend fun until(label: String, condition: () -> Boolean) {
        withTimeout(20_000) { while (!condition()) delay(25) }
        println("PASS: $label")
    }
    @Test fun `broker echoes commits and retains commands snapshots and departure after both clients close`() = runBlocking {
        assumeTrue("Opt in with BINGO_LIVE_MQTT=1", System.getenv("BINGO_LIVE_MQTT") == "1")
        val code = "CODEX_SIM_" + UUID.randomUUID().toString().take(12).uppercase()
        val seed = System.currentTimeMillis()
        val ids = listOf("qa_kevin_$seed", "qa_stuart_$seed")
        val roster = ids.mapIndexed { i, id -> Player(id, if (i == 0) "Kevin QA" else "Stuart QA", isHost = i == 0) }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = OnlineRoomSyncManager(scope, NoCloud)
        val b = OnlineRoomSyncManager(scope, NoCloud)
        val aq = CopyOnWriteArrayList<RoomMessagePacket>()
        val bq = CopyOnWriteArrayList<RoomMessagePacket>()
        val topics = CopyOnWriteArrayList<String>()
        val cleaner = MqttAsyncClient(NetworkConfig.BROKER_URL, "qa-clean-${UUID.randomUUID()}", MemoryPersistence())
        try {
            cleaner.connect(MqttConnectOptions().apply { isCleanSession = true; connectionTimeout = 5; NetworkConfig.applyMqttOptions(this) }).waitForCompletion(7_000)
            cleaner.subscribe("${PeerMatchRecovery.topic(code,seed)}/#", 1) { topic, _ -> if (!topics.contains(topic)) topics.add(topic) }.waitForCompletion(4_000)
            topics += "bingo/v5/room/$code/availability/${ids[0]}"
            topics += "bingo/v5/room/$code/availability/${ids[1]}"
            topics += "bingo/emqx7/room/$code/availability/${ids[0]}"
            topics += "bingo/emqx7/room/$code/availability/${ids[1]}"
            scope.launch(start = CoroutineStart.UNDISPATCHED) { a.incomingPackets.collect { aq.add(it) } }
            scope.launch(start = CoroutineStart.UNDISPATCHED) { b.incomingPackets.collect { bq.add(it) } }
            a.connectToRoom(code, roster[0], roster, seed)
            b.connectToRoom(code, roster[1], roster, seed)
            until("both real production managers completed retained recovery") {
                aq.any { it.type == "PEER_SYNC_READY" && it.seed == seed } && bq.any { it.type == "PEER_SYNC_READY" && it.seed == seed }
            }
            val boards = roster.mapIndexed { i, p -> p.id to BingoEngine().generateBoard(5, seed + i) }.toMap()
            val now = System.currentTimeMillis()
            val base = OngoingMatchData(code, seed, 5, accountUid = ids[0], participants = roster,
                playerBoard = boards.getValue(ids[0]), opponentBoard = boards.getValue(ids[1]), allPlayerBoards = boards,
                randomizedTurnOrder = roster, currentTurnPlayerId = ids[0], startedAt = now, lastPlayerActivityAt = now,
                turnClock = MultiplayerTurnClock.start(1, ids[0], now), simplifiedRulesVersion = 1)
            a.publishPeerSnapshot(RoomMessagePacket("GAME_SYNC", playerId = ids[0], seed = seed, sequence = 1,
                turnNumber = 1, currentTurnPlayerId = ids[0], turnClock = base.turnClock,
                boardNumbers = boards.mapValues { it.value.cells.map { c -> c.number } },
                lastPlayerActivityAt = now, simplifiedRulesVersion = 1))
            until("complete state snapshot arrived at the second peer") { bq.any { it.type == "GAME_SYNC" && it.sequence == 1L } }
            val packet = RoomMessagePacket("PICK_NUMBER", playerId = ids[0], senderPlayerId = ids[0], seed = seed,
                number = 7, closedTurnNumber = 1, turnNumber = 2, currentTurnPlayerId = ids[1], timestamp = System.currentTimeMillis(),
                closedTurnClock = base.turnClock, turnClock = MultiplayerTurnClock.start(2,ids[1],System.currentTimeMillis()), simplifiedRulesVersion = 1)
            a.broadcastPacket(packet)
            until("publisher and opponent consume the same committed pick") {
                aq.any { it.type == "PICK_NUMBER" && it.number == 7 } && bq.any { it.type == "PICK_NUMBER" && it.number == 7 }
            }
            val fromA = aq.first { it.type == "PICK_NUMBER" && it.number == 7 }
            val fromB = bq.first { it.type == "PICK_NUMBER" && it.number == 7 }
            assertEquals(fromA, fromB)
            val committed = PeerMatchRecovery.merge(base,listOf(fromA),System.currentTimeMillis())
            assertEquals(listOf(7),committed.pickedNumbers)
            a.disconnect(); b.disconnect()
            val lookup = PeerMatchRecovery.read(code,seed)
            assertTrue("Broker must answer a cold recovery read",lookup.available)
            val cold = PeerMatchRecovery.merge(base,lookup.packets,System.currentTimeMillis())
            assertEquals(committed.pickedNumbers,cold.pickedNumbers)
            assertEquals(committed.turnClock,cold.turnClock)
            assertEquals(committed.allPlayerBoards,cold.allPlayerBoards)
            println("PASS: accepted pick recovered after both original clients closed, without their next snapshot")
            val exit = PendingMatchExit(ids[0], code, RoomMessagePacket("SURRENDER", playerId = ids[0], senderPlayerId = ids[0],
                seed = seed, roomCode = code, timestamp = System.currentTimeMillis(), simplifiedRulesVersion = 1))
            assertTrue(PeerMatchRecovery.publishExit(exit))
            val afterExit = PeerMatchRecovery.read(code,seed)
            assertTrue(afterExit.available)
            val final = PeerMatchRecovery.merge(base,afterExit.packets,System.currentTimeMillis())
            assertTrue(final.isMatchFinished)
            assertEquals(ids[1],final.winnerPlayerId)
            assertEquals("Opponent Surrendered",final.winReason)
            println("PASS: durable explicit exit produces the eligible opponent's result during cold recovery")
        } finally {
            a.disconnect(); b.disconnect()
            a.drainForTests(); b.drainForTests()
            delay(300)
            if (cleaner.isConnected) {
                for (topic in topics.distinct()) {
                    cleaner.publish(topic, MqttMessage(ByteArray(0)).apply { qos = 1; isRetained = true }).waitForCompletion(3_000)
                }
                cleaner.disconnect().waitForCompletion(2_000)
            }
            runCatching { cleaner.close(true) }
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
            scope.cancel()
        }
    }
}
