package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrokerWarningPolicyTest {
    @Test fun offlineNeverShowsServerWarning() = runTest {
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            brokerWarningVisibility(MutableStateFlow(false), MutableStateFlow(true), MutableStateFlow(true)).collect(values::add)
        }
        advanceTimeBy(60_000); runCurrent()
        assertFalse(values.last())
    }

    @Test fun warningWaitsExactlyTenSecondsOfOnlineFailure() = runTest {
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            brokerWarningVisibility(MutableStateFlow(true), MutableStateFlow(true), MutableStateFlow(true)).collect(values::add)
        }
        runCurrent(); assertFalse(values.last())
        advanceTimeBy(9_999); runCurrent(); assertFalse(values.last())
        advanceTimeBy(1); runCurrent(); assertTrue(values.last())
    }

    @Test fun reconnectingInternetStartsAFullNewGracePeriod() = runTest {
        val online = MutableStateFlow(true); val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            brokerWarningVisibility(online, MutableStateFlow(true), MutableStateFlow(true)).collect(values::add)
        }
        advanceTimeBy(9_000); runCurrent()
        online.value = false; runCurrent(); assertFalse(values.last())
        advanceTimeBy(60_000); online.value = true; runCurrent(); assertFalse(values.last())
        advanceTimeBy(9_999); runCurrent(); assertFalse(values.last())
        advanceTimeBy(1); runCurrent(); assertTrue(values.last())
        online.value = false; runCurrent(); assertFalse(values.last())
    }

    @Test fun backgroundTimeNeverConsumesResumeGrace() = runTest {
        val foreground = MutableStateFlow(true); val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            brokerWarningVisibility(MutableStateFlow(true), foreground, MutableStateFlow(true)).collect(values::add)
        }
        advanceTimeBy(10_000); runCurrent(); assertTrue(values.last())
        foreground.value = false; runCurrent(); assertFalse(values.last())
        advanceTimeBy(60_000); foreground.value = true; runCurrent(); assertFalse(values.last())
        advanceTimeBy(9_999); runCurrent(); assertFalse(values.last())
        advanceTimeBy(1); runCurrent(); assertTrue(values.last())
    }

    @Test fun brokerRecoveryCancelsPendingWarningAndClearsVisibleWarning() = runTest {
        val down = MutableStateFlow(true); val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            brokerWarningVisibility(MutableStateFlow(true), MutableStateFlow(true), down).collect(values::add)
        }
        advanceTimeBy(9_000); down.value = false; runCurrent()
        advanceTimeBy(20_000); runCurrent(); assertFalse(values.last())
        down.value = true; runCurrent(); assertFalse(values.last())
        advanceTimeBy(10_000); runCurrent(); assertTrue(values.last())
        down.value = false; runCurrent(); assertFalse(values.last())
    }

    @Test fun repeatedOnlineCallbacksDoNotPostponeTheWarning() = runTest {
        val online = MutableSharedFlow<Boolean>(replay = 1); online.tryEmit(true)
        val values = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            brokerWarningVisibility(online, MutableStateFlow(true), MutableStateFlow(true)).collect(values::add)
        }
        runCurrent(); assertFalse(values.last())
        repeat(9) { advanceTimeBy(1_000); online.emit(true); runCurrent() }
        advanceTimeBy(1_000); runCurrent(); assertTrue(values.last())
    }
}
