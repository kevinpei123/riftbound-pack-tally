package com.riftbound.packtally.feature.quickscan

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riftbound.packtally.App
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.core.ocr.CardOcrParser
import com.riftbound.packtally.core.ocr.OcrService
import com.riftbound.packtally.core.persistence.LooseScanRepository
import com.riftbound.packtally.core.pricing.PricingRepository
import com.riftbound.packtally.core.settings.SettingsRepository
import com.riftbound.packtally.feature.scanner.Variant
import com.riftbound.packtally.model.RiftboundCard
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "QuickScanViewModel"
private const val OCR_TIMEOUT_MS = 10_000L
private const val PRICING_TIMEOUT_MS = 30_000L

private val KNOWN_SETS = setOf("OGN", "UNL", "SFD", "OGS")

sealed interface QuickScanState {
    /** Camera live, user about to capture. */
    data object CameraReady : QuickScanState

    /** Bitmap submitted, OCR running. */
    data object Scanning : QuickScanState

    /** OCR + DB lookup yielded a single candidate; show variant picker. */
    data class Identified(val card: RiftboundCard, val confidence: Float) : QuickScanState

    /** Name fuzzy lookup yielded multiple candidates. */
    data class Ambiguous(val candidates: List<RiftboundCard>) : QuickScanState

    /** Variant picked, pricing call in flight. */
    data class Pricing(val card: RiftboundCard, val variant: Variant) : QuickScanState

    /** Loose scan was saved. Sheet shows "Scan Another" / "Done" buttons. */
    data class Saved(val card: RiftboundCard, val variant: Variant, val marketPrice: Double) : QuickScanState

    /** OCR / pricing failure with a user-visible reason. */
    data class Failed(val reason: String) : QuickScanState
}

data class SessionStats(
    val cardsAdded: Int = 0,
    val totalValue: Double = 0.0,
) {
    fun add(price: Double): SessionStats = copy(
        cardsAdded = cardsAdded + 1,
        totalValue = totalValue + price,
    )
}

class QuickScanViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val pricing: PricingRepository = app.pricing
    private val looseScans: LooseScanRepository = app.looseScanRepository
    private val settings: SettingsRepository = app.settingsRepository

    private val _state = MutableStateFlow<QuickScanState>(QuickScanState.CameraReady)
    val state: StateFlow<QuickScanState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    /** Consecutive OCR failures. Resets on any success or explicit reset. */
    private val _ocrFailureCount = MutableStateFlow(0)
    val ocrFailureCount: StateFlow<Int> = _ocrFailureCount.asStateFlow()

    fun onCardCaptured(bitmap: Bitmap) {
        val current = _state.value
        if (current is QuickScanState.Scanning || current is QuickScanState.Pricing) return
        _state.value = QuickScanState.Scanning
        viewModelScope.launch {
            val result = identify(bitmap)
            _state.value = result
            if (result is QuickScanState.Failed) {
                _ocrFailureCount.update { it + 1 }
            } else if (result is QuickScanState.Identified || result is QuickScanState.Ambiguous) {
                _ocrFailureCount.value = 0
            }
        }
    }

    fun pickCandidate(card: RiftboundCard) {
        // Name-fuzzy picks get 0.5 confidence — same fallback we use in
        // ScannerViewModel — so the stored entry knows it didn't come from a
        // high-confidence collector-number read.
        _state.value = QuickScanState.Identified(card, 0.5f)
    }

    /** User picks a variant from the bottom sheet — fetch price, save loose scan. */
    fun confirmVariant(card: RiftboundCard, variant: Variant) {
        _state.value = QuickScanState.Pricing(card, variant)
        viewModelScope.launch {
            val result = runCatching {
                withTimeout(PRICING_TIMEOUT_MS) {
                    pricing.price(card, variant)
                }
            }.getOrElse { exc ->
                Log.e(TAG, "Pricing timed out / threw for ${card.id} $variant", exc)
                if (exc is TimeoutCancellationException) {
                    _state.value = QuickScanState.Failed("Pricing timed out after ${PRICING_TIMEOUT_MS / 1000}s")
                } else {
                    _state.value = QuickScanState.Failed(exc.message ?: "Pricing failed")
                }
                return@launch
            }
            result
                .onSuccess { price ->
                    runCatching { looseScans.saveEntry(card, variant, price) }
                        .onFailure { Log.e(TAG, "Saving loose scan failed", it) }
                    _stats.update { it.add(price.marketPrice) }
                    _state.value = QuickScanState.Saved(card, variant, price.marketPrice)
                }
                .onFailure { exc ->
                    Log.e(TAG, "Pricing returned failure for ${card.id} $variant", exc)
                    _state.value = QuickScanState.Failed(exc.message ?: "Pricing failed")
                }
        }
    }

    /** "Type instead" or "Enter manually" — pre-fills the Identified state with a chosen card. */
    fun chooseManually(card: RiftboundCard) {
        _state.value = QuickScanState.Identified(card, 1.0f)
        _ocrFailureCount.value = 0
    }

    fun scanAnother() {
        _state.value = QuickScanState.CameraReady
    }

    fun reset() {
        _state.value = QuickScanState.CameraReady
        _ocrFailureCount.value = 0
    }

    fun clearSessionStats() {
        _stats.value = SessionStats()
    }

    private suspend fun identify(bitmap: Bitmap): QuickScanState {
        val current = settings.getCurrentSettings()
        val blocks = runCatching {
            withTimeout(OCR_TIMEOUT_MS) {
                OcrService.recognize(bitmap, alwaysPreprocess = current.forceOcrPreprocessing)
            }
        }.getOrElse { exc ->
            Log.e(TAG, "OCR failed", exc)
            return QuickScanState.Failed(
                if (exc is TimeoutCancellationException) "OCR timed out" else "OCR error",
            )
        }

        val parsed = CardOcrParser.parse(blocks)
        parsed.collectorNumber?.let { number ->
            CardDatabase.lookupByNumber(number)?.let { card ->
                val confidence = if (parsed.setCode in KNOWN_SETS) 0.9f else 0.6f
                return QuickScanState.Identified(card, confidence)
            }
        }
        parsed.name?.let { name ->
            val candidates = CardDatabase.lookupByNameFuzzy(name, limit = 3)
            return when (candidates.size) {
                0 -> QuickScanState.Failed("No match for '$name'")
                1 -> QuickScanState.Identified(candidates.first(), 0.5f)
                else -> QuickScanState.Ambiguous(candidates)
            }
        }
        return QuickScanState.Failed("No readable text on card")
    }
}
