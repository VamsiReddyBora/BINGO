package com.bingo.multiplayer.domain.network

/** Shared presentation identity for foreground and background native update offers. */
object ApkUpdateOfferPolicy {
    fun noticeKey(versionCode: Long, tag: String): String = "update:" + if (versionCode > 0) "build-$versionCode" else tag
    fun matchesAccount(offerUid: String?, currentUid: String, notificationUid: String?) =
        (offerUid == null || offerUid == currentUid) && (offerUid == null || offerUid == (notificationUid ?: ""))
}
