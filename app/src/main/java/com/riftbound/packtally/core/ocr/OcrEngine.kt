package com.riftbound.packtally.core.ocr

interface OcrEngine {
    suspend fun recognizeCollectorNumber(imageBytes: ByteArray): String?
}
