package com.bingo.multiplayer.domain.notifications

import com.bingo.multiplayer.domain.repository.AuthRepositoryTest.FakeSharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FcmRepairWiringRegressionTest {
    private val start = 1_800_000_000_000L
    private fun source(relative: String): String {
        var folder = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (!File(folder, "app/src").isDirectory) folder = folder.parentFile ?: error("Source root not found")
        return File(folder, "app/src/main/java/com/bingo/multiplayer/$relative").readText()
    }

    @Test fun configuredContinuousAbsenceQualifiesAtTheExactBoundary() {
        val store = FriendOnlineAnnouncements(FakeSharedPreferences())
        val original = store.active("bob", start)!!
        store.offline("bob", start + 1_000)
        val interval = NotificationOnlineConfig.OFFLINE_INTERVAL_MS
        assertNotEquals(original.id, store.active("bob", start + interval + 1_000)?.id)
        assertNotNull(store.active("bob", start + interval + 1_001))
        assertEquals(1_800_000L, FriendOnlineAnnouncements.OFFLINE_INTERVAL)
    }

    @Test fun receiverCooldownAllowsANewNoticeAtConfiguredInterval() {
        val prefs = FakeSharedPreferences()
        fun event(id: String, at: Long) = NotificationEvent(id, NotificationKind.FRIEND_ONLINE,
            "bob", "bob", "Bob", "stuart", "stuart", at, at + 120_000)
        assertTrue(NotificationLedger(prefs) { start }.present(event("first", start), true).show)
        val next = start + NotificationOnlineConfig.OFFLINE_INTERVAL_MS
        assertTrue(NotificationLedger(prefs) { next }.present(event("second", next), true).show)
    }

    @Test fun settingsUsesAnInteractiveAccountPreservingNotificationRepair() {
        val settings = source("presentation/settings/SettingsScreen.kt")
        assertTrue(settings.contains("NotificationGoogleAuth.reconnect"))
        assertTrue(settings.contains("Reconnect notifications"))
        assertTrue(settings.contains("notificationRepairAccount"))
    }

    @Test fun googleFailuresKeepTheOriginalErrorAndOrigin() {
        assertTrue(source("domain/notifications/NotificationIdentity.kt").contains("NotificationGoogleAuth.failure"))
        assertTrue(source("domain/notifications/NotificationDiagnostics.kt").contains("googleStatus"))
        assertTrue(source("domain/notifications/NotificationDiagnostics.kt").contains("phase"))
    }

    @Test fun failedPushEnqueueIsSeparateFromTheSuccessfulMqttInvite() {
        val invite = source("domain/network/GameInviteManager.kt")
        assertTrue(invite.contains("SENT_PUSH_UNAVAILABLE"))
        assertTrue(invite.contains("pushQueued"))
    }

    @Test fun homeUsesTheExactApprovedNoteWithoutChangingWarningOwnership() {
        val menu = source("presentation/menu/MainMenuScreen.kt")
        assertTrue(menu.contains("Unable to connect to the EMQX MQTT broker. Online invitations and match synchronization may be interrupted. Please report this through Developer Note."))
        assertTrue(source("presentation/menu/BrokerWarningPresentation.kt").contains("BrokerWarningState"))
    }
}
