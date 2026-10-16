package com.bingo.multiplayer.domain.notifications

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.bingo.multiplayer.domain.network.NetworkConfig
import com.bingo.multiplayer.domain.model.AuthProvider
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Guest identities prove possession of an installation key; arbitrary legacy guest IDs are never accepted. */
object NotificationIdentity {
    private const val ALIAS = "bingo_notification_guest_v2"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Synchronized private fun publicKey(): ByteArray {
        val store = keyStore()
        if (!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        return keyStore().getCertificate(ALIAS).publicKey.encoded
    }
    fun guestUid(): String = "guest_" + MessageDigest.getInstance("SHA-256").digest(publicKey()).joinToString("") { "%02x".format(it) }
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    suspend fun proof(context: Context, account: NotificationAccount): JSONObject {
        val user = NotificationRuntime.profile(context)
            ?: throw NotificationApiException(401, "Sign in again to enable push notifications")
        if (user.uid != account.uid || notificationUsername(user.username) != account.username) throw NotificationApiException(401, "Account changed")
        if (user.provider == AuthProvider.GOOGLE) {
            if (NetworkConfig.NOTIFICATION_GOOGLE_CLIENT_ID.isBlank()) throw NotificationApiException(503, "Notification sign-in is not configured")
            val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().requestProfile()
                .requestIdToken(NetworkConfig.NOTIFICATION_GOOGLE_CLIENT_ID).build()
            val google = try { GoogleSignIn.getClient(context, options).silentSignIn().await() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: com.google.android.gms.common.api.ApiException) {
                throw NotificationApiException(if (error.statusCode == 7) 503 else 401, "Sign in again to enable secure notifications")
            }
            if ("google_${google.id}" != account.uid) throw NotificationApiException(401, "Google account does not match")
            val token = google.idToken ?: throw NotificationApiException(401, "Sign in again to enable notifications")
            return JSONObject().put("provider", "google").put("idToken", token)
        }
        val key = publicKey()
        if (account.uid != guestUid()) throw NotificationApiException(401, "This older guest profile needs a new guest sign-in or Google sign-in for secure notifications")
        val timestamp = System.currentTimeMillis()
        val nonce = UUID.randomUUID().toString()
        val message = "bingo-notifications-v2\n${account.uid}\n${account.username}\n${account.installationId}\n${account.generation}\n${account.sequence}\n$timestamp\n$nonce"
        val signer = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyStore().getKey(ALIAS, null) as java.security.PrivateKey); update(message.toByteArray(Charsets.UTF_8))
        }
        return JSONObject().put("provider", "guest").put("publicKey", b64(key)).put("signature", b64(signer.sign()))
            .put("timestamp", timestamp).put("nonce", nonce)
    }
}
