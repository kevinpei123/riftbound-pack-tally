package com.riftbound.packtally.feature.scanner

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.core.ocr.CardOcrParser
import com.riftbound.packtally.core.ocr.OcrService
import com.riftbound.packtally.model.RiftboundCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "ScannerViewModel"

private const val CONFIDENCE_KNOWN_SET = 0.9f
private const val CONFIDENCE_UNKNOWN_SET = 0.6f
private const val CONFIDENCE_NAME_FALLBACK = 0.5f

private val KNOWN_SETS = setOf("OGN", "UNL", "SFD", "OGS")

sealed interface ScanResult {
    data object Idle : ScanResult
    data object Scanning : ScanResult
    data class Identified(val card: RiftboundCard, val confidence: Float) : ScanResult
    data class Ambiguous(val candidates: List<RiftboundCard>) : ScanResult
    data class Failed(val reason: String) : ScanResult
}

enum class Variant { STANDARD, FOIL, SIGNATURE }

class ScannerViewModel : ViewModel() {

    private val _scanResult = MutableStateFlow<ScanResult>(ScanResult.Idle)
    val scanResult: StateFlow<ScanResult> = _scanResult.asStateFlow()

    fun onCardCaptured(bitmap: Bitmap) {
        if (_scanResult.value is ScanResult.Scanning) return
        _scanResult.value = ScanResult.Scanning
        viewModelScope.launch {
            _scanResult.value = identify(bitmap)
        }
    }

    fun recordCard(card: RiftboundCard, variant: Variant) {
        Log.d(TAG, "Record ${card.id} (${card.name}) as $variant")
        // TODO: persist to collection via core/persistence once wired.
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
            val blocks = OcrService.recognize(bitmap)
            val parsed = CardOcrParser.parse(blocks)

            parsed.collectorNumber?.let { number ->
                CardDatabase.lookupByNumber(number)?.let { card ->
                    val confidence = if (parsed.setCode in KNOWN_SETS) {
                        CONFIDENCE_KNOWN_SET
                    } else {
                        CONFIDENCE_UNKNOWN_SET
                    }
                    return ScanResult.Identified(card, confidence)
                }
            }

            parsed.name?.let { name ->
                val candidates = CardDatabase.lookupByNameFuzzy(name, limit = 3)
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
}
