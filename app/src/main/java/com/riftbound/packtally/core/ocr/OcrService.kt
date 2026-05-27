package com.riftbound.packtally.core.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OcrService {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * If the first OCR pass returns no block with confidence ≥ this threshold,
     * [recognize] retries on `preprocess(bitmap, applyOtsu = true)` — the
     * heavier binarization path that often saves foil/signature reads.
     */
    const val LOW_CONFIDENCE_THRESHOLD: Float = 0.5f

    /**
     * Recognize text in [bitmap], sorted top-to-bottom by bounding box `top`.
     *
     * When [alwaysPreprocess] is true (e.g. Settings → "Force OCR preprocessing")
     * the first pass runs on `preprocess(bitmap, applyOtsu = false)` instead of
     * the raw bitmap. Regardless of that setting, if no block in the first pass
     * reaches [LOW_CONFIDENCE_THRESHOLD] we make a second attempt against
     * `preprocess(bitmap, applyOtsu = true)`. So:
     *   - clean read → 1 recognizer call
     *   - low-confidence read → 2 recognizer calls (raw + binarized)
     *   - force-preprocess + clean → 1 recognizer call on the gray/contrast image
     *   - force-preprocess + low confidence → 2 calls, the retry uses Otsu
     */
    suspend fun recognize(
        bitmap: Bitmap,
        alwaysPreprocess: Boolean = false,
    ): List<TextBlock> {
        val firstInput = if (alwaysPreprocess) {
            withContext(Dispatchers.Default) { preprocess(bitmap, applyOtsu = false) }
        } else {
            bitmap
        }
        val first = try {
            doRecognize(firstInput)
        } finally {
            if (firstInput !== bitmap && !firstInput.isRecycled) firstInput.recycle()
        }

        val maxConfidence = first.maxOfOrNull { it.confidence } ?: 0f
        if (maxConfidence < LOW_CONFIDENCE_THRESHOLD) {
            val preprocessed = withContext(Dispatchers.Default) {
                preprocess(bitmap, applyOtsu = true)
            }
            return try {
                doRecognize(preprocessed)
            } finally {
                if (!preprocessed.isRecycled) preprocessed.recycle()
            }
        }
        return first
    }

    /**
     * Grayscale + ~1.5× contrast, optionally followed by Otsu binarization.
     * Returns a new `ARGB_8888` bitmap with the same dimensions as [bitmap].
     *
     * NOTE: synchronous + CPU-bound. Call from a non-Main coroutine context;
     * [recognize] handles that internally with `withContext(Dispatchers.Default)`.
     */
    fun preprocess(bitmap: Bitmap, applyOtsu: Boolean = false): Bitmap {
        val grayContrast = applyGrayscaleAndContrast(bitmap)
        return if (applyOtsu) {
            val otsu = applyOtsuThresholding(grayContrast)
            if (otsu !== grayContrast && !grayContrast.isRecycled) grayContrast.recycle()
            otsu
        } else {
            grayContrast
        }
    }

    private suspend fun doRecognize(bitmap: Bitmap): List<TextBlock> {
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

    private fun applyGrayscaleAndContrast(bitmap: Bitmap): Bitmap {
        val contrast = 1.5f
        // Anchor contrast around mid-gray (128) so neither blacks nor whites clip aggressively.
        val translate = (1f - contrast) * 128f
        val grayscale = ColorMatrix().apply { setSaturation(0f) }
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val combined = ColorMatrix(grayscale).apply { postConcat(contrastMatrix) }
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(combined)
        }
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(bitmap, 0f, 0f, paint)
        return output
    }

    /** Pure-Kotlin Otsu binarization on an already-grayscale ARGB_8888 bitmap. */
    private fun applyOtsuThresholding(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val histogram = IntArray(256)
        for (pixel in pixels) {
            // After grayscale, R == G == B; pick R.
            val luminance = (pixel shr 16) and 0xFF
            histogram[luminance]++
        }

        val threshold = computeOtsuThreshold(histogram, pixels.size)

        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        for (i in pixels.indices) {
            val luminance = (pixels[i] shr 16) and 0xFF
            // Standard Otsu convention: threshold is the high end of background,
            // so background = (lum <= threshold), foreground = (lum > threshold).
            // The `>` matters at boundary cases like threshold=0 (a 90/10 split).
            pixels[i] = if (luminance > threshold) white else black
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /**
     * Otsu's method: pick the intensity that maximizes the between-class
     * variance σ²_b = w_b · w_f · (μ_b − μ_f)². Linear in the histogram bins.
     */
    private fun computeOtsuThreshold(histogram: IntArray, totalPixels: Int): Int {
        var sum = 0.0
        for (i in 0..255) sum += i.toDouble() * histogram[i]

        var sumB = 0.0
        var weightB = 0
        var maxVariance = 0.0
        var optimalThreshold = 0

        for (t in 0..255) {
            weightB += histogram[t]
            if (weightB == 0) continue
            val weightF = totalPixels - weightB
            if (weightF == 0) break

            sumB += t.toDouble() * histogram[t]
            val meanB = sumB / weightB
            val meanF = (sum - sumB) / weightF
            val diff = meanB - meanF

            val variance = weightB.toDouble() * weightF.toDouble() * diff * diff
            if (variance > maxVariance) {
                maxVariance = variance
                optimalThreshold = t
            }
        }
        return optimalThreshold
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
