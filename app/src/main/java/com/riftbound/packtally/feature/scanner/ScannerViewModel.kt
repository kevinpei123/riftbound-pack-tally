package com.riftbound.packtally.feature.scanner

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.riftbound.packtally.App
import com.riftbound.packtally.BuildConfig
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.core.ocr.CardOcrParser
import com.riftbound.packtally.core.ocr.OcrService
import com.riftbound.packtally.core.settings.SettingsRepository
import com.riftbound.packtally.feature.pack.PackViewModel
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.ScannedEntry
import com.riftbound.packtally.model.Variant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant

private const val TAG = "ScannerViewModel"

private const val CONFIDENCE_KNOWN_SET = 0.9f
private const val CONFIDENCE_UNKNOWN_SET = 0.6f
private const val CONFIDENCE_NAME_FALLBACK = 0.5f

private const val OCR_TIMEOUT_MS = 10_000L

private val KNOWN_SETS = setOf("OGN", "OGS", "ARC", "SFD", "UNL", "FND")

sealed interface ScanResult {
    data object Idle : ScanResult
    data object Scanning : ScanResult
    data class Identified(val card: RiftboundCard, val confidence: Float) : ScanResult
    data class Ambiguous(val candidates: List<RiftboundCard>) : ScanResult
    data class Failed(val reason: String) : ScanResult
}

class ScannerViewModel(
    private val pack: PackViewModel,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _scanResult = MutableStateFlow<ScanResult>(ScanResult.Idle)
    val scanResult: StateFlow<ScanResult> = _scanResult.asStateFlow()

    fun onCardCaptured(bitmap: Bitmap) {
        if (_scanResult.value is ScanResult.Scanning) return
        _scanResult.value = ScanResult.Scanning
        viewModelScope.launch {
            _scanResult.value = try {
                identify(bitmap)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    /**
     * Append the identified card to the active pack with no price attached.
     * The PackViewModel's Submit action prices the whole pack in one batched
     * JustTCG call when the user is done filling it. Per-scan pricing would
     * burn the free-tier quota (~1000 requests/month) very fast.
     */
    fun recordCard(card: RiftboundCard, variant: Variant) {
        val confidence = (_scanResult.value as? ScanResult.Identified)?.confidence
            ?: CONFIDENCE_NAME_FALLBACK
        pack.append(
            ScannedEntry(
                card = card,
                variant = variant,
                price = null,
                confidence = confidence,
                scannedAt = Instant.now(),
            ),
        )
        reset()
    }

    fun pickCandidate(card: RiftboundCard) {
        _scanResult.value = ScanResult.Identified(card, CONFIDENCE_NAME_FALLBACK)
    }

    fun reset() {
        _scanResult.value = ScanResult.Idle
    }

    private suspend fun identify(bitmap: Bitmap): ScanResult {
        return try {
            val currentSettings = settings.getCurrentSettings()
            val alwaysPreprocess = currentSettings.forceOcrPreprocessing
            // OCR call wrapped in withTimeout(10s) so a stuck ML Kit call
            // can't hang the scan flow indefinitely.
            val blocks = withTimeout(OCR_TIMEOUT_MS) {
                OcrService.recognize(bitmap, alwaysPreprocess = alwaysPreprocess)
            }
            val parsed = CardOcrParser.parse(blocks)
            val debugLogging = BuildConfig.DEBUG && currentSettings.ocrDebugLogging
            if (debugLogging) {
                Log.d(
                    TAG,
                    "OCR raw='${blocks.joinToString(" | ") { it.text }}' parsed=$parsed",
                )
            }

            parsed.collectorNumber?.let { number ->
                CardDatabase.lookupByNumber(number)?.let { card ->
                    val confidence = if (parsed.setCode in KNOWN_SETS) {
                        CONFIDENCE_KNOWN_SET
                    } else {
                        CONFIDENCE_UNKNOWN_SET
                    }
                    if (debugLogging) Log.d(TAG, "OCR lookup=collector confidence=$confidence card=${card.id}")
                    return ScanResult.Identified(card, confidence)
                }
            }

            parsed.name?.let { name ->
                val candidates = CardDatabase.lookupByNameFuzzy(name, limit = 3)
                if (debugLogging) Log.d(TAG, "OCR lookup=name candidates=${candidates.map { it.id }}")
                return when (candidates.size) {
                    0 -> ScanResult.Failed("No match for '$name'")
                    1 -> ScanResult.Identified(candidates.first(), CONFIDENCE_NAME_FALLBACK)
                    else -> ScanResult.Ambiguous(candidates)
                }
            }

            ScanResult.Failed("No readable text on card")
        } catch (e: Throwable) {
            Log.e(TAG, "Identify failed", e)
            ScanResult.Failed(e.message ?: "Recognition error")
        }
    }

    companion object {
        fun factory(app: App, pack: PackViewModel): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ScannerViewModel(
                    pack = pack,
                    settings = app.settingsRepository,
                )
            }
        }
    }
}
