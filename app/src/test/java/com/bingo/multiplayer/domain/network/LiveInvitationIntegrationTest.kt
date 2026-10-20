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
        val cleaner = MqttAsyncClient(NetworkConfig.BROKER_URL, "qa-inv-clean-" + UUID.randomUUID().toString().take(8), MemoryPersistence())
        var listener: AutoCloseable? = null
        try {
            GameInviteManager.clearForegroundInvite()
            listener = GameInviteManager.startInviteListener(recipient) {}
            val live = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(15_000L) { GameInviteManager.foregroundInviteFlow.first { it.eventId == invite.eventId } }
            }
            GameInviteManager.publishMqttInvite(recipient, invite)
            val received = live.await()
            assertEquals(invite.eventId, received.eventId)
            assertEquals(invite.roomCode, received.roomCode)
            assertTrue(NotificationPolicy.eligible(received.copy(recipientUid = account.uid).toEvent(), account, System.currentTimeMillis()))
            println("PASS actual invitation publisher -> private broker -> production listener -> foreground flow")

            listener.close()
            GameInviteManager.clearForegroundInvite()
            val retained = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(15_000L) { GameInviteManager.foregroundInviteFlow.first { it.eventId == invite.eventId } }
            }
            listener = GameInviteManager.startInviteListener(recipient) {}
            assertEquals(invite, retained.await())
            println("PASS retained invitation reaches a newly connected production listener")
        } finally {
            listener?.close()
            GameInviteManager.clearForegroundInvite()
            try {
                cleaner.connect(MqttConnectOptions().apply {
                    isCleanSession = true; connectionTimeout = 5; NetworkConfig.applyMqttOptions(this)
                }).waitForCompletion(7_000L)
                cleaner.publish("bingo/v3/invites/$recipient", MqttMessage(ByteArray(0)).apply {
                    qos = 1; isRetained = true
                }).waitForCompletion(4_000L)
            } finally {
                runCatching { cleaner.disconnectForcibly(0L, 50L) }
                runCatching { cleaner.close(true) }
            }
        }
    }
}
