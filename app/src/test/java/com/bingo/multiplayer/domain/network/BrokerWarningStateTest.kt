package com.bingo.multiplayer.domain.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BrokerWarningStateTest {
    @Test fun visibleWarningSurvivesLeavingHomeAndReturningWithoutAnotherGracePeriod() = runTest {
        val state = BrokerWarningState(backgroundScope, MutableStateFlow(true), MutableStateFlow(true), MutableStateFlow(true))
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.visible.collect {} }
        runCurrent(); advanceTimeBy(10_000L); runCurrent(); assertTrue(state.visible.value)
        first.cancelAndJoin(); runCurrent(); advanceTimeBy(30_000L); runCurrent()
        val second = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.visible.collect {} }
        runCurrent(); assertTrue("Returning Home must immediately show the same unresolved outage", state.visible.value)
        second.cancelAndJoin()
    }
    @Test fun graceContinuesWhileDashboardOrSettingsHasNoHomeCollector() = runTest {
        val state = BrokerWarningState(backgroundScope, MutableStateFlow(true), MutableStateFlow(true), MutableStateFlow(true))
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.visible.collect {} }
        runCurrent(); advanceTimeBy(4000L); runCurrent(); first.cancelAndJoin(); runCurrent()
        advanceTimeBy(6000L); runCurrent()
        val second = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.visible.collect {} }
        runCurrent(); assertTrue("One continuous outage must not restart its grace on tab navigation", state.visible.value)
        second.cancelAndJoin()
    }
    @Test fun recoveryWhileAwayFromHomeClearsTheWarningAndANewOutageGetsANewGrace() = runTest {
        val down = MutableStateFlow(true)
        val state = BrokerWarningState(backgroundScope, MutableStateFlow(true), MutableStateFlow(true), down)
        val first = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.visible.collect {} }
        runCurrent(); advanceTimeBy(10_000L); runCurrent(); assertTrue(state.visible.value)
        first.cancelAndJoin(); runCurrent(); down.value = false; runCurrent(); assertFalse(state.visible.value)
        down.value = true; runCurrent(); advanceTimeBy(9999L); runCurrent(); assertFalse(state.visible.value)
        advanceTimeBy(1L); runCurrent(); assertTrue(state.visible.value)
    }
    @Test fun phoneOfflineAndAppBackgroundStillKeepTheExistingGraceRules() = runTest {
        val online = MutableStateFlow(false); val foreground = MutableStateFlow(true)
        val state = BrokerWarningState(backgroundScope, online, foreground, MutableStateFlow(true))
        runCurrent(); advanceTimeBy(60_000L); runCurrent(); assertFalse(state.visible.value)
        online.value = true; runCurrent(); advanceTimeBy(10_000L); runCurrent(); assertTrue(state.visible.value)
        foreground.value = false; runCurrent(); assertFalse(state.visible.value)
        advanceTimeBy(60_000L); foreground.value = true; runCurrent(); assertFalse(state.visible.value)
        advanceTimeBy(9999L); runCurrent(); assertFalse(state.visible.value)
        advanceTimeBy(1L); runCurrent(); assertTrue(state.visible.value)
    }
    @Test fun repeatedCollectorsShareOneConnectivityObserver() = runTest {
        var starts = 0
        val state = BrokerWarningState(backgroundScope, MutableStateFlow(true).onStart { starts++ }, MutableStateFlow(true), MutableStateFlow(true))
        repeat(4) {
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.visible.collect {} }
            runCurrent(); job.cancelAndJoin(); runCurrent()
        }
        assertEquals("Tab changes must not register another connectivity observer", 1, starts)
    }
    @Test fun homeRestoresTheExplicitEmqxWordingAndUsesTheSharedWarning() {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        val folder = File(root, "app/src/main/java/com/bingo/multiplayer/presentation/menu")
        val home = File(folder,"MainMenuScreen.kt").readText()
        assertTrue(home.contains("EMQX server is temporarily unavailable. Online game play may not work properly. Please use Developer note to report this issue."))
        assertFalse(home.contains("Online server is not responding."))
        assertTrue(File(folder,"BrokerWarningPresentation.kt").readText().contains("BrokerWarningPresentation.visibility(context)"))
    }
}
