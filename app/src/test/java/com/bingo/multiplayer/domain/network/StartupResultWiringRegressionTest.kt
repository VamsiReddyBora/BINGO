package com.bingo.multiplayer.domain.network

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Architectural guards against putting live recovery back into the local startup/result paths.
 * These inspect production wiring; runtime policy tests separately execute the shared callbacks. */
class StartupResultWiringRegressionTest {
    private fun source(path: String): String {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        return File(root, "app/src/main/java/com/bingo/multiplayer/$path").readText()
    }
    @Test fun splashNeverWaitsForRoomDiscovery() {
        val source = source("presentation/auth/AuthGateScreen.kt")
        assertFalse("Splash must not discover rooms", source.contains("OnlineRoomRegistry.getRoom"))
        assertFalse("Splash must not wait for remote cleanup", source.contains("refreshJob.join()"))
    }
    @Test fun automaticUpdatePresentationWaitsForNavigationReadiness() {
        val source = source("MainActivity.kt")
        assertTrue("Automatic checks must await the real navigation readiness signal", source.contains("launchReady.first { it }"))
        assertTrue("Update dialog must be guarded by readiness", source.contains("if (readyForDialogs)"))
    }
    @Test fun savedResultBranchesBeforeLiveRejoinInitialization() {
        val source = source("presentation/navigation/RootNavGraph.kt")
        val restore = source.substringAfter("fun restoreAndRejoinOngoingMatch(").substringBefore("fun finishPeerMatchIfWon")
        assertTrue("Saved result must use the production opening dispatcher", restore.contains("SavedMatchOpening.showResultIfFinished"))
        assertTrue(restore.indexOf("SavedMatchOpening.showResultIfFinished") < restore.indexOf("matchClock.restore"))
    }
    @Test fun finishedResultAcceptsLobbyClosureBeforeGameplayPacketFiltering() {
        val source = source("presentation/navigation/RootNavGraph.kt")
        val handler = source.substringAfter("fun handleIncomingPacket(original:")
        assertTrue("Room departure must be processed while inspecting results", handler.contains("finishedLobby.acceptDeparture"))
        assertTrue(handler.indexOf("finishedLobby.acceptDeparture") < handler.indexOf("handlePeerGamePacket(packet)"))
    }
    @Test fun resultExitUsesAvailabilityAndCannotAlwaysEnterTheOldLobby() {
        val source = source("presentation/game/GameScreen.kt")
        assertTrue("Rendered exit action must use availability", source.contains("isLobbyAvailable && onReturnToLobby != null"))
        assertTrue("Unavailable result must provide a Home action", source.contains("Back to Home"))
        assertTrue(source("presentation/navigation/RootNavGraph.kt").contains("finishedLobby.enterIfAvailable"))
    }
}
