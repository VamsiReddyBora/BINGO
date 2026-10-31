package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.Player
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Only unique owned fixture rooms and identities; no real invitation, profile or push is sent. */
class LiveLobbyCommunicationIntegrationTest {
    @Test fun realMqttApprovalSafeHostTransferLatestMessageAndHostReconnect()=runBlocking {
        assumeTrue(System.getenv("BINGO_LIVE_MQTT")=="1")
        val suffix=UUID.randomUUID().toString().replace("-","")
        val oldCode="A"+suffix.take(5).uppercase();val code="B"+suffix.take(5).uppercase()
        val a=Player("qa_lobby_a_$suffix","QA A","qa_a_$suffix",isHost=true)
        val b=Player("qa_lobby_b_$suffix","QA B","qa_b_$suffix",isHost=true)
        val ca=EmqxConnection("qa-lobby-a-$suffix");val cb=EmqxConnection("qa-lobby-b-$suffix")
        val ma=MqttRoomMetadataTransport(ca,{a.id});val mb=MqttRoomMetadataTransport(cb,{b.id})
        val ra=LobbyJoinRequests(LobbyJoinIdentity(a.id,a.username,a.displayName),ca)
        val rb=LobbyJoinRequests(LobbyJoinIdentity(b.id,b.username,b.displayName),cb)
        val handles=mutableListOf<AutoCloseable>();val io=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val previous=RoomMetadataApi.transportOverride
        try {
            assertTrue(ca.awaitHealthy(12_000));assertTrue(cb.awaitHealthy(12_000))
            val ac=RoomMetadataCoordinator(ma);val bc=RoomMetadataCoordinator(mb)
            assertTrue(ac.mutate(oldCode,initial=OnlineRoomSession(oldCode,a.id,a.username,a.displayName,players=listOf(a))){it} is MutationResult.Applied)
            assertTrue(bc.mutate(code,initial=OnlineRoomSession(code,b.id,b.username,b.displayName,players=listOf(b))){it} is MutationResult.Applied)
            val old=ma.read(oldCode).getOrThrow()!!;val target=mb.read(code).getOrThrow()!!
            ra.updateLocation("source",oldCode,old.sessionId,true);rb.updateLocation("target",code,target.sessionId,true)
            ra.start();rb.start();delay(400)
            val request=ra.request(target).getOrThrow()
            withTimeout(7000){while(rb.incoming.value==null)delay(10)}
            assertTrue(rb.respond(request,true));withTimeout(7000){while(ra.accepted.value==null)delay(10)}
            assertTrue(ra.consumeAcceptance(request))
            RoomMetadataApi.transportOverride=ma
            val moved=LobbyRoomTransfer.join(
                admit={OnlineRoomRegistry.validateAndJoinRoom(code,a.copy(isHost=false),target.sessionId,b.id,waitingOnly=true)},
                depart={LobbyRoomTransfer.departHostedLobby(oldCode,old.sessionId,a.id,emptySet(),ac)},
                rollback={OnlineRoomRegistry.removePlayerFromRoom(code,a.id)},isCurrent={true})
            assertTrue("Actual room transfer: $moved",moved is RoomJoinResult.Success)
            assertEquals("CLOSED",ma.read(oldCode).getOrThrow()!!.status)
            val roster=mb.read(code).getOrThrow()!!;assertEquals(setOf(a.id,b.id),roster.players.map{it.id}.toSet())
            println("PASS real MQTT request -> host approval -> existing admission -> confirmed old-host lobby closure")
            val activeA=AtomicReference<LobbyMessageSession?>(LobbyMessageSession())
            val engineB=LobbyMessageSession()
            fun bind(s:LobbyMessageSession,id:String){s.bind(code,target.sessionId,id,b.id,setOf(a.id,b.id))}
            bind(activeA.get()!!,a.id);bind(engineB,b.id)
            val topic="bingo/emqx7/room/$code"
            suspend fun publish(c:EmqxConnection,p:RoomMessagePacket){c.publish(topic,FastPacketCodec.encode(p).toByteArray(),false)}
            handles+=ca.subscribe(topic){bytes,_->val p=FastPacketCodec.decode(bytes.toString(Charsets.UTF_8));activeA.get()?.receive(p)?.let{reply->io.launch{publish(ca,reply)}}}
            handles+=cb.subscribe(topic){bytes,_->val p=FastPacketCodec.decode(bytes.toString(Charsets.UTF_8));engineB.receive(p)?.let{reply->io.launch{publish(cb,reply)}}}
            publish(ca,activeA.get()!!.typingPacket(true)!!)
            withTimeout(7000){while(a.id !in engineB.typingPlayerIds.value)delay(10)}
            publish(ca,activeA.get()!!.typingPacket(false)!!)
            withTimeout(7000){while(a.id in engineB.typingPlayerIds.value)delay(10)}
            println("PASS real MQTT transient lobby typing start/stop, without publishing draft text")
            assertTrue(activeA.get()!!.send("Wait a minute ⏳"){publish(ca,it);true})
            withTimeout(7000){while(engineB.messages.value[a.id]?.text!="Wait a minute ⏳")delay(10)}
            assertTrue(activeA.get()!!.send("Ready now 👍"){publish(ca,it);true})
            withTimeout(7000){while(engineB.messages.value[a.id]?.text!="Ready now 👍")delay(10)}
            assertEquals(1,engineB.messages.value.size)
            // The host misses a live message, then recovers the sender's latest value.
            bind(engineB,b.id);val hostAway=AtomicReference<LobbyMessageSession?>(null)
            handles.last().close()
            handles+=cb.subscribe(topic){bytes,_->val p=FastPacketCodec.decode(bytes.toString(Charsets.UTF_8));hostAway.get()?.receive(p)?.let{reply->io.launch{publish(cb,reply)}}}
            var lastMessage: RoomMessagePacket? = null
            assertTrue(activeA.get()!!.send("Latest while host was away"){lastMessage=it;publish(ca,it);true})
            val returnedHost=LobbyMessageSession();bind(returnedHost,b.id);hostAway.set(returnedHost)
            publish(cb,returnedHost.request()!!)
            withTimeout(7000){while(returnedHost.messages.value[a.id]?.text!="Latest while host was away")delay(10)}
            println("PASS real MQTT latest-message replacement and host-return replay; non-retained room traffic")
            val latestId=activeA.get()!!.messages.value.getValue(a.id).id
            assertTrue(activeA.get()!!.delete(latestId){publish(ca,it);true})
            withTimeout(7000){while(returnedHost.messages.value.containsKey(a.id))delay(10)}
            publish(ca,lastMessage!!)
            val freshHost=LobbyMessageSession();bind(freshHost,b.id);hostAway.set(freshHost)
            val deletedSync=activeA.get()!!.receive(freshHost.request()!!)!!
            assertEquals("",deletedSync.lobbyMessages.single().text)
            publish(ca,deletedSync);publish(ca,lastMessage!!)
            assertTrue(activeA.get()!!.send("After deletion"){publish(ca,it);true})
            withTimeout(7000){while(freshHost.messages.value[a.id]?.text!="After deletion")delay(10)}
            assertEquals(1,freshHost.messages.value.size)
            println("PASS real MQTT owner deletion, reconnect deletion marker, delayed replay and replacement")
        } finally {
            RoomMetadataApi.transportOverride=previous;ra.close();rb.close();handles.forEach{it.close()};io.cancel();ma.close();mb.close()
            try {
                check(ca.awaitHealthy(10_000))
                for(room in listOf(oldCode,code))for(kind in listOf("state","auth")){
                    val topic="bingo/emqx7/metadata/$room/$kind"
                    ca.publish(topic,ByteArray(0),true);assertTrue(ca.confirmRetained(topic,ByteArray(0)))
                }
            } finally {ca.close();cb.close();OnlineRoomRegistry.recentRoomCache.remove(oldCode);OnlineRoomRegistry.recentRoomCache.remove(code)}
        }
    }
}
