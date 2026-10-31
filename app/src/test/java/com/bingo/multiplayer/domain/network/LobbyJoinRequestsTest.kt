package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class LobbyJoinRequestsTest {
    private class Bus:EmqxWire {
        override val isHealthy=true
        val listeners=ConcurrentHashMap<String,(ByteArray,Boolean)->Unit>()
        data class Sent(val topic:String,val payload:ByteArray,val retained:Boolean)
        val queue=CopyOnWriteArrayList<Sent>()
        val history=CopyOnWriteArrayList<Sent>()
        var fail=false
        override suspend fun subscribe(topic:String,handler:(ByteArray,Boolean)->Unit):AutoCloseable {
            listeners[topic]=handler;return AutoCloseable{listeners.remove(topic)}
        }
        override suspend fun publish(topic:String,payload:ByteArray,retained:Boolean){
            if(fail)error("Broker unavailable")
            Sent(topic,payload,retained).also {queue.add(it);history.add(it)}
        }
        override suspend fun fence(){}
        override suspend fun readRetained(topic:String):ByteArray?=null
        override suspend fun confirmRetained(topic:String,payload:ByteArray)=true
        fun deliver(index:Int=0){val p=queue.removeAt(index);listeners[p.topic]?.invoke(p.payload,p.retained)}
        fun drain(){while(queue.isNotEmpty())deliver()}
    }
    private fun room(host:String="b",code:String="ROOMB1")=OnlineRoomSession(code,host,host,"Player $host",sessionId="session-$host")
    private suspend fun ready(bus:Bus){withTimeout(2500){while(bus.listeners.size<2)delay(5)}}
    private suspend fun waitFor(condition:()->Boolean){withTimeout(2500){while(!condition())delay(5)}}
    private fun manager(id:String,bus:Bus,clock:()->Long=System::currentTimeMillis)=LobbyJoinRequests(LobbyJoinIdentity(id,id,"Player $id"),bus,clock).also{
        it.updateLocation("own-lobby-$id",if(id=="a")"ROOMA1" else "ROOMB1","session-$id",true);it.start()
    }
    @Test fun actualMqttEnvelopesDeliverApprovalOnlyToMatchingRequester()=runBlocking {
        val bus=Bus();val a=manager("a",bus);val b=manager("b",bus)
        try {ready(bus);val request=a.request(room()).getOrThrow();bus.drain();assertEquals(request,b.incoming.value)
            assertTrue(b.respond(request,true));bus.drain();assertEquals(request,a.accepted.value)
            assertTrue(a.consumeAcceptance(request));assertFalse(a.consumeAcceptance(request))
            assertTrue(bus.history.all{!it.retained});assertNull(a.outgoing.value);assertNull(b.incoming.value)
        }finally{a.close();b.close()}
    }
    @Test fun declineDoesNotProduceAnAcceptance()=runBlocking {
        val bus=Bus();val a=manager("a",bus);val b=manager("b",bus)
        try{ready(bus);val request=a.request(room()).getOrThrow();bus.drain();assertTrue(b.respond(request,false));bus.drain()
            assertNull(a.accepted.value);assertNull(a.outgoing.value)}finally{a.close();b.close()}
    }
    @Test fun simultaneousOppositeRequestsChooseOneDestinationInEitherArrivalOrder()=runBlocking {
        for(reverse in listOf(false,true)){
            val bus=Bus();val a=manager("a",bus);val b=manager("b",bus)
            try{ready(bus);a.request(room()).getOrThrow();b.request(room("a","ROOMA1")).getOrThrow()
                if(reverse)bus.deliver(1) else bus.deliver(0);bus.drain()
                waitFor{listOf(a.incoming.value,b.incoming.value).count{it!=null}==1}
                val receiver=if(a.incoming.value!=null)a else b;val sender=if(receiver===a)b else a
                val chosen=receiver.incoming.value!!;assertTrue(receiver.respond(chosen,true));bus.drain()
                waitFor{sender.accepted.value!=null};assertEquals(chosen,sender.accepted.value);assertNull(receiver.accepted.value)
                assertEquals(1,listOf(a.accepted.value,b.accepted.value).count{it!=null})
            }finally{a.close();b.close()}
        }
    }
    @Test fun navigationOrStartingMatchInvalidatesOldRequests()=runBlocking {
        val bus=Bus();val a=manager("a",bus);val b=manager("b",bus)
        try{ready(bus);val request=a.request(room()).getOrThrow();bus.drain()
            b.updateLocation("starting","ROOMB1","session-b",false);assertFalse(b.respond(request,true));assertNull(b.incoming.value)
            a.updateLocation("another-screen");a.receive(LobbyJoinSignal("RESPONSE","b",request,true));assertNull(a.accepted.value)
        }finally{a.close();b.close()}
    }
    @Test fun wrongIdentitySessionAndUnsolicitedApprovalCannotAdmitAPlayer()=runBlocking {
        val bus=Bus();val a=manager("a",bus);val b=manager("b",bus)
        try{ready(bus);val request=a.request(room()).getOrThrow()
            b.receive(LobbyJoinSignal("REQUEST","stranger",request));assertNull(b.incoming.value)
            b.receive(LobbyJoinSignal("REQUEST","a",request.copy(lobbySessionId="old")));assertNull(b.incoming.value)
            a.receive(LobbyJoinSignal("RESPONSE","stranger",request,true));assertNull(a.accepted.value)
            a.receive(LobbyJoinSignal("RESPONSE","b",request.copy(id="other-request-123456"),true));assertNull(a.accepted.value)
            bus.drain();assertEquals(request,b.incoming.value)
        }finally{a.close();b.close()}
    }
    @Test fun expiredOrDuplicateRequestsDoNotCreateAnotherApproval()=runBlocking {
        var time=System.currentTimeMillis();val bus=Bus();val a=manager("a",bus){time};val b=manager("b",bus){time}
        try{ready(bus);val request=a.request(room()).getOrThrow();bus.drain();b.receive(LobbyJoinSignal("REQUEST","a",request))
            assertEquals(request,b.incoming.value);time=request.expiresAt+1;assertFalse(b.respond(request,true))
            a.receive(LobbyJoinSignal("RESPONSE","b",request,true));assertNull(a.accepted.value)
        }finally{a.close();b.close()}
    }
    @Test fun closedPlayingAndUnpublishedRequestsDoNotLeavePendingState()=runBlocking {
        val bus=Bus();val a=manager("a",bus);val b=manager("b",bus)
        try{ready(bus);assertTrue(a.request(room().copy(status="PLAYING")).isFailure)
            assertTrue(a.request(room().copy(status="CLOSED")).isFailure);assertTrue(bus.history.isEmpty())
            bus.fail=true;assertTrue(a.request(room()).isFailure);assertNull(a.outgoing.value)
        }finally{a.close();b.close()}
    }
    @Test fun repeatedTapDoesNotPublishAnotherPendingRequest()=runBlocking {
        val bus=Bus();val a=manager("a",bus);val b=manager("b",bus)
        try{ready(bus);a.request(room()).getOrThrow();assertTrue(a.request(room()).isFailure);assertEquals(1,bus.history.size)}finally{a.close();b.close()}
    }
}
