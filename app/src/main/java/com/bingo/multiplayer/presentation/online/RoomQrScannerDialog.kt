package com.bingo.multiplayer.presentation.online

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.bingo.multiplayer.core.designsystem.BingoTheme
import com.bingo.multiplayer.domain.network.QrCodeHelper
import com.bingo.multiplayer.presentation.common.PermissionHelper
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * CameraX QR Scanner dialog that scans room QR codes for online matches.
 * Automatically decodes BINGO_ROOM: QR codes, deep links, and plain 6-character codes,
 * then instantly joins the room.
 */
@Composable
fun RoomQrScannerDialog(
    onDismiss: () -> Unit,
    onRoomScanned: (roomCode: String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tokens = BingoTheme.colors
    val haptic = LocalHapticFeedback.current

    var hasCameraPermission by remember {
        mutableStateOf(PermissionHelper.hasCameraPermission(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Scanning laser animation
    val infiniteTransition = rememberInfiniteTransition(label = "scanLaser")
    val laserOffsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 220f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laserOffsetY"
    )

    var cameraControl by remember { mutableStateOf<Camera?>(null) }
    var isFlashOn by remember { mutableStateOf(false) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                cameraControl?.cameraControl?.enableTorch(false)
                ProcessCameraProvider.getInstance(context).get().unbindAll()
            } catch (_: Exception) {}
            cameraExecutor.shutdown()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            color = tokens.surface,
            border = BorderStroke(1.dp, tokens.surfaceBorder),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = tokens.accentBrand,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Scan Room QR Code",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = tokens.cellNeutralText
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = tokens.cellNeutralText.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Point camera at the host's screen to join their Bingo room instantly.",
                    fontSize = 12.sp,
                    color = tokens.cellNeutralText.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Camera Scanner Viewfinder Box
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (hasCameraPermission) {
                        var hasDetected by remember { mutableStateOf(false) }

                        AndroidView(
                            factory = { ctx ->
                                val previewView = PreviewView(ctx)
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }

                                    val imageAnalysis = ImageAnalysis.Builder()
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .build()

                                    val reader = MultiFormatReader()

                                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                        if (hasDetected) {
                                            imageProxy.close()
                                            return@setAnalyzer
                                        }

                                        try {
                                            val buffer: ByteBuffer = imageProxy.planes[0].buffer
                                            val bytes = ByteArray(buffer.remaining())
                                            buffer.get(bytes)
                                            val width = imageProxy.width
                                            val height = imageProxy.height

                                            val source = PlanarYUVLuminanceSource(
                                                bytes, width, height, 0, 0, width, height, false
                                            )
                                            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

                                            val result = reader.decodeWithState(binaryBitmap)
                                            reader.reset()

                                            if (result != null && !result.text.isNullOrBlank()) {
                                                val cleanCode = QrCodeHelper.extractRoomCode(result.text)
                                                if (!cleanCode.isNullOrBlank() && cleanCode.length == 6) {
                                                    hasDetected = true
                                                    ContextCompat.getMainExecutor(ctx).execute {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        onRoomScanned(cleanCode)
                                                    }
                                                }
                                            }
                                        } catch (_: Exception) {
                                        } finally {
                                            imageProxy.close()
                                        }
                                    }

                                    try {
                                        cameraProvider.unbindAll()
                                        val camera = cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            imageAnalysis
                                        )
                                        cameraControl = camera
                                    } catch (_: Exception) {}
                                }, ContextCompat.getMainExecutor(ctx))

                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Viewfinder border corners
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .border(
                                    width = 2.dp,
                                    color = tokens.accentBrand.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                        )

                        // Animated Laser Line
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .offset(y = laserOffsetY.dp)
                                .background(tokens.accentBrand)
                        )
                    } else {
                        // Permission Request Placeholder
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Camera permission needed to scan room QR codes.",
                                color = Color.White,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = tokens.primaryButtonBg,
                                    contentColor = tokens.primaryButtonText
                                )
                            ) {
                                Text("Allow Camera", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tokens.primaryButtonText)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Flashlight toggle & Cancel
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasCameraPermission && cameraControl != null) {
                        IconButton(
                            onClick = {
                                val nextState = !isFlashOn
                                isFlashOn = nextState
                                cameraControl?.cameraControl?.enableTorch(nextState)
                            }
                        ) {
                            Icon(
                                imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = "Toggle Torch",
                                tint = if (isFlashOn) Color(0xFFFACC15) else tokens.cellNeutralText.copy(alpha = 0.6f)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(48.dp))
                    }

                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Cancel",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.cellNeutralText.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}
