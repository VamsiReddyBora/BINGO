package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.*
import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.File
import java.util.UUID

/** Explicit opt-in executable; not a fake broker or a device UI test. Never touches real rooms/users. */
object LiveMqttRecoveryProbe {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private class Endpoint(private val code: String, private val uid: String) : ReliableMatchTransport {
        private val mqtt = MqttAsyncClient(NetworkConfig.BROKER_URL, "codex-test-${UUID.randomUUID().toString().take(12)}", MemoryPersistence())
        var receiver: ((RoomMessagePacket) -> Unit)? = null
        val roomTopic = "bingo/v3/room/${code.uppercase()}"
        val stateTopic = "bingo/v4/match/${code.uppercase()}/778899/state"
        override val connected get() = mqtt.isConnected
        override val healthy get() = connected
        fun connect() {
            mqtt.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {}
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    try { receiver?.invoke(json.decodeFromString(String(message!!.payload, Charsets.UTF_8))) } catch (_: Exception) {}
                }
            })
            val options = MqttConnectOptions().apply {
                userName = NetworkConfig.MQTT_USERNAME
                password = NetworkConfig.MQTT_PASSWORD.toCharArray()
                isCleanSession = true; connectionTimeout = 10; keepAliveInterval = 15
            }
            mqtt.connect(options).waitForCompletion(15_000)
            mqtt.subscribe(roomTopic,1).waitForCompletion(5000)
        }
        override suspend fun subscribe(seed: Long): Boolean = try { mqtt.subscribe(stateTopic,1).waitForCompletion(5000); true } catch (_: Exception) { false }
        override suspend fun publish(packet: RoomMessagePacket, retained: Boolean, stateTopic: Boolean): Boolean = try {
            val m = MqttMessage(json.encodeToString(packet).toByteArray()).apply { qos = 1; isRetained = retained }
            mqtt.publish(if (stateTopic) this.stateTopic else roomTopic,m).waitForCompletion(5000); true
        } catch (_: Exception) { false }
        fun close() { try { mqtt.disconnect().waitForCompletion(3000) } catch (_: Exception) {}; mqtt.close() }
        fun clear() { mqtt.publish(stateTopic,MqttMessage(ByteArray(0)).apply { qos=1; isRetained=true }).waitForCompletion(5000) }
    }
    private class Store(private val file: File) : ReliableMatchCheckpoint {
        override fun saveHostWait(snapshot: ReliableMatchSnapshot?): Boolean {
            val target = File(file.parentFile, file.name + ".hold")
            if (snapshot == null) target.delete() else { target.parentFile?.mkdirs(); target.writeText(json.encodeToString(snapshot)) }
            return true
        }
        override fun save(snapshot: ReliableMatchSnapshot,pending: Boolean): Boolean {
            // Each simulated device gets its own private directory; the broker is the only bridge.
            val target = File(file.parentFile,if(pending) file.name+".pending" else file.name)
            target.parentFile?.mkdirs(); target.writeText(json.encodeToString(snapshot)); return true
        }
    }
    private suspend fun waitFor(label: String, predicate: () -> Boolean) {
        withTimeout(20_000) { while (!predicate()) delay(50) }
        println("PASS $label")
    }
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 3) { "writer|reader unique-test-room private-evidence-directory" }
        val mode=args[0]; val code=args[1]; val directory=File(args[2]); directory.mkdirs()
        val clock=object : Clock { override fun elapsedRealtime()=System.nanoTime()/1_000_000 }
        val initial=ReliableMatchSnapshot(matchId="778899",roomCode=code,seed=778899,hostId="A",ownerId="owner-A",
            participants=listOf(Player("A","Kevin-test",isHost=true),Player("B","Stuart-test")),
            boards=mapOf("A" to BingoEngine().generateBoard(5,1),"B" to BingoEngine().generateBoard(5,2)),order=listOf("A","B"),currentPlayerId="A")
        val jobs=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        if (mode == "cleanup") {
            val endpoint = Endpoint(code, "cleanup"); endpoint.connect()
            try { endpoint.clear() } finally { endpoint.close(); jobs.cancel() }
            return@runBlocking
        }
        if(mode=="writer") {
            var a=Endpoint(code,"A"); val b=Endpoint(code,"B"); a.connect(); b.connect()
            var host=ReliableMatchClient(initial,"A","owner-A",clock,a,Store(File(directory,"A/state.json")),jobs)
            val guest=ReliableMatchClient(initial,"B","owner-B",clock,b,Store(File(directory,"B/state.json")),jobs)
            a.receiver=host::receive; b.receiver=guest::receive
            try {
                waitFor("real broker negotiates both production clients") { !host.view.value.waiting && !guest.view.value.waiting }
                val samples=mutableListOf<Long>()
                for (number in 1..8) {
                    val active=if(host.view.value.snapshot!!.currentPlayerId=="A") host else guest
                    val started=System.nanoTime()
                    active.request("PICK",number=number)
                    waitFor("accepted MQTT pick $number reaches both clients") {
                        host.view.value.snapshot?.picks?.size==number && guest.view.value.snapshot?.picks?.size==number
                    }
                    samples.add((System.nanoTime()-started)/1_000_000)
                    check(host.view.value.snapshot!!.boards==guest.view.value.snapshot!!.boards)
                }
                val sorted=samples.sorted()
                val median=(sorted[(sorted.size-1)/2]+sorted[sorted.size/2])/2.0
                println("METRIC request-to-both-accepted samples=${samples.size} medianMs=$median p95Ms=${sorted.last()} rawMs=$samples (50ms poll resolution; not physical tap latency)")
                val ownerCheckpoint = host.view.value.snapshot!!.copy(remainingMillis = host.view.value.remainingMillis)
                val policy = OnlineMatchHeartbeatPolicy(initial.matchId,"A","live-host-wait",clock)
                policy.initializeObservation("live-host-wait")
                host.close(); a.close()
                delay(15_100)
                check(policy.onTick() is HeartbeatDecision.BecameUnreachable)
                guest.waitForHost()
                waitFor("15s host silence enters persistent blocking guest hold") { guest.view.value.hostDisconnected && guest.view.value.waiting }
                val frozen = guest.view.value
                check(frozen.remainingMillis in 0..30_000)
                guest.request("PICK",number=9);guest.request("SURRENDER")
                delay(2_000)
                check(guest.view.value.remainingMillis == frozen.remainingMillis)
                check(guest.view.value.snapshot == frozen.snapshot)
                check(guest.view.value.snapshot!!.final == null)
                println("PASS MQTT host hold freezes remainingMs=${frozen.remainingMillis}, board, turns and actions")
                a=Endpoint(code,"A");a.connect()
                host=ReliableMatchClient(ownerCheckpoint,"A","owner-A",clock,a,Store(File(directory,"A/state.json")),jobs,previouslyAccepted=true)
                a.receiver=host::receive
                waitFor("returning host and guest finish correlated recovery") { !host.view.value.waiting && !guest.view.value.waiting && !guest.view.value.hostDisconnected }
                check(host.view.value.snapshot!!.boards == frozen.snapshot!!.boards)
                check(guest.view.value.snapshot!!.picks == frozen.snapshot!!.picks)
                check(host.view.value.snapshot!!.turn == frozen.snapshot!!.turn)
                check(host.view.value.remainingMillis <= frozen.remainingMillis)
                check(guest.view.value.remainingMillis <= frozen.remainingMillis)
                check(kotlin.math.abs(host.view.value.remainingMillis - guest.view.value.remainingMillis) < 1500)
                println("PASS MQTT rejoin preserves board/picks/turn; frozenMs=${frozen.remainingMillis}, hostMs=${host.view.value.remainingMillis}, guestMs=${guest.view.value.remainingMillis}")
                guest.close(); b.close()
                host.peerEpisode("B",MatchEpisode(3,"third-test-episode",true,true))
                waitFor("prepared third-episode checkpoint published") { host.view.value.snapshot?.episodes?.get("B")?.count==3 }
                host.claim("third-test-episode")
                waitFor("accepted final result retained before winner exits") { host.view.value.snapshot?.final!=null }
                File(directory,"expected-result.json").writeText(json.encodeToString(host.view.value.snapshot!!.final))
            } finally { host.close(); a.close(); jobs.cancel() }
        } else {
            val b=Endpoint(code,"B"); b.connect()
            val client=ReliableMatchClient(initial,"B","owner-B",clock,b,Store(File(directory,"returning-B/state.json")),jobs,previouslyAccepted=true)
            b.receiver=client::receive
            try {
                waitFor("independent returning JVM recovers final result after winner JVM exited") { client.view.value.snapshot?.final!=null && !client.view.value.waiting }
                check(client.view.value.snapshot!!.final!!.winnerId=="A")
                println("PASS returned match stays final; no unfinished-match resurrection")
                b.clear()
            } finally { client.close(); b.close(); jobs.cancel() }
        }
    }
}
