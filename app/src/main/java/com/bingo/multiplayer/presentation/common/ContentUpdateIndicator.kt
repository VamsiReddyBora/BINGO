package com.bingo.multiplayer.presentation.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.content.*
import com.bingo.multiplayer.domain.network.AppUpdateManager
import com.bingo.multiplayer.domain.network.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ContentUpdateIndicator(uid: String?, status: ContentUpdateStatus, settingsLink: Boolean = false) {
    if (uid.isNullOrBlank()) return
    val context = LocalContext.current
    val runtime = remember { ContentUpdateRuntime.get(context) }
    val apk by AppUpdateManager.updateState.collectAsState()
    val own = if (status.uid == uid) status else ContentUpdateStatus(uid)
    var details by remember { mutableStateOf(false) }
    val c = BingoTheme.colors
    val busy = own.stage in setOf(ContentStage.DOWNLOADING, ContentStage.APPLYING)
    val visible = own.stage in setOf(ContentStage.DOWNLOADING, ContentStage.READY, ContentStage.APPLYING, ContentStage.APPLIED) ||
        apk is UpdateState.Downloading || apk is UpdateState.ReadyToInstall
    if (settingsLink) TextButton(onClick = { details = true }) { Text("Content updates", fontSize = 12.sp, color = c.textSecondary) }
    else if (visible) {
        IconButton(onClick = { details = true }, modifier = Modifier.size(30.dp)) {
            when {
                apk is UpdateState.Downloading -> CircularProgressIndicator(progress = { (apk as UpdateState.Downloading).progress.coerceIn(0f, 1f) },
                    modifier = Modifier.size(21.dp), color = c.textPrimary, strokeWidth = 2.dp)
                busy -> Box(contentAlignment = Alignment.Center) {
                    if (own.stage == ContentStage.DOWNLOADING && own.total > 0) CircularProgressIndicator(
                        progress = { (own.downloaded.toFloat() / own.total).coerceIn(0f, 1f) }, modifier = Modifier.size(23.dp), color = c.textPrimary, strokeWidth = 2.dp)
                    else CircularProgressIndicator(Modifier.size(23.dp), color = c.textPrimary, strokeWidth = 2.dp)
                    Icon(Icons.Default.CloudDownload, if (own.stage == ContentStage.APPLYING) "Applying content update" else "Downloading content update",
                        modifier = Modifier.size(12.dp), tint = c.textPrimary)
                }
                own.stage == ContentStage.APPLIED -> Icon(Icons.Default.Check, "Content update applied", tint = c.textPrimary, modifier = Modifier.size(20.dp))
                else -> Icon(Icons.Default.CloudDownload, "Content update status", tint = c.textMuted, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(4.dp))
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, containerColor = c.surface,
        title = { Text("App updates", color = c.textPrimary) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val native = apk
                Text(when (native) {
                    is UpdateState.Downloading -> "APK downloading · ${(native.progress * 100).toInt()}%"
                    is UpdateState.ReadyToInstall -> "APK downloaded. Android installation is still required."
                    else -> when (own.stage) {
                        ContentStage.CHECKING -> "Checking for content updates…"
                        ContentStage.DOWNLOADING -> "Downloading content · ${own.downloaded / 1024}/${own.total / 1024} KiB"
                        ContentStage.READY -> "Content is downloaded and will apply on Home."
                        ContentStage.APPLYING -> "Applying verified content…"
                        ContentStage.APPLIED -> "Content update applied."
                        ContentStage.ERROR, ContentStage.ROLLED_BACK -> own.message
                        else -> if (!own.cache.automatic) "Automatic content updates are paused." else
                            "${if (own.cache.active == null) "Built-in content" else "Content revision ${own.cache.active.pack.revision}"} · ready"
                    }
                }, color = c.textSecondary)
                Text("These updates change supported content and settings. New native features and app-code fixes still use an APK.", fontSize = 12.sp, color = c.textMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Automatic content updates", modifier = Modifier.weight(1f), fontSize = 13.sp, color = c.textPrimary)
                    Switch(checked = own.cache.automatic, onCheckedChange = { runtime.automatic(uid, it) }, enabled = !busy)
                }
                TextButton(enabled = !busy && own.cache.automatic, onClick = { runtime.check(uid, true) }) { Text("Check now") }
                if (own.cache.active != null) TextButton(enabled = !busy, onClick = { runtime.rollback(uid) }) {
                    Icon(Icons.Default.History, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Restore previous content")
                }
            }
        }, confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } })
}

@Composable
fun ContentHomeBackground(uid: String?, status: ContentUpdateStatus, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val runtime = remember { ContentUpdateRuntime.get(context) }
    val file = uid?.let { runtime.backgroundFile(it, status) }
    if (file == null) return
    key(uid, file.path, status.cache.active?.key) {
        val image by produceState<ImageBitmap?>(null, file.path, status.cache.active?.key) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.path, bounds)
                    if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..2048) return@withContext null
                    val options = BitmapFactory.Options().apply { inSampleSize = if (bounds.outWidth > 1280 || bounds.outHeight > 1280) 2 else 1 }
                    BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
                }.getOrNull()
            }
        }
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.alpha(status.cache.active?.pack?.homeStyle?.backgroundOpacity ?: .08f)) }
    }
}
