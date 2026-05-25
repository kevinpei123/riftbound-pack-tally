package com.riftbound.packtally.feature.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executor

private const val TAG = "CameraScreen"

/**
 * Normalized capture region, as fractions of the camera preview area.
 * Sized and positioned for the bottom-left corner of a portrait TCG card
 * held at arm's length filling roughly the screen width.
 */
val DEFAULT_GUIDE_RECT: Rect = Rect(left = 0.06f, top = 0.62f, right = 0.42f, bottom = 0.78f)

@Composable
fun CameraScreen(
    onCardCaptured: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
    guideRect: Rect = DEFAULT_GUIDE_RECT,
    onError: (Throwable) -> Unit = { Log.e(TAG, "Capture failed", it) },
) {
    PermissionGate(modifier = modifier) {
        CameraSurface(
            onCardCaptured = onCardCaptured,
            onError = onError,
            guideRect = guideRect,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PermissionGate(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var deniedAtLeastOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        if (!granted) deniedAtLeastOnce = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    when {
        hasPermission -> content()
        deniedAtLeastOnce -> PermissionDeniedView(
            modifier = modifier.fillMaxSize(),
            onRetry = { launcher.launch(Manifest.permission.CAMERA) },
        )
        else -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun PermissionDeniedView(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Camera permission is required to scan cards.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRetry) { Text("Grant permission") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                context.startActivity(intent)
            }) { Text("Open app settings") }
        }
    }
}

@Composable
private fun CameraSurface(
    onCardCaptured: (Bitmap) -> Unit,
    onError: (Throwable) -> Unit,
    guideRect: Rect,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { ContextCompat.getMainExecutor(context) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var isCapturing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val provider = ProcessCameraProvider.awaitInstance(context)
            cameraProvider = provider

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(Surface.ROTATION_0)
                .build()
                .also { it.surfaceProvider = previewView.surfaceProvider }

            val capture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(Surface.ROTATION_0)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
            )
            imageCapture = capture
        } catch (e: Exception) {
            Log.e(TAG, "Camera setup failed", e)
            onError(e)
        }
    }

    DisposableEffect(Unit) {
        onDispose { cameraProvider?.unbindAll() }
    }

    Box(modifier = modifier.background(Color.Black)) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .aspectRatio(9f / 16f),
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
            GuideOverlay(
                guideRect = guideRect,
                modifier = Modifier.fillMaxSize(),
            )
        }

        FloatingActionButton(
            onClick = {
                val capture = imageCapture ?: return@FloatingActionButton
                if (isCapturing) return@FloatingActionButton
                isCapturing = true
                takeAndCrop(
                    imageCapture = capture,
                    executor = executor,
                    guideRect = guideRect,
                    onCaptured = { bmp ->
                        isCapturing = false
                        onCardCaptured(bmp)
                    },
                    onError = { exc ->
                        isCapturing = false
                        onError(exc)
                    },
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            containerColor = if (isCapturing || imageCapture == null) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
        ) {
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = "Capture card",
            )
        }
    }
}

@Composable
private fun GuideOverlay(
    guideRect: Rect,
    modifier: Modifier = Modifier,
) {
    val dim = Color.Black.copy(alpha = 0.55f)
    val accent = Color.White
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val l = w * guideRect.left
        val t = h * guideRect.top
        val r = w * guideRect.right
        val b = h * guideRect.bottom

        // Four dim strips around the guide rect
        drawRect(dim, topLeft = Offset(0f, 0f), size = Size(w, t))
        drawRect(dim, topLeft = Offset(0f, t), size = Size(l, b - t))
        drawRect(dim, topLeft = Offset(r, t), size = Size(w - r, b - t))
        drawRect(dim, topLeft = Offset(0f, b), size = Size(w, h - b))

        // Corner brackets — easier to align against than a full border
        val bracket = (b - t) * 0.22f
        val stroke = 4.dp.toPx()
        // top-left
        drawLine(accent, Offset(l, t), Offset(l + bracket, t), stroke)
        drawLine(accent, Offset(l, t), Offset(l, t + bracket), stroke)
        // top-right
        drawLine(accent, Offset(r, t), Offset(r - bracket, t), stroke)
        drawLine(accent, Offset(r, t), Offset(r, t + bracket), stroke)
        // bottom-left
        drawLine(accent, Offset(l, b), Offset(l + bracket, b), stroke)
        drawLine(accent, Offset(l, b), Offset(l, b - bracket), stroke)
        // bottom-right
        drawLine(accent, Offset(r, b), Offset(r - bracket, b), stroke)
        drawLine(accent, Offset(r, b), Offset(r, b - bracket), stroke)
    }
}

private fun takeAndCrop(
    imageCapture: ImageCapture,
    executor: Executor,
    guideRect: Rect,
    onCaptured: (Bitmap) -> Unit,
    onError: (Throwable) -> Unit,
) {
    imageCapture.takePicture(
        executor,
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rotated = image.toRotatedBitmap()
                    val cropped = rotated.cropToNormalizedRect(guideRect)
                    if (cropped !== rotated) rotated.recycle()
                    onCaptured(cropped)
                } catch (e: Throwable) {
                    onError(e)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        },
    )
}

private fun ImageProxy.toRotatedBitmap(): Bitmap {
    val raw = toBitmap()
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return raw
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    if (rotated !== raw) raw.recycle()
    return rotated
}

private fun Bitmap.cropToNormalizedRect(rect: Rect): Bitmap {
    val l = (rect.left * width).toInt().coerceIn(0, width - 1)
    val t = (rect.top * height).toInt().coerceIn(0, height - 1)
    val r = (rect.right * width).toInt().coerceIn(l + 1, width)
    val b = (rect.bottom * height).toInt().coerceIn(t + 1, height)
    return Bitmap.createBitmap(this, l, t, r - l, b - t)
}
