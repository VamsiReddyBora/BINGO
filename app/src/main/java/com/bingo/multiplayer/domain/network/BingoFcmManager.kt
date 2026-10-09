package com.bingo.multiplayer.domain.network

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.bingo.multiplayer.domain.model.FriendRequest
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

object BingoFcmManager {
    private const val TAG = "BingoFcmManager"
    private const val PREFS_NAME = "bingo_fcm_prefs"
    private const val KEY_FCM_TOKEN = "fcm_device_token"

    // Firebase Service Account Credentials for Direct Google Cloud FCM v1 Sending
    private const val FCM_PROJECT_ID = "bingo-6bb09"
    private const val FCM_CLIENT_EMAIL = "firebase-adminsdk-fbsvc@bingo-6bb09.iam.gserviceaccount.com"
    private const val FCM_PRIVATE_KEY_PEM = "-----BEGIN PRIVATE KEY-----\n" +
            "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQDN6Seh6Qy9GOil\n" +
            "oclCCA4UeTaKWFc9aMjedW40ElprL8cEdptY56IllUL+UhAbOmyK79eAF6tu8lxP\n" +
            "EOkdzJ2CtBQqwd17wbCf/2vkKdc3Vkta4kkslozIShfvxRLiSb83GkERUrbjRubG\n" +
            "2MykwUyJjGRF/ZI6Q1NhhGiM4kAV3c/mFjngGa527Zznz6+Ob9G5xL3LTSXoBfNE\n" +
            "v4C29iL9qzXWfTniEdT1EfxLda1z+7Us3GvvgTeA+BfFnnJ/Bzg2/CkPclIryghe\n" +
            "kA9ptANsRm/X3sHKrNvQhW/VZnBuS9lGL5TK2OAGsL/0mzigFyOZ/Dq3huyCtOIu\n" +
            "piRONLizAgMBAAECggEAGQOlgmhW2VQA0zpDwkdLOpZ9FzJjKr0jhc7bO+0s2c+c\n" +
            "jEDCX3sIOiuXT2D1vvEKhZhcZB28AEbmCt7hivK0AdBRkN4rQ2EEzXMQjs+8aucL\n" +
            "UXei7w08/gnuPX0B7caKua1xUSLsv9B5sZddyPgIjb8l4VDMJlLOes7EirTjlyQ6\n" +
            "nw+mMswopO3V+Zl8TmdFlV7nScG2egIafpKPMoaTLLb00Eq2R3AxUA1tzs4GZSvc\n" +
            "9Vtu/mn+vselLfb+0+qI76VFV138IgHbhPDWfcL23MiFcJzokBHNkhnzRZPhsvFC\n" +
            "yvcB6gyBy0wQMGgnj34ETAnMSwxTRAiOvl1IgWEREQKBgQD7F6NgTwyH4xi7KTbX\n" +
            "I/XcBx4Ztfx/gAUtFk4HnQfnuX2hfCjuAIRQWv2ADgBlsUa86kt2o3c+r0laMvTj\n" +
            "YKr3AYZducu6bxf5fahqSHgPClQPQKxDBF299KAZRlBv/wMMEB7hQQw66TlF8lhP\n" +
            "Hlk2vTE8raECZK1mUTWbxSNrsQKBgQDR73JgElWIa+l+/9cdtoUV8cmGE+ZzlB3j\n" +
            "4wGpjkYWgQ8QUUbittHvaXyGx4D6bA7B+cmWZa7GphmwQd4v3O4MkNI5OA7ShCbX\n" +
            "4mN5oygozY5u5SlLFitvul+1AzlUs63GqRK3AlmdzmL+lXRqWJ0NxWW11xLfckvi\n" +
            "WDzZSW5XowKBgDquJ4xWbQNE237B/wMAcHDfaPVxRnU1ogALemjlFffdrbKTpa0Z\n" +
            "idKNsTjADO+3ImT8DG7JfRC1PltKFVkeOlZHkPNOfIIxfFTePQG5tfUt4L8/ygJP\n" +
            "fujpxpChkiLaYgfrrIvP+9+4qZ3jKSg0W30jceJQYZSBmtSSngitZb3BAoGBAJ0L\n" +
            "pOIdlQKix1+L/95oZZKO95RnWqPnj5yket/eYKwBC8XHJ2H+JXoVzWP95oxvPXL6\n" +
            "a0Uo99/+7YSfIZloimO4CqtnNh9hYLVq08NwvGAZtY1bvNJA2WmRYHtG2CJ2726H\n" +
            "mEpzZZrZg9Cy+Q19EK/2lSm8pI+nLwE5xPs/JV5FAoGARbKS/0s0noqTlBSJCM1c\n" +
            "cgMSygli66n3sI4E9SrDmHF92JwEOusyrfhXM9U9SxEbQXWoF0GU1E6HRNrS2OOc\n" +
            "y6gf2j+VT68YBkjYsspmqriD7oZ5c34wKm4yIR8h71w0s+H9ITZ6C/GwImQr0yjv\n" +
            "wzjDqx7TVAZbS3xlUDhSkiU=\n" +
            "-----END PRIVATE KEY-----"

