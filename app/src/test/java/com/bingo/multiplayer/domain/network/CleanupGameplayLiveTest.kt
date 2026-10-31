package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.engine.BingoEngine
import com.bingo.multiplayer.domain.model.InGameChatMessage
import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in: actual production managers/reducer/recovery plus the real service cleaner over EMQX. */
class CleanupGameplayLiveTest {
    private object NoCloud : RoomRegistryGateway {
        override suspend fun sync(code: String, player: Player, known: List<Player>, banned: Set<String>): OnlineRoomSession? = null
        override suspend fun ready(code: String, uid: String, status: String, version: Long) {}
        override suspend fun remove(code: String, uid: String) {}
        override suspend fun close(code: String) {}
    }
    private suspend fun until(label: String, condition: () -> Boolean) {
        withTimeout(20_000L) { while (!condition()) delay(20L) }
        println("PASS: $label")
    }
    @Test fun cleanupBacksOffForLivePicksAndColdRejoins() = runBlocking {
        assumeTrue("Explicit live opt-in", System.getenv("BINGO_CLEANUP_LIVE") == "1")
        val runner = File(requireNotNull(System.getenv("BINGO_CLEANUP_RUNNER")))
        val evidence = File(requireNotNull(System.getenv("BINGO_CLEANUP_EVIDENCE"))).apply { mkdirs() }
        val suffix = UUID.randomUUID().toString().replace("-", "").take(8).uppercase()
        val code = "CT$suffix"; val oldCode = "CO$suffix"; val seed = System.currentTimeMillis()
        val manifest = File(evidence, "fixture-$suffix.private.json")
        manifest.writeText("""{"activeRoom":"$code","oldRoom":"$oldCode","seed":$seed}""")
        var invocation = 0
        fun start(mode: String): Pair<Process, File> {
            val log = File(evidence, "${invocation++}-$mode.log")
            return ProcessBuilder("node", runner.absolutePath, mode, manifest.absolutePath)
                .redirectErrorStream(true).redirectOutput(log).start() to log
        }
        suspend fun done(task: Pair<Process, File>): String {
            withTimeout(60_000L) { while (task.first.isAlive) delay(25) }
            val text = task.second.readText()
            assertEquals(text, 0, task.first.exitValue()); println(text.trim()); return text
        }
        val roster = listOf(Player("qa_a_$suffix", "Cleanup QA A", isHost = true), Player("qa_b_$suffix", "Cleanup QA B"))
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val managerScopes=List(3) { CoroutineScope(Dispatchers.Default + SupervisorJob()) }
        val a = OnlineRoomSyncManager(managerScopes[0], NoCloud); val b = OnlineRoomSyncManager(managerScopes[1], NoCloud)
        val b2 = OnlineRoomSyncManager(managerScopes[2], NoCloud)
        val aq = CopyOnWriteArrayList<RoomMessagePacket>(); val bq = CopyOnWriteArrayList<RoomMessagePacket>()
        val b2q = CopyOnWriteArrayList<RoomMessagePacket>()
        val oldDeletes = AtomicInteger(); var race: Pair<Process, File>? = null
        val observer = MqttAsyncClient(NetworkConfig.BROKER_URL, "guard_qa_${UUID.randomUUID()}", MemoryPersistence())
        val latency = mutableListOf<Long>()
        var dataA: OngoingMatchData? = null; var dataB: OngoingMatchData? = null
        try {
            done(start("prepare"))
            observer.connect(MqttConnectOptions().apply { isCleanSession=true; connectionTimeout=5; NetworkConfig.applyMqttOptions(this) }).waitForCompletion(7_000)
            observer.subscribe("${PeerMatchRecovery.topic(oldCode,seed)}/#", 0) { _, m ->
                if (m.payload.isEmpty()) oldDeletes.incrementAndGet()
            }.waitForCompletion(4_000)
            scope.launch(start=CoroutineStart.UNDISPATCHED) { a.incomingPackets.collect { aq.add(it) } }
            scope.launch(start=CoroutineStart.UNDISPATCHED) { b.incomingPackets.collect { bq.add(it) } }
            scope.launch(start=CoroutineStart.UNDISPATCHED) { b2.incomingPackets.collect { b2q.add(it) } }
            race = start("race")
            until("old fixture cleanup actually started") { oldDeletes.get()>0 }
            a.connectToRoom(code,roster[0],roster,seed); b.connectToRoom(code,roster[1],roster,seed)
            until("two production managers completed room recovery") {
                aq.any { it.type=="PEER_SYNC_READY"&&it.seed==seed } && bq.any { it.type=="PEER_SYNC_READY"&&it.seed==seed }
            }
            val now=System.currentTimeMillis()
            val boards=roster.mapIndexed { i,p->p.id to BingoEngine().generateBoard(5,seed+i) }.toMap()
            val base=OngoingMatchData(code,seed,5,accountUid=roster[0].id,participants=roster,
                playerBoard=boards.getValue(roster[0].id),opponentBoard=boards.getValue(roster[1].id),allPlayerBoards=boards,
                randomizedTurnOrder=roster,currentTurnPlayerId=roster[0].id,startedAt=now,lastPlayerActivityAt=now,
                turnClock=MultiplayerTurnClock.start(1,roster[0].id,now),simplifiedRulesVersion=1)
            val baseB=base.copy(accountUid=roster[1].id,playerBoard=boards.getValue(roster[1].id),opponentBoard=boards.getValue(roster[0].id))
            dataA=base; dataB=baseB
            fun snapshot(d: OngoingMatchData, revision: Long, chat: List<InGameChatMessage> = d.chatMessages) = RoomMessagePacket("GAME_SYNC",
                playerId=roster[0].id,senderPlayerId=roster[0].id,seed=seed,roomCode=code,sequence=revision,
                timestamp=System.currentTimeMillis(),turnNumber=d.turnNumber,currentTurnPlayerId=d.currentTurnPlayerId,
                pickedHistory=d.pickedNumbers,pickedByHistory=d.pickedByPlayers,turnIds=d.turnIds,
                boardNumbers=d.allPlayerBoards.mapValues { it.value.cells.map { c->c.number } },
                turnClock=d.turnClock,remainingTurnMillis=d.turnClock?.remainingMillis(System.currentTimeMillis())?:0,
                lastPlayerActivityAt=d.lastPlayerActivityAt,chatMessages=chat,simplifiedRulesVersion=1)
            val chat=listOf(InGameChatMessage(id=seed,text="Preserve this chat during cleanup",senderPlayerId=roster[0].id,timestamp=now))
            a.publishPeerSnapshot(snapshot(base,1,chat))
            until("snapshot and chat reached guest") { bq.any { it.type=="GAME_SYNC"&&it.chatMessages.any { m->m.id==seed } } }
            dataA=PeerMatchRecovery.merge(dataA!!,listOf(aq.last { it.type=="GAME_SYNC" }),System.currentTimeMillis())
            dataB=PeerMatchRecovery.merge(dataB!!,listOf(bq.last { it.type=="GAME_SYNC" }),System.currentTimeMillis())
            suspend fun pick(number: Int, guestQueue: CopyOnWriteArrayList<RoomMessagePacket> = bq, guestManager: OnlineRoomSyncManager = b) {
                val before=dataA!!; val owner=before.currentTurnPlayerId
                val next=roster.first { it.id!=owner }.id; val at=System.currentTimeMillis()
                val p=RoomMessagePacket("PICK_NUMBER",playerId=owner,senderPlayerId=owner,roomCode=code,seed=seed,
                    number=number,closedTurnNumber=before.turnNumber,turnNumber=before.turnNumber+1,currentTurnPlayerId=next,
                    timestamp=at,closedTurnClock=before.turnClock,turnClock=MultiplayerTurnClock.start(before.turnNumber+1,next,at),simplifiedRulesVersion=1)
                (if(owner==roster[0].id)a else guestManager).broadcastPacket(p)
                until("both peers received pick $number") { aq.any { it.type=="PICK_NUMBER"&&it.number==number } && guestQueue.any { it.type=="PICK_NUMBER"&&it.number==number } }
                latency+=System.currentTimeMillis()-at
                dataA=PeerMatchRecovery.merge(before,listOf(aq.last { it.type=="PICK_NUMBER"&&it.number==number }),System.currentTimeMillis())
                dataB=PeerMatchRecovery.merge(dataB!!,listOf(guestQueue.last { it.type=="PICK_NUMBER"&&it.number==number }),System.currentTimeMillis())
                assertEquals(dataA!!.pickedNumbers,dataB!!.pickedNumbers);assertEquals(dataA!!.turnNumber,dataB!!.turnNumber)
                assertEquals(dataA!!.currentTurnPlayerId,dataB!!.currentTurnPlayerId);assertEquals(dataA!!.turnClock,dataB!!.turnClock)
                assertEquals(dataA!!.allPlayerBoards,dataB!!.allPlayerBoards)
            }
            pick(7);pick(9)
            val raceResult=done(race!!);assertTrue(raceResult,raceResult.contains("\"deferred\":true"))
            assertTrue("Cleanup must stop before deleting the whole old fixture",oldDeletes.get()<42)
            assertTrue(done(start("automatic")).contains("\"status\":\"DEFERRED\""))
            pick(11);pick(13)
            val staleGuest=dataB!!
            b.disconnect();b.drainForTests()
            // Host's next actual transport pick must survive while guest is closed.
            val before=dataA!!;val at=System.currentTimeMillis()
            a.broadcastPacket(RoomMessagePacket("PICK_NUMBER",playerId=roster[0].id,senderPlayerId=roster[0].id,roomCode=code,seed=seed,
                number=15,closedTurnNumber=before.turnNumber,turnNumber=before.turnNumber+1,currentTurnPlayerId=roster[1].id,timestamp=at,
                closedTurnClock=before.turnClock,turnClock=MultiplayerTurnClock.start(before.turnNumber+1,roster[1].id,at),simplifiedRulesVersion=1))
            until("host committed a pick while guest was closed") { aq.any { it.type=="PICK_NUMBER"&&it.number==15 } }
            dataA=PeerMatchRecovery.merge(before,listOf(aq.last { it.type=="PICK_NUMBER"&&it.number==15 }),System.currentTimeMillis())
            a.publishPeerSnapshot(snapshot(dataA!!,2,chat))
            assertTrue(done(start("automatic")).contains("\"status\":\"DEFERRED\""))
            b2.connectToRoom(code,roster[1],roster,seed)
            until("returning production guest completed recovery") { b2q.any { it.type=="PEER_SYNC_READY"&&it.seed==seed } }
            val read=PeerMatchRecovery.read(code,seed);assertTrue(read.available)
            dataB=PeerMatchRecovery.merge(staleGuest,read.packets,System.currentTimeMillis())
            assertEquals(dataA!!.pickedNumbers,dataB!!.pickedNumbers);assertEquals(dataA!!.allPlayerBoards,dataB!!.allPlayerBoards)
            assertEquals(dataA!!.turnNumber,dataB!!.turnNumber);assertEquals(dataA!!.turnClock,dataB!!.turnClock)
            assertEquals(listOf(seed),dataB!!.chatMessages.map { it.id });assertFalse(dataB!!.isMatchFinished)
            println("PASS: real reconnect preserved boards, picks, turn, deadline and chat")
            pick(17,b2q,b2)
            val expected=dataA!!
            a.disconnect();b2.disconnect();a.drainForTests();b2.drainForTests()
            assertTrue(done(start("automatic")).contains("\"status\":\"DEFERRED\""))
            val coldRead=PeerMatchRecovery.read(code,seed);assertTrue(coldRead.available)
            val cold=PeerMatchRecovery.merge(base,coldRead.packets,System.currentTimeMillis())
            assertEquals(expected.pickedNumbers,cold.pickedNumbers);assertEquals(expected.allPlayerBoards,cold.allPlayerBoards)
            assertEquals(expected.turnNumber,cold.turnNumber);assertEquals(expected.currentTurnPlayerId,cold.currentTurnPlayerId)
            assertEquals(expected.turnClock,cold.turnClock);assertEquals(listOf(seed),cold.chatMessages.map { it.id });assertFalse(cold.isMatchFinished)
            println("PASS: both clients closed; cold recovery retained the unsnapshotted last pick with no extra skip")
            File(evidence,"gameplay-result.txt").writeText("PASS\noldDeletes=${oldDeletes.get()}\npickDeliveryMillis=$latency\nrejoinBoardsPicksTurnDeadlineChat=PASS\nbothClosedColdRecovery=PASS\n")
        } finally {
            a.disconnect();b.disconnect();b2.disconnect();a.drainForTests();b.drainForTests();b2.drainForTests()
            race?.first?.let { if(it.isAlive)it.destroyForcibly() }
            if(observer.isConnected)runCatching { observer.disconnect().waitForCompletion(2000) };runCatching { observer.close(true) }
            delay(400);runCatching { done(start("finish")) }.onFailure { println("OWNED FIXTURE CLEANUP NEEDS RETRY: ${it.message}") }
            scope.cancel();managerScopes.forEach { it.cancel() }
        }
    }
}
