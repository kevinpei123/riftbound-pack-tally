package com.riftbound.packtally.feature.scanner

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.riftbound.packtally.App
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.core.ocr.CardOcrParser
import com.riftbound.packtally.core.ocr.OcrService
import com.riftbound.packtally.core.pricing.PricingRepository
import com.riftbound.packtally.feature.pack.PackViewModel
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.ScannedEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

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
    data class Pricing(val card: RiftboundCard, val variant: Variant) : ScanResult
    data class PricingFailed(
        val card: RiftboundCard,
        val variant: Variant,
        val reason: String,
    ) : ScanResult
}

enum class Variant { STANDARD, FOIL, SIGNATURE }

class ScannerViewModel(
    private val pricing: PricingRepository,
    private val pack: PackViewModel,
) : ViewModel() {

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
        val confidence = (_scanResult.value as? ScanResult.Identified)?.confidence
            ?: CONFIDENCE_NAME_FALLBACK
        _scanResult.value = ScanResult.Pricing(card, variant)
        viewModelScope.launch {
            pricing.price(
                card = card,
                foil = variant == Variant.FOIL,
                signature = variant == Variant.SIGNATURE,
            )
                .onSuccess { price ->
                    pack.append(
                        ScannedEntry(
                            card = card,
                            variant = variant,
                            price = price,
                            confidence = confidence,
                            scannedAt = Instant.now(),
                        ),
                    )
                    reset()
                }
                .onFailure { exc ->
                    Log.e(TAG, "Pricing failed for ${card.id} as $variant", exc)
                    _scanResult.value = ScanResult.PricingFailed(
                        card = card,
                        variant = variant,
                        reason = exc.message ?: "Pricing failed",
                    )
                }
        }
    }

    fun retryPricing() {
        val current = _scanResult.value
        if (current is ScanResult.PricingFailed) {
            recordCard(current.card, current.variant)
        }
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

    companion object {
        fun factory(app: App, pack: PackViewModel): ViewModelProvider.Factory = viewModelFactory {
            initializer { ScannerViewModel(pricing = app.pricing, pack = pack) }
        }
    }
}
