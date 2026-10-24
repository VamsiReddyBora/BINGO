package com.bingo.multiplayer.domain.notifications

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NotificationStartupWiringTest {
    private fun source(path: String): String {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        return File(root, "app/src/main/java/com/bingo/multiplayer/$path").takeIf { it.exists() }?.readText().orEmpty()
    }

    @Test fun reconnectReminderUsesTheHomeRouteAndExistingInteractiveRepair() {
        val root = source("presentation/navigation/RootNavGraph.kt")
        val prompt = source("presentation/components/NotificationReconnectPrompt.kt")
        assertTrue(root.contains("NotificationReconnectPrompt("))
        assertTrue(root.contains("canPrompt = currentRoute == Screen.MainMenu.route"))
        assertTrue(prompt.contains("NotificationGoogleAuth.reconnect"))
        assertTrue(prompt.contains("rememberSaveable"))
        assertTrue(prompt.contains("Reconnect notifications"))
        assertTrue(prompt.contains("Later"))
    }

    @Test fun onlineReturnUsesTheApprovedThirtyMinuteInterval() {
        assertEquals(1_800_000L, NotificationOnlineConfig.OFFLINE_INTERVAL_MS)
    }

    @Test fun systemOnlineNoticeUsesOneSharedTextLayoutWithoutARepeatedBody() {
        val manager = source("domain/network/BingoNotificationManager.kt")
        assertTrue(manager.contains("NotificationPresentation.systemText(event)"))
        assertTrue(manager.contains("notificationText.body?.let"))
    }
}
