package com.riftbound.packtally.feature.scanner

import android.util.Log
import androidx.compose.runtime.Composable

@Composable
fun ScannerScreen() {
    CameraScreen(
        onCardCaptured = { bitmap ->
            Log.d("ScannerScreen", "Captured ${bitmap.width}x${bitmap.height} — TODO: hand off to OCR")
        },
    )
}
