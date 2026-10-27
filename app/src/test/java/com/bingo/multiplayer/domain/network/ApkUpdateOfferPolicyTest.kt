package com.bingo.multiplayer.domain.network

import org.junit.Assert.*
import org.junit.Test

class ApkUpdateOfferPolicyTest {
    @Test fun aNewBuildWithTheSameVersionNameHasItsOwnNotificationIdentity() {
        assertNotEquals(ApkUpdateOfferPolicy.noticeKey(76, "v1.0.3"), ApkUpdateOfferPolicy.noticeKey(77, "v1.0.3"))
    }
    @Test fun theSameBuildHasOneIdentityAcrossPublicAndSelectedOffers() {
        assertEquals(ApkUpdateOfferPolicy.noticeKey(76, "v1.0.3"), ApkUpdateOfferPolicy.noticeKey(76, "v1.0.3"))
    }
    @Test fun legacyReleaseWithoutBuildStillHasAVersionIdentity() {
        assertNotEquals(ApkUpdateOfferPolicy.noticeKey(0, "v1.0.3"), ApkUpdateOfferPolicy.noticeKey(0, "v1.0.4"))
    }
    @Test fun anAccountChangeBetweenResolutionAndNotificationBindingIsRejected() {
        assertFalse(ApkUpdateOfferPolicy.matchesAccount("bob", "bob", "stuart"))
        assertFalse(ApkUpdateOfferPolicy.matchesAccount("bob", "stuart", "stuart"))
        assertTrue(ApkUpdateOfferPolicy.matchesAccount("bob", "bob", "bob"))
    }
}
