package com.bingo.multiplayer.presentation.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val avatarMemoryCache = object : androidx.collection.LruCache<String, ImageBitmap>(128) {}

/**
 * Checks whether a given string is a local filesystem path on an Android device.
 * CRITICAL: Base64-encoded JPEG images start with `/9j/` (due to JPEG magic bytes 0xFF 0xD8 0xFF).
 * Therefore, we must never assume that a string starting with `/` is a local file path!
 */
fun isLocalFilePath(path: String?): Boolean {
    if (path.isNullOrBlank()) return false
    // Base64 strings for images are typically thousands of characters (> 500 chars)
    if (path.length > 500) return false
    if (path.startsWith("data:image")) return false
    if (path.startsWith("/9j/") || path.startsWith("iVBORw") || path.startsWith("R0lGOD")) return false
    return path.startsWith("/") && (
        path.contains("/files/") ||
        path.contains("/data/") ||
        path.contains("/storage/") ||
        path.contains("/sdcard/") ||
        path.endsWith(".jpg") ||
        path.endsWith(".jpeg") ||
        path.endsWith(".png") ||
        path.endsWith(".webp") ||
        try { File(path).exists() } catch (_: Exception) { false }
    )
}

/**
 * Safely decodes an avatar source string (HTTP URL, content URI, local file path, or Base64 string).
 * If the source is a local path from a foreign device that does not exist here, returns null
 * so callers can fall back to username-based cloud lookup.
 */
fun decodeAvatarBitmap(source: String?, context: Context): Bitmap? {
    if (source.isNullOrBlank()) return null
    return try {
        when {
            source.startsWith("http://") || source.startsWith("https://") -> {
                val req = okhttp3.Request.Builder().url(source).build()
                com.bingo.multiplayer.domain.network.NetworkConfig.httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        resp.body?.byteStream()?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    } else null
                }
            }
            source.startsWith("content://") || source.startsWith("file://") -> {
                val uri = Uri.parse(source)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }
            isLocalFilePath(source) -> {
                val file = File(source)
                if (file.exists() && file.canRead()) {
                    BitmapFactory.decodeFile(source)
                } else {
                    null // Remote local path; fall back to username resolution
                }
            }
            else -> {
                // Base64 encoded JPEG / PNG string (handles /9j/..., iVBORw..., data:image..., etc.)
                val cleanBase64 = if (source.contains(",")) {
                    source.substringAfter(",")
                } else {
                    source
                }.trim().replace("\n", "").replace("\r", "")
                val bytes = android.util.Base64.decode(cleanBase64, android.util.Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Clean, robust player avatar component.
 * Displays custom profile photo (local path, URI, HTTP/HTTPS URL, or Base64 string from opponents)
 * if present. If missing or only username is known, automatically resolves and renders
 * the photo in the background via AccountSessionManager and caches it.
 */
@Composable
fun PlayerAvatar(
    avatarPathOrUri: String?,
    displayName: String,
    size: Dp,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    borderWidth: Dp = 1.dp,
    username: String? = null
) {
    val context = LocalContext.current
    val tokens = BingoTheme.colors

    val cleanUser = remember(username) {
        username?.trim()?.lowercase()?.removePrefix("@")?.takeIf { it.isNotBlank() }
    }

    var bitmap by remember(avatarPathOrUri, cleanUser) {
        mutableStateOf(
            avatarPathOrUri?.takeIf { it.isNotBlank() }?.let { avatarMemoryCache.get(it) }
                ?: cleanUser?.let { avatarMemoryCache.get("u:$it") }
        )
    }

    LaunchedEffect(avatarPathOrUri, cleanUser) {
        val cached = (avatarPathOrUri?.takeIf { it.isNotBlank() }?.let { avatarMemoryCache.get(it) })
            ?: (cleanUser?.let { avatarMemoryCache.get("u:$it") })

        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }

        withContext(Dispatchers.IO) {
            var resolvedBitmap: ImageBitmap? = null
            val source = avatarPathOrUri?.takeIf { it.isNotBlank() }

            if (source != null) {
                val bmp = decodeAvatarBitmap(source, context)
                if (bmp != null) {
                    resolvedBitmap = bmp.asImageBitmap()
                    avatarMemoryCache.put(source, resolvedBitmap)
                    if (cleanUser != null) {
                        avatarMemoryCache.put("u:$cleanUser", resolvedBitmap)
                    }
                }
            }

            // If bitmap is still null and username is provided, automatically resolve via cloud in background
            if (resolvedBitmap == null && cleanUser != null) {
                try {
                    val entry = com.bingo.multiplayer.domain.network.AccountSessionManager.searchPlayerByUsername(cleanUser)
                    val remoteAvatar = entry?.avatarUrl?.takeIf { it.isNotBlank() }
                    if (remoteAvatar != null) {
                        val bmp = decodeAvatarBitmap(remoteAvatar, context)
                        if (bmp != null) {
                            resolvedBitmap = bmp.asImageBitmap()
                            avatarMemoryCache.put("u:$cleanUser", resolvedBitmap)
                            avatarMemoryCache.put(remoteAvatar, resolvedBitmap)
                        }
                    }
                } catch (_: Exception) {}
            }

            if (resolvedBitmap != null) {
                bitmap = resolvedBitmap
            }
        }
    }

    val finalBorderColor = borderColor ?: tokens.surfaceBorder

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (tokens.isDark) Color(0xFF222222) else tokens.cellPlayerPickBg)
            .border(borderWidth, finalBorderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = "Profile Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val initial = displayName.trim().take(1).uppercase().ifEmpty { "P" }
            Text(
                text = initial,
                fontSize = (size.value * 0.44f).sp,
                fontWeight = FontWeight.Bold,
                color = if (tokens.isDark) Color.White else tokens.accentBrand
            )
        }
    }
}
