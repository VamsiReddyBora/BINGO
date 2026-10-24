package com.bingo.multiplayer.domain.notifications

import com.bingo.multiplayer.domain.network.UpdateState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class NotificationReconnectPromptPolicyTest {
    private val account = NotificationAccount("google_bob", "bob", "generation", "installation", 1)
    private fun visible(account: NotificationAccount? = this.account, google: Boolean = true,
                        needs: Boolean = true, home: Boolean = true, foreground: Boolean = true,
                        blocked: Boolean = false, handled: String? = null) =
        NotificationReconnectPromptPolicy.shouldShow(account, google, needs, home, foreground, blocked, handled)

    @After fun cleanStatus() { NotificationDeliveryStatus.clear() }

    @Test fun googleSignInRequiredOnRenderedHomeOffersRepair() {
        NotificationDeliveryStatus.failed(NotificationGoogleAuth.failure(4))
        assertTrue(visible(needs = NotificationDeliveryStatus.needsGoogleAuthorization.value))
    }
    @Test fun networkAndServerFailuresDoNotOfferGoogleSignIn() {
        for (status in listOf(0, 408, 429, 503)) {
            NotificationDeliveryStatus.failed(NotificationApiException(status, "unavailable", NotificationPhase.INBOX_SYNC))
            assertFalse(visible(needs = NotificationDeliveryStatus.needsGoogleAuthorization.value))
        }
    }
    @Test fun configurationFailureDoesNotPretendThatGoogleApprovalCanFixIt() {
        NotificationDeliveryStatus.failed(NotificationGoogleAuth.failure(10))
        assertFalse(visible(needs = NotificationDeliveryStatus.needsGoogleAuthorization.value))
    }
    @Test fun healthyRegistrationClearsTheReminder() {
        NotificationDeliveryStatus.failed(NotificationGoogleAuth.failure(4))
        NotificationDeliveryStatus.clear()
        assertFalse(visible(needs = NotificationDeliveryStatus.needsGoogleAuthorization.value))
    }
    @Test fun loginAndGuestAccountsNeverGetGoogleNotificationPrompts() {
        assertFalse(visible(account = null)); assertFalse(visible(google = false))
    }
    @Test fun splashGameManualBoardLobbyAndUnrenderedHomeCannotOfferThePrompt() {
        assertFalse(visible(home = false))
    }
    @Test fun returningToHomeCanOfferAnUnansweredReminder() {
        assertFalse(visible(home = false)); assertTrue(visible(home = true))
    }
    @Test fun backgroundCannotLaunchAVisibleReminder() {
        assertFalse(visible(foreground = false)); assertTrue(visible(foreground = true))
    }
    @Test fun anInvitationOrAnotherDialogDefersRatherThanConsumesTheReminder() {
        assertFalse(visible(blocked = true)); assertTrue(visible(blocked = false))
    }
    @Test fun laterAndReconnectConsumeThisAccountReminderForTheActivitySession() {
        val saved = NotificationReconnectPromptPolicy.accountKey(account)
        repeat(3) { assertFalse(visible(handled = saved)) }
        // The next fresh Activity starts without a saved decision.
        assertTrue(visible(handled = null))
    }
    @Test fun restoredDecisionPreventsRotationAndNavigationFromNaggingAgain() {
        val saved = NotificationReconnectPromptPolicy.accountKey(account)
        assertFalse(visible(home = false, handled = saved))
        assertFalse(visible(home = true, handled = saved))
    }
    @Test fun aFreshProcessCanRemindEvenWhenAndroidRestoresAnOldActivityBundle() {
        val previousLaunch = NotificationReconnectPromptPolicy.accountKey(account, "old-process")
        assertTrue(visible(handled = previousLaunch))
    }
    @Test fun googleResultRestoredAfterRotationRequiresTheSameAccountAndProcess() {
        val pending = NotificationReconnectPromptPolicy.accountKey(account)
        assertEquals(account, NotificationReconnectPromptPolicy.pendingAccount(account, pending))
        assertNull(NotificationReconnectPromptPolicy.pendingAccount(null, pending))
        assertNull(NotificationReconnectPromptPolicy.pendingAccount(account.copy(generation = "new"), pending))
        assertNull(NotificationReconnectPromptPolicy.pendingAccount(account.copy(uid = "other"), pending))
        assertNull(NotificationReconnectPromptPolicy.pendingAccount(account,
            NotificationReconnectPromptPolicy.accountKey(account, "old-process")))
    }
    @Test fun aDifferentPlayerOrNewAccountGenerationDoesNotInheritAnotherPlayersChoice() {
        val saved = NotificationReconnectPromptPolicy.accountKey(account)
        assertTrue(visible(account = account.copy(uid = "google_other"), handled = saved))
        assertTrue(visible(account = account.copy(generation = "new-generation"), handled = saved))
    }
    @Test fun updateChecksAndEveryVisibleUpdateDialogGetPriority() {
        for (state in listOf(UpdateState.Checking, UpdateState.Error("offline"),
                UpdateState.Downloading(.5f, 50, 100), UpdateState.ReadyToInstall(java.io.File("update.apk")),
                UpdateState.UpdateAvailable(com.bingo.multiplayer.domain.network.AppUpdateInfo(true, "v1.0.2", "1.0.2", "https://example.invalid/app.apk", "", 100))))
            assertTrue(NotificationReconnectPromptPolicy.updateBlocksPrompt(state))
        assertFalse(NotificationReconnectPromptPolicy.updateBlocksPrompt(UpdateState.Idle))
        assertFalse(NotificationReconnectPromptPolicy.updateBlocksPrompt(UpdateState.UpToDate))
    }
}
