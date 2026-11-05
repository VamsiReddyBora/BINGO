package com.bingo.multiplayer.core.security

import com.bingo.multiplayer.domain.content.ContentPacks
import com.bingo.multiplayer.domain.model.UserProfile

/** The same registered account identities used by the selected-account update channels. */
object AdminControlAccess {
    private val accountAudiences = setOf(
        "2778fa42104c3f64703c5fe9abcbd76b356a4167176fac0a22ee08f22f11b3f9", // @bob
        "492e92d03b7d4c82194ec0a5c453b9a052ec6e36cbc8ea45f8d2c408fd135b6f"  // @vampire
    )

    fun canAccess(user: UserProfile?): Boolean = user != null && canAccessUid(user.uid)

    internal fun canAccessUid(uid: String, allowedAudiences: Set<String> = accountAudiences): Boolean =
        uid.isNotBlank() && ContentPacks.audience(uid) in allowedAudiences

    internal fun isAuthorizedAudience(audience: String): Boolean = audience in accountAudiences
}
