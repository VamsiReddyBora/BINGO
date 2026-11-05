package com.bingo.multiplayer.presentation.admin

import com.bingo.multiplayer.domain.model.UserProfile
import com.bingo.multiplayer.domain.network.AutomaticCleanupApi
import org.junit.Assert.*
import org.junit.Test

class AdminAccountMatchingTest {
    @Test fun reconnectMustUseTheExistingBingoGoogleIdentity() {
        val user = UserProfile(uid = "google_123456", displayName = "Admin")
        AutomaticCleanupApi.requireMatchingAccount(user, "123456")
        for (identity in listOf(null, "", "654321")) {
            try { AutomaticCleanupApi.requireMatchingAccount(user, identity); fail("Different identity accepted") }
            catch (_: IllegalStateException) { }
        }
    }
}
