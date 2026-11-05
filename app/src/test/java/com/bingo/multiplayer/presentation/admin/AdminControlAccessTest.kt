package com.bingo.multiplayer.presentation.admin

import com.bingo.multiplayer.core.security.AdminControlAccess
import com.bingo.multiplayer.domain.content.ContentPacks
import com.bingo.multiplayer.domain.model.UserProfile
import org.junit.Assert.*
import org.junit.Test

class AdminControlAccessTest {
    @Test fun bothExistingRegisteredAccountAudiencesAreAuthorized() {
        assertTrue(AdminControlAccess.isAuthorizedAudience("2778fa42104c3f64703c5fe9abcbd76b356a4167176fac0a22ee08f22f11b3f9"))
        assertTrue(AdminControlAccess.isAuthorizedAudience("492e92d03b7d4c82194ec0a5c453b9a052ec6e36cbc8ea45f8d2c408fd135b6f"))
        assertFalse(AdminControlAccess.isAuthorizedAudience(ContentPacks.audience("fictional-other-account")))
    }

    @Test fun productionUidMatchingUsesTheAccountDigest() {
        val bob = "fictional-bob-uid"
        val vampire = "fictional-vampire-uid"
        val allowed = setOf(ContentPacks.audience(bob), ContentPacks.audience(vampire))
        assertTrue(AdminControlAccess.canAccessUid(bob, allowed))
        assertTrue(AdminControlAccess.canAccessUid(vampire, allowed))
        assertFalse(AdminControlAccess.canAccessUid("fictional-other-uid", allowed))
        assertFalse(AdminControlAccess.canAccessUid("", allowed))
    }

    @Test fun matchingNamesDoNotGrantAnUnapprovedAccountAccess() {
        for (name in listOf("bob", "vampire", "BOB", "VAMPIRE")) {
            assertFalse(AdminControlAccess.canAccess(UserProfile(uid = "fictional-outsider", username = name, displayName = name)))
            assertFalse(AdminControlAccess.canAccess(UserProfile(uid = "fictional-outsider", displayName = name)))
        }
    }

    @Test fun signedOutAndBlankIdentitiesAreDenied() {
        assertFalse(AdminControlAccess.canAccess(null))
        assertFalse(AdminControlAccess.canAccess(UserProfile(uid = "", username = "bob", displayName = "Bob")))
        assertFalse(AdminControlAccess.canAccessUid("   "))
    }
}
