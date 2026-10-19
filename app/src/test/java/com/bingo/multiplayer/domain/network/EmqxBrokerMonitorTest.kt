package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmqxBrokerMonitorTest {

    @Test
    fun testEmqxBrokerMonitorTransitions() = runBlocking {
        // A live startup probe must not overwrite the explicit transitions under test.
        EmqxBrokerMonitor.stopAndAwait()
        // Record broker success -> isBrokerDown must be false
        EmqxBrokerMonitor.recordBrokerSuccess()
        assertFalse("Broker should not be down after recordBrokerSuccess", EmqxBrokerMonitor.isBrokerDown.value)

        // Record broker failure -> isBrokerDown transitions
        EmqxBrokerMonitor.recordBrokerFailure()
        // Wait briefly for coroutine scope
        kotlinx.coroutines.delay(100L)
        // Check state
        assertTrue("Broker should be reported down after recordBrokerFailure when network is up", EmqxBrokerMonitor.isBrokerDown.value)

        // When broker recovers -> isBrokerDown clears immediately
        EmqxBrokerMonitor.recordBrokerSuccess()
        assertFalse("Broker should be restored after recordBrokerSuccess", EmqxBrokerMonitor.isBrokerDown.value)
    }
}
