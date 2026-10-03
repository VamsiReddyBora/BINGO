package com.bingo.multiplayer.domain.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

object FeedbackManager {
    private const val TAG = "FeedbackManager"
    private const val DEV_EMAIL = "vamsireddy2534@gmail.com"
    const val DEV_WHATSAPP_PHONE = "918688869780"

    private val httpClient = NetworkConfig.httpClient
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Opens direct WhatsApp chat with the developer (+918688869780) with
     * user message and device diagnostic details pre-filled.
     */
    fun openDirectWhatsAppChat(
        context: Context,
        username: String,
        displayName: String,
        message: String
    ): Boolean {
        val cleanMsg = message.trim()
        val cleanUser = username.trim().lowercase().removePrefix("@").ifBlank { "anonymous" }
        val cleanName = displayName.trim().ifBlank { cleanUser }
        val formattedText = buildString {
            append("*Bingo Multiplayer Feedback*\n\n")
            append("*From*: $cleanName (@$cleanUser)\n\n")
            append("*Message*:\n$cleanMsg")
        }

        val encoded = URLEncoder.encode(formattedText, "UTF-8")

        // 1. First attempt: Direct WhatsApp application intent
        val waUri = Uri.parse("https://api.whatsapp.com/send?phone=$DEV_WHATSAPP_PHONE&text=$encoded")
        val waIntent = Intent(Intent.ACTION_VIEW, waUri).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        return try {
            context.startActivity(waIntent)
            true
        } catch (_: Exception) {
            // 2. Fallback: wa.me browser/app link
            try {
                val fallbackUri = Uri.parse("https://wa.me/$DEV_WHATSAPP_PHONE?text=$encoded")
                val fallbackIntent = Intent(Intent.ACTION_VIEW, fallbackUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch WhatsApp: ${e.message}")
                false
            }
        }
    }

    /**
     * Transmits user feedback/suggestions in the background directly to:
     * 1. Developer Email (via FormSubmit AJAX to vamsireddy2534@gmail.com)
     * 2. Cloud Key-Value backup storage
     */
    suspend fun transmitFeedback(
        context: Context,
        username: String,
        displayName: String,
        message: String
    ): Boolean = withContext(Dispatchers.IO) {
        val cleanMsg = message.trim()
        if (cleanMsg.isBlank()) return@withContext false
        val cleanUser = username.trim().lowercase().removePrefix("@").ifBlank { "anonymous" }
        val cleanName = displayName.trim().ifBlank { cleanUser }
        var emailDelivered = false
        var cloudBackedUp = false

        // ── 1. Send directly to Developer Gmail via FormSubmit ──
        try {
            val jsonPayload = JSONObject().apply {
                put("name", "$cleanName (@$cleanUser)")
                put("email", DEV_EMAIL)
                put("_subject", "💌 Bingo App Feedback from @$cleanUser")
                put("message", cleanMsg)
                put("timestamp", System.currentTimeMillis().toString())
            }

            val request = Request.Builder()
                .url("https://formsubmit.co/ajax/$DEV_EMAIL")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(jsonPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                emailDelivered = response.isSuccessful
                Log.i(TAG, "FormSubmit email dispatch status: ${response.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "FormSubmit direct delivery warning: ${e.message}")
        }

        // ── 2. Backup to Cloud Key-Value storage ──
        try {
            val ts = System.currentTimeMillis()
            val backupData = JSONObject().apply {
                put("user", cleanUser)
                put("name", cleanName)
                put("msg", cleanMsg)
                put("ts", ts)
            }.toString()

            val encVal = URLEncoder.encode(backupData, "UTF-8")
            val backupKey = URLEncoder.encode("dev_fb_${cleanUser}_$ts", "UTF-8")
            val backupUrl = "${NetworkConfig.KEYVALUE_API_URL}/UpdateValue/${NetworkConfig.KEYVALUE_APP_KEY}/$backupKey?value=$encVal"

            val backupRequest = Request.Builder()
                .url(backupUrl)
                .post("".toRequestBody(null))
                .build()

            httpClient.newCall(backupRequest).execute().use { response ->
                cloudBackedUp = response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cloud storage feedback backup warning: ${e.message}")
        }

        // Return true if either email or cloud storage succeeded
        emailDelivered || cloudBackedUp
    }
}
