package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.*
import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID

/** Opt-in real MQTT test of both production lobby managers and match clients. Never joins a real user's room. */
object LiveLobbyStartProbe {
    private object NoCloud : RoomRegistryGateway {
        override suspend fun sync(code: String, player: Player, known: List<Player>, banned: Set<String>): OnlineRoomSession? = null
        override suspend fun ready(code: String, uid: String, status: String, version: Long) {}
        override suspend fun remove(code: String, uid: String) {}
        override suspend fun close(code: String) {}
    }
    private class Store : ReliableMatchCheckpoint {
        var accepted: ReliableMatchSnapshot? = null
        override fun save(snapshot: ReliableMatchSnapshot, pending: Boolean): Boolean { if (!pending) accepted = snapshot; return true }
        override fun saveHostWait(snapshot: ReliableMatchSnapshot?) = true
    }
    private fun transport(manager: OnlineRoomSyncManager) = object : ReliableMatchTransport {
        override val connected get() = manager.isConnected
        // This headless check cannot simulate Android process lifecycle/clock health.
        override val healthy get() = connected
        override suspend fun subscribe(seed: Long) = manager.reliableSubscribe(seed)
        override suspend fun publish(packet: RoomMessagePacket, retained: Boolean, stateTopic: Boolean) =
            manager.reliablePublish(packet, retained, stateTopic)
    }
    private suspend fun waitFor(label: String, condition: () -> Boolean) {
        withTimeout(20_000L) { while (!condition()) delay(20L) }
        println("PASS $label")
    }
    private fun clearRetained(code: String, seed: Long) {
        val mqtt = MqttAsyncClient(NetworkConfig.BROKER_URL, "start-clear-" + UUID.randomUUID().toString().take(8), MemoryPersistence())
        try {
            val options = MqttConnectOptions().apply {
                isCleanSession = true; connectionTimeout = 10
                NetworkConfig.applyMqttOptions(this)
            }
            mqtt.connect(options).waitForCompletion(15_000L)
            mqtt.publish("bingo/v4/match/$code/$seed/state", MqttMessage(ByteArray(0)).apply { qos = 1; isRetained = true }).waitForCompletion(5_000L)
            mqtt.disconnect().waitForCompletion(3_000L)
            println("PASS unique retained test state removed")
        } finally { mqtt.close() }
    }
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val code = args.firstOrNull() ?: "CODEXSTART_" + UUID.randomUUID().toString().take(12).uppercase()
        require(code.startsWith("CODEXSTART_"))
        val seed = 920041L
        val players = listOf(Player("google_" + "1".repeat(21), "Kevin test", "start_test_a", isHost = true, lobbyReadyStatus = "READY"),
            Player("google_" + "2".repeat(21), "Stuart test", "start_test_b", lobbyReadyStatus = "READY"))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val a = OnlineRoomSyncManager(scope, NoCloud)
        val b = OnlineRoomSyncManager(scope, NoCloud)
        var host: ReliableMatchClient? = null
        var guest: ReliableMatchClient? = null
        val starts = java.util.concurrent.CopyOnWriteArrayList<RoomMessagePacket>()
        val clock = object : Clock { override fun elapsedRealtime() = System.nanoTime() / 1_000_000 }
        try {
            a.connectToRoom(code, players[0], players)
            b.connectToRoom(code, players[1], players)
            waitFor("both production lobby managers connect") { a.isConnected && b.isConnected }
            val ids = players.map { it.id }
            check(a.awaitReliableCompatibility(ids, ids[0], 12_000))
            check(b.awaitReliableCompatibility(ids, ids[1], 12_000))
            println("PASS same build confirms lobby compatibility in both directions")
            val ar = scope.launch(start = CoroutineStart.UNDISPATCHED) { a.incomingPackets.collect { if (it.type.startsWith("MATCH_V2_")) host?.receive(it) } }
            val br = scope.launch(start = CoroutineStart.UNDISPATCHED) { b.incomingPackets.collect {
                if (it.type == "START_GAME") starts += it
                if (it.type.startsWith("MATCH_V2_")) guest?.receive(it)
            } }
            a.broadcastPacket(RoomMessagePacket("START_GAME", playerId = ids[0], seed = seed, matchId = seed.toString(),
                players = players, boardSize = 5, currentTurnPlayerId = ids[0]))
            waitFor("START_GAME reaches guest with required versions") {
                starts.any { it.seed == seed && it.protocolVersion == 2 && it.hostWaitVersion == 1 && it.reliablePolicyVersion == 3 }
            }
            val boards = players.associate { it.id to BingoEngine().generateBoard(5, seed + if (it.isHost) 1 else 2) }
            val initial = ReliableMatchSnapshot(matchId = seed.toString(), roomCode = code, seed = seed,
                hostId = ids[0], ownerId = "probe-owner-A", participants = players, boards = boards,
                order = ids, currentPlayerId = ids[0])
            val hs = Store(); val gs = Store()
            host = ReliableMatchClient(initial, ids[0], "probe-owner-A", clock, transport(a), hs, scope)
            guest = ReliableMatchClient(initial, ids[1], "probe-owner-B", clock, transport(b), gs, scope)
            waitFor("both production match clients finish negotiation") { !host!!.view.value.waiting && !guest!!.view.value.waiting }
            var picks = 0
            for (number in 1..25) {
                if (host!!.view.value.snapshot?.final != null) break
                val active = if (host!!.view.value.snapshot!!.currentPlayerId == ids[0]) host!! else guest!!
                active.request("PICK", number = number)
                picks++
                waitFor("accepted pick $number reaches both players") {
                    host!!.view.value.snapshot?.picks?.size == picks && guest!!.view.value.snapshot?.picks?.size == picks
                }
                check(host!!.view.value.snapshot!!.boards == guest!!.view.value.snapshot!!.boards)
                check(host!!.view.value.snapshot!!.turn == guest!!.view.value.snapshot!!.turn)
            }
            waitFor("legitimate Bingo result is identical on both clients") {
                val result = host!!.view.value.snapshot?.final
                result != null && result == guest!!.view.value.snapshot?.final && result.reason in setOf("BINGO", "TURN_PICK")
            }
            println("RESULT picks=$picks outcome=${host!!.view.value.snapshot!!.final!!.reason}; no HTTP registry writes; headless Android health replaced by connected transport")
            ar.cancel(); br.cancel()
        } finally {
            host?.close(); guest?.close()
            a.disconnect(); b.disconnect()
            try {
                waitFor("production room disconnect releases both MQTT ping timers") {
                    Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith("MQTT Ping: bingo_google_") }
                }
            } finally {
                scope.cancel()
                clearRetained(code, seed)
            }
        }
    }
}
