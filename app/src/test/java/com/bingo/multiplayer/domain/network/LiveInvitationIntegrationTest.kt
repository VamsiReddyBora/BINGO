package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.notifications.NotificationAccount
import com.bingo.multiplayer.domain.notifications.NotificationPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Opt-in real broker check. Only private random test recipients are used and their retained topic is cleared. */
class LiveInvitationIntegrationTest {
    @Test
    fun productionInvitationPublisherAndListenerDeliverLiveAndRetainedInvites() = runBlocking {
        assumeTrue("Opt in with BINGO_LIVE_MQTT=1", System.getenv("BINGO_LIVE_MQTT") == "1")
        val recipient = "qa48_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val account = NotificationAccount("qa-recipient", recipient, "qa-generation", "qa-install")
        val now = System.currentTimeMillis()
        val invite = GameInvite("qa_sender", "Owned QA sender", roomCode = "QA4812",
            timestamp = now, eventId = "qa-live-" + UUID.randomUUID(), recipientUsername = recipient,
            senderUid = "qa-sender", expiresAt = now + 120_000L)
        val publisher = EmqxConnection("qa-inv-pub-" + UUID.randomUUID())
        var subscriber = EmqxConnection("qa-inv-sub-" + UUID.randomUUID())
        var listener: AutoCloseable? = null
        val topics = mutableListOf<String>()
        suspend fun awaitListener() {
            withTimeout(12_000L) {
                while (!subscriber.hasSubscription("bingo/emqx7/social/request/$recipient/+") || !subscriber.isHealthy) delay(10L)
            }
        }
        var failure: Throwable? = null
        try {
            GameInviteManager.clearForegroundInvite()
            listener = GameInviteManager.listen(recipient, subscriber) {}
            val healthy = publisher.awaitHealthy(12_000L)
            assertTrue("Publisher EMQX health failed: ${publisher.diagnostics}", healthy); awaitListener()
            val elapsed = mutableListOf<Long>()
            for (index in 0 until 5) {
                val payload = if (index == 0) invite else invite.copy(eventId = "qa-live-" + UUID.randomUUID())
                topics += "bingo/emqx7/social/invite/$recipient/${payload.eventId}"
                val live = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(5_000L) { GameInviteManager.foregroundInviteFlow.first { it.eventId == payload.eventId } }
                }
                val begin = System.nanoTime()
                GameInviteManager.publishMqttInviteAwaited(recipient, payload, publisher)
                val ackMillis = (System.nanoTime() - begin) / 1_000_000L
                val received = live.await()
                val receiptMillis = (System.nanoTime() - begin) / 1_000_000L
                elapsed += receiptMillis
                assertEquals(payload.eventId, received.eventId)
                assertEquals(payload.roomCode, received.roomCode)
                assertTrue(NotificationPolicy.eligible(received.copy(recipientUid = account.uid).toEvent(), account, System.currentTimeMillis()))
                println("INVITATION sample=$index publisher_to_puback_ms=$ackMillis publisher_to_recipient_flow_ms=$receiptMillis ui_not_measured=true")
                assertTrue("Warm actual publisher -> recipient flow should be near immediate", receiptMillis < 1500L)
            }
            listener.close(); subscriber.close()
            GameInviteManager.clearForegroundInvite()
            val retained = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(15_000L) { GameInviteManager.foregroundInviteFlow.first { it.eventId == invite.eventId } }
            }
            val reconnectBegin = System.nanoTime()
            subscriber = EmqxConnection("qa-inv-reconnect-" + UUID.randomUUID())
            listener = GameInviteManager.listen(recipient, subscriber) {}
            assertEquals(invite, retained.await())
            println("INVITATION reconnect_to_retained_recipient_flow_ms=${(System.nanoTime() - reconnectBegin) / 1_000_000L} ui_not_measured=true")
            awaitListener()

            val request = invite.copy(eventId = "qa-request-" + UUID.randomUUID(), isPlayRequest = true, roomCode = "")
            val receipt = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5000L) { GameInviteManager.playRequestReceipts.first { it.eventId == request.eventId } }
            }
            GameInviteManager.clearForegroundInvite()
            GameInviteManager.publishMqttInviteAwaited(recipient, request, publisher)
            assertEquals(request, receipt.await())
            assertNull("A Request never stages the invitation popup", GameInviteManager.stagedInvite)
            listener.close(); subscriber.close()
            val requestReplay = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(2500L) { GameInviteManager.playRequestReceipts.first { it.eventId == request.eventId } }
            }
            subscriber = EmqxConnection("qa-req-reconnect-" + UUID.randomUUID())
            listener = GameInviteManager.listen(recipient, subscriber) {}
            awaitListener()
            assertNull("Requests are not retained or delivered after reconnect", requestReplay.await())
            println("PASS request receipt uses transient EMQX, creates no invite, and does not replay after reconnect; Android Toast not measured")
        } catch (error: Throwable) { failure = error; throw error }
        finally {
            listener?.close(); subscriber.close()
            GameInviteManager.clearForegroundInvite()
            try {
                check(publisher.awaitHealthy(10_000L)) { "Owned invitation cleanup connection unavailable" }
                for (topic in topics) publisher.publish(topic, ByteArray(0), retained = true)
            } catch (cleanup: Throwable) {
                if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
            } finally { publisher.close() }
        }
    }
}
