package com.riftbound.packtally.core.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OcrService {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Recognize text in [bitmap] and return blocks sorted top-to-bottom by their
     * bounding-box `top` coordinate. Bounding boxes are in [bitmap]'s pixel space.
     *
     * The bundled ML Kit recognizer does not expose per-block confidence, so each
     * block gets a proxy: `min(0.95, text.length * 0.05)`. Treat it as a length-
     * based prior, not a real probability.
     */
    suspend fun recognize(bitmap: Bitmap): List<TextBlock> {
        val input = InputImage.fromBitmap(bitmap, 0)
        val result: Text = suspendCancellableCoroutine { cont ->
            recognizer.process(input)
                .addOnSuccessListener { text -> cont.resume(text) }
                .addOnFailureListener { exc -> cont.resumeWithException(exc) }
        }
        return result.textBlocks
            .mapNotNull { mlBlock ->
                val box = mlBlock.boundingBox ?: return@mapNotNull null
                TextBlock(
                    text = mlBlock.text,
                    bounds = BoundingBox(box.left, box.top, box.right, box.bottom),
                    confidence = (mlBlock.text.length * 0.05f).coerceAtMost(0.95f),
                )
            }
            .sortedBy { it.bounds.top }
    }
}

data class TextBlock(
    val text: String,
    val bounds: BoundingBox,
    val confidence: Float,
)

data class BoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