    private const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val GOOGLE_FCM_V1_URL = "https://fcm.googleapis.com/v1/projects/$FCM_PROJECT_ID/messages:send"

    private val httpClient = NetworkConfig.httpClient
    private val appKey = NetworkConfig.KEYVALUE_APP_KEY
    private val scope = CoroutineScope(Dispatchers.IO)

    // In-memory OAuth2 Access Token Cache
    @Volatile
    private var cachedAccessToken: String? = null
    @Volatile
    private var tokenExpiryEpochMs: Long = 0L

    fun init(context: Context) {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    Log.i(TAG, "Fetched FCM registration token: ${token.take(15)}...")
                    saveToken(context, token)
                    val localUser = BroadcastMessageManager.getLocalUsername(context)
                    if (localUser.isNotBlank()) {
                        syncTokenToCloud(context, localUser, token)
                    }
                } else {
                    Log.w(TAG, "Failed to fetch FCM registration token: ${task.exception?.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseMessaging init warning: ${e.message}")
        }
    }

    fun saveToken(context: Context, token: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_FCM_TOKEN, token.trim())
                .apply()
        } catch (_: Exception) {}
    }

    fun getSavedToken(context: Context): String? {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_FCM_TOKEN, null)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Publishes this user's FCM device token to the Cloud Key-Value store.
     * This enables any opponent or friend to look up this device's token and send a push.
     */
    fun syncTokenToCloud(context: Context, username: String, token: String) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank() || token.isBlank()) return

        scope.launch {
            try {
                val encKey = URLEncoder.encode("fcm_$clean", "UTF-8")
                val encVal = URLEncoder.encode(token.trim(), "UTF-8")
                val url = "${NetworkConfig.KEYVALUE_API_URL}/UpdateValue/$appKey/$encKey?value=$encVal"
                val request = Request.Builder()
                    .url(url)
                    .post("".toRequestBody(null))
                    .header("Content-Length", "0")
                    .build()
                httpClient.newCall(request).execute().close()
                Log.d(TAG, "Synced FCM token to cloud directory for @$clean")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync FCM token to cloud: ${e.message}")
            }
        }
    }

    /**
     * Ensures this user's FCM device token is synced to the Cloud Key-Value store.
     * Can be safely called upon login, resume, presence start, or account switch.
     */
    fun syncCurrentUserToken(context: Context, username: String) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return
        val saved = getSavedToken(context)
        if (!saved.isNullOrBlank()) {
            syncTokenToCloud(context, clean, saved)
        } else {
            try {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful && !task.result.isNullOrBlank()) {
                        val token = task.result
                        saveToken(context, token)
                        syncTokenToCloud(context, clean, token)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch fresh token for sync: ${e.message}")
            }
        }
    }

    /**
     * Fetches the FCM device token for a specific user from Cloud Key-Value store.
     */
    suspend fun fetchTokenForUser(username: String): String? = withContext(Dispatchers.IO) {
        val clean = username.trim().lowercase().removePrefix("@")
        if (clean.isBlank()) return@withContext null
        try {
            val encKey = URLEncoder.encode("fcm_$clean", "UTF-8")
            val url = "${NetworkConfig.KEYVALUE_API_URL}/GetValue/$appKey/$encKey"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string()?.trim()?.removeSurrounding("\"")
                if (!body.isNullOrBlank() && body != "null") body else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } else {
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).trim().replace("=", "")
        }
    }

    /**
     * Generates or returns a valid cached Google OAuth2 access token
     * by signing a JWT with the Firebase Service Account private key (RS256).
     */
    @Synchronized
    private fun getOrFetchAccessToken(): String? {
        val now = System.currentTimeMillis()
        if (cachedAccessToken != null && now < (tokenExpiryEpochMs - 300_000L)) {
            return cachedAccessToken
        }

        return try {
            // 1. Parse Private Key
            val cleanKey = FCM_PRIVATE_KEY_PEM
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\n", "")
                .replace("\r", "")
                .trim()
            val keyBytes = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    java.util.Base64.getDecoder().decode(cleanKey)
                } else {
                    Base64.decode(cleanKey, Base64.DEFAULT)
                }
            } catch (_: Exception) {
                Base64.decode(cleanKey, Base64.DEFAULT)
            }
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val privateKey: PrivateKey = keyFactory.generatePrivate(keySpec)

            // 2. Construct RS256 JWT
            // Google OAuth JWT bearer allows up to 60s clock skew. We set iat = nowSec - 60L
            // so slight device clock discrepancies never cause "invalid_grant" token rejections.
            val nowSec = now / 1000L
            val headerJson = "{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
            val payloadJson = JSONObject().apply {
                put("iss", FCM_CLIENT_EMAIL)
                put("scope", "https://www.googleapis.com/auth/firebase.messaging")
                put("aud", GOOGLE_TOKEN_URL)
                put("exp", nowSec + 3600L)
                put("iat", nowSec - 60L)
            }.toString()

            val headerB64 = base64UrlEncode(headerJson.toByteArray(StandardCharsets.UTF_8))
            val payloadB64 = base64UrlEncode(payloadJson.toByteArray(StandardCharsets.UTF_8))
            val unsignedToken = "$headerB64.$payloadB64"

            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(privateKey)
            signer.update(unsignedToken.toByteArray(StandardCharsets.UTF_8))
            val sigBytes = signer.sign()
            val sigB64 = base64UrlEncode(sigBytes)
            val jwtAssertion = "$unsignedToken.$sigB64"

            // 3. Exchange JWT with Google OAuth2 Token Endpoint
            val formBody = FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                .add("assertion", jwtAssertion)
                .build()

            val tokenReq = Request.Builder()
                .url(GOOGLE_TOKEN_URL)
                .post(formBody)
                .build()

            httpClient.newCall(tokenReq).execute().use { response ->
                val respStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "OAuth2 token request failed: code=${response.code}, body=$respStr")
                    return null
                }
                val json = JSONObject(respStr)
                val token = json.getString("access_token")
                val expiresInSec = json.optLong("expires_in", 3600L)
                cachedAccessToken = token
                tokenExpiryEpochMs = now + (expiresInSec * 1000L)
                Log.i(TAG, "Successfully acquired fresh Google FCM access token")
                token
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Google OAuth2 token: ${e.message}", e)
            null
        }
    }

    /**
     * Sends a High-Priority Data-Only FCM push notification directly to Google's FCM v1 API.
     * Delivering as a high-priority data payload guarantees:
     * 1. Google Play Services delivers it directly to BingoFirebaseMessagingService on target device.
     * 2. When the app is in the FOREGROUND, BingoFirebaseMessagingService displays in-app modal / toast
     *    and SUPPRESSES the status bar notification (Rule 2).
     * 3. When the app is in the BACKGROUND or KILLED, BingoFirebaseMessagingService invokes
     *    BingoNotificationManager to post rich notifications with interactive action buttons
     *    (Accept / Decline, Invite to Game, etc.).
     */
    fun sendPushNotification(
        targetToken: String,
        type: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        if (targetToken.isBlank()) {
            onComplete?.invoke(false, "Target device token is empty")
            return
        }

        scope.launch {
            try {
                val accessToken = getOrFetchAccessToken()
                if (accessToken.isNullOrBlank()) {
                    Log.e(TAG, "Cannot send FCM push: OAuth access token unavailable")
                    withContext(Dispatchers.Main) {
                        onComplete?.invoke(false, "OAuth token acquisition failed")
                    }
                    return@launch
                }

                // High-priority data-only payload
                val dataObj = JSONObject().apply {
                    put("type", type)
                    put("title", title)
                    put("body", body)
                    data.forEach { (k, v) -> put(k, v) }
                }

                val androidConfig = JSONObject().apply {
                    put("priority", "HIGH")
                }

                val messageObj = JSONObject().apply {
                    put("token", targetToken)
                    put("data", dataObj)
                    put("android", androidConfig)
                }

                val rootJson = JSONObject().apply {
                    put("message", messageObj)
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = rootJson.toString().toRequestBody(mediaType)
                val request = Request.Builder()
                    .url(GOOGLE_FCM_V1_URL)
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post(requestBody)
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    val isSuccess = resp.isSuccessful
                    val respBody = resp.body?.string().orEmpty()
                    if (isSuccess) {
                        Log.i(TAG, "Direct Google FCM v1 push dispatched successfully: $type")
                        withContext(Dispatchers.Main) {
                            onComplete?.invoke(true, "Sent successfully via Google FCM")
                        }
                    } else {
                        Log.e(TAG, "FCM v1 dispatch error HTTP ${resp.code}: $respBody")
                        withContext(Dispatchers.Main) {
                            onComplete?.invoke(false, "Google FCM returned HTTP ${resp.code}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "FCM dispatch exception: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, e.message ?: "Unknown error")
                }
            }
        }
    }

    /**
     * Sends an FCM match invitation push to target user.
     */
    fun sendInvitePush(targetUsername: String, invite: GameInvite) {
        scope.launch {
            val token = fetchTokenForUser(targetUsername)
            if (!token.isNullOrBlank()) {
                sendPushNotification(
                    targetToken = token,
                    type = "GAME_INVITE",
                    title = "🎮 Game Invite from ${invite.fromDisplayName}",
                    body = "@${invite.fromUsername} invited you to play a Bingo match! (Room ${invite.roomCode})",
                    data = mapOf(
                        "roomCode" to invite.roomCode,
                        "fromUsername" to invite.fromUsername,
                        "fromDisplayName" to invite.fromDisplayName,
                        "timestamp" to invite.timestamp.toString()
                    )
                )
            } else {
                Log.d(TAG, "No FCM token registered for @$targetUsername; opponent must be online via MQTT")
            }
        }
    }

    /**
     * Sends an FCM friend request push to target user.
     */
    fun sendFriendRequestPush(targetUsername: String, request: FriendRequest) {
        scope.launch {
            val token = fetchTokenForUser(targetUsername)
            if (!token.isNullOrBlank()) {
                val displayName = request.fromDisplayName.ifBlank { request.fromUsername }
                sendPushNotification(
                    targetToken = token,
                    type = "FRIEND_REQUEST",
                    title = "👥 Friend Request from $displayName",
                    body = "@${request.fromUsername} sent you a friend request!",
                    data = mapOf(
                        "fromUsername" to request.fromUsername,
                        "fromDisplayName" to displayName,
                        "fromUid" to request.fromUid,
                        "type" to "FRIEND_REQUEST"
                    )
                )
            } else {
                Log.d(TAG, "No FCM token for @$targetUsername; friend request delivered via MQTT/Cloud")
            }
        }
    }

    /**
     * Sends an FCM friend accepted push to target user.
     */
    fun sendFriendAcceptedPush(targetUsername: String, myUsername: String, myDisplayName: String) {
        scope.launch {
            val token = fetchTokenForUser(targetUsername)
            if (!token.isNullOrBlank()) {
                val displayName = myDisplayName.ifBlank { myUsername }
                sendPushNotification(
                    targetToken = token,
                    type = "FRIEND_ACCEPTED",
                    title = "🎉 Friend Request Accepted",
                    body = "$displayName accepted your friend request!",
                    data = mapOf(
                        "fromUsername" to myUsername,
                        "fromDisplayName" to displayName,
                        "type" to "FRIEND_ACCEPTED"
                    )
                )
            } else {
                Log.d(TAG, "No FCM token for @$targetUsername; acceptance delivered via MQTT/Cloud")
            }
        }
    }

    /**
     * Sends an FCM push to a friend notifying them that this user is online.
     */
    fun sendFriendOnlinePush(targetUsername: String, myUsername: String, myDisplayName: String) {
        scope.launch {
            val token = fetchTokenForUser(targetUsername)
            if (!token.isNullOrBlank()) {
                val displayName = myDisplayName.ifBlank { myUsername }
                sendPushNotification(
                    targetToken = token,
                    type = "FRIEND_ONLINE",
                    title = "🟢 $displayName is online",
                    body = "Your friend is online. Tap to challenge them to a match!",
                    data = mapOf(
                        "username" to myUsername,
                        "displayName" to displayName,
                        "timestamp" to System.currentTimeMillis().toString(),
                        "type" to "FRIEND_ONLINE"
                    )
                )
            } else {
                Log.d(TAG, "No FCM token for @$targetUsername; presence delivered via MQTT")
            }
        }
    }

    /**
     * Self-test trigger: Sends an FCM push to this exact device with a delay
     * so user can minimize/lock their phone and watch FCM wake the device.
     */
    fun sendSelfTestPush(context: Context, onResult: (Boolean, String) -> Unit) {
        val token = getSavedToken(context)
        if (token.isNullOrBlank()) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful && !task.result.isNullOrBlank()) {
                    val freshToken = task.result
                    saveToken(context, freshToken)
                    dispatchSelfTest(freshToken, onResult)
                } else {
                    onResult(false, "Could not fetch FCM device token: ${task.exception?.message}")
                }
            }
        } else {
            dispatchSelfTest(token, onResult)
        }
    }

    private fun dispatchSelfTest(token: String, onResult: (Boolean, String) -> Unit) {
        sendPushNotification(
            targetToken = token,
            type = "GAME_INVITE",
            title = "🎮 Test FCM Push",
            body = "FCM High-Priority push successfully woke your device!",
            data = mapOf(
                "roomCode" to "FCMTEST",
                "fromUsername" to "fcm_test",
                "fromDisplayName" to "FCM Cloud Diagnostics",
                "timestamp" to System.currentTimeMillis().toString()
            ),
            onComplete = onResult
        )
    }
}
