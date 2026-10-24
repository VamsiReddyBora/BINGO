package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.model.AuthProvider
import com.bingo.multiplayer.domain.model.UserProfile

/** The public profile observer must not depend on a matching Firebase Google provider. */
internal suspend fun beginProfileSynchronization(
    observe: () -> Unit,
    openSession: suspend () -> Boolean,
    synchronize: () -> Unit
): Boolean {
    observe()
    if (!openSession()) return false
    synchronize()
    return true
}

/** Preserve guest binding. A local Google account may use the existing Firebase storage session. */
internal suspend fun openProfileStorageSession(
    user: UserProfile,
    token: String?,
    authenticate: suspend (String?, String?) -> Boolean
): Boolean {
    val target = if (user.provider == AuthProvider.GOOGLE) null else user.uid
    if (!token.isNullOrBlank() && authenticate(token, target)) return true
    return authenticate(null, target)
}

/** Acknowledgement is written only for the exact revision actually applied by this app. */
internal fun profileAcknowledgementNeeded(data: Map<String, Any?>, appliedRevision: Long, build: Int): Boolean {
    if (appliedRevision <= 0L || (data["adminRevision"] as? Number)?.toLong() != appliedRevision) return false
    val acknowledged = (data["adminAppliedRevision"] as? Number)?.toLong() ?: 0L
    val proofBuild = (data["adminAppliedBuild"] as? Number)?.toInt() ?: 0
    return acknowledged < appliedRevision || (acknowledged == appliedRevision && proofBuild < build)
}
