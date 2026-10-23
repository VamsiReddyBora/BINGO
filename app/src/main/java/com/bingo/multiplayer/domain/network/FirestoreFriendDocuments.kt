package com.bingo.multiplayer.domain.network

import com.bingo.multiplayer.domain.repository.AuthRepository
import com.bingo.multiplayer.domain.notifications.NotificationAccount
import com.bingo.multiplayer.domain.notifications.NotificationRuntime
import com.bingo.multiplayer.domain.model.UserProfile
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

internal fun socialAccountUid(username: String, notification: NotificationAccount?, profile: UserProfile?): String? =
    notification?.takeIf { it.username == username }?.uid ?: profile?.takeIf {
        it.username.trim().removePrefix("@").lowercase() == username
    }?.uid

/** Small SDK adapter; admission and request decisions are in FirebaseFriendsStore. */
internal class FirestoreFriendDocuments(private val db: FirebaseFirestore) : FriendDocuments {
    private val uidCache = ConcurrentHashMap<String, String>()
    override suspend fun uid(username: String): String? {
        val notificationAccount = NotificationRuntime.context()?.let { NotificationRuntime.account(it) }
        socialAccountUid(username, notificationAccount, AuthRepository.activeInstance?.getPersistedUserSync())?.let { return it }
        uidCache[username]?.let { return it }
        val indexed = db.collection("usernames").document(username).get().await().getString("uid")
        val id = indexed ?: AccountSessionManager.searchPlayerByUsername(username, timeoutMs = 2500L)?.uid
        if (!id.isNullOrBlank()) uidCache[username] = id
        return id
    }
    override suspend fun list(path: String): List<Map<String, Any?>> =
        db.collection(path).get(Source.SERVER).await().documents.mapNotNull { it.data }
    override suspend fun get(path: String): Map<String, Any?>? = db.document(path).get(Source.SERVER).await().data
    override suspend fun mergeAll(documents: Map<String, Map<String, Any?>>) {
        // Each document is independent. Batch chunks avoid dropping a larger existing roster.
        for (chunk in documents.entries.chunked(400)) {
            val batch = db.batch()
            chunk.forEach { batch.set(db.document(it.key), it.value, SetOptions.merge()) }
            batch.commit().await()
        }
    }
    override suspend fun transaction(block: (FriendDocuments.Transaction) -> Boolean): Boolean =
        db.runTransaction { transaction ->
            block(object : FriendDocuments.Transaction {
                override fun get(path: String): Map<String, Any?>? = transaction.get(db.document(path)).data
                override fun merge(path: String, data: Map<String, Any?>) {
                    transaction.set(db.document(path), data, SetOptions.merge())
                }
            })
        }.await()
}
