package com.bingo.multiplayer.presentation.nearby

import android.Manifest
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.bingo.multiplayer.domain.network.NearbyHostQrPayload
import com.bingo.multiplayer.domain.network.QrCodeHelper
import com.bingo.multiplayer.presentation.common.PermissionHelper
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.concurrent.Executors

@Composable
fun NearbyQrScannerDialog(
    onDismiss: () -> Unit,
    onQrScanned: (NearbyHostQrPayload) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val tokens = BingoTheme.colors

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

    // Scanning line animation
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
                            text = "Scan Host QR Code",
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
                    text = "Point your camera at the QR code displayed on the host's screen to connect automatically.",
                    fontSize = 11.5.sp,
                    color = tokens.cellNeutralText.copy(alpha = 0.6f),
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
                                val cameraExecutor = Executors.newSingleThreadExecutor()

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
                                                val parsed = QrCodeHelper.parseQrContent(result.text)
                                                if (parsed != null) {
                                                    hasDetected = true
                                                    ContextCompat.getMainExecutor(ctx).execute {
                                                        onQrScanned(parsed)
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
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            CameraSelector.DEFAULT_BACK_CAMERA,
                                            preview,
                                            imageAnalysis
                                        )
                                    } catch (e: Exception) {
                                        Log.e("QrScanner", "Use case binding failed", e)
                                    }
                                }, ContextCompat.getMainExecutor(ctx))

                                previewView
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Target Frame Corners Overlay
                        Box(
                            modifier = Modifier
                                .size(210.dp)
                                .border(2.dp, tokens.accentBrand.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                        )

                        // Animated scanning laser line
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .offset(y = (laserOffsetY - 110).dp)
                                .background(Color(0xFF38BDF8))
                        )
                    } else {
                        // Permission Request View
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "Camera Permission Required",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Grant camera permission to scan host QR codes.",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = tokens.primaryButtonBg,
                                    contentColor = tokens.primaryButtonText
                                )
                            ) {
                                Text("Grant Permission", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tokens.primaryButtonText)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, tokens.surfaceBorder)
                ) {
                    Text("Cancel", color = tokens.cellNeutralText, fontSize = 12.5.sp)
                }
            }
        }
    }
}
