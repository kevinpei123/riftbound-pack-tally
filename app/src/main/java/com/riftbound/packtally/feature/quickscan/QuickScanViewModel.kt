package com.riftbound.packtally.feature.quickscan

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riftbound.packtally.App
import com.riftbound.packtally.BuildConfig
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.core.ocr.CardOcrParser
import com.riftbound.packtally.core.ocr.OcrService
import com.riftbound.packtally.core.persistence.LooseScanRepository
import com.riftbound.packtally.core.pricing.PricingRepository
import com.riftbound.packtally.core.settings.SettingsRepository
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.Variant
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "QuickScanViewModel"
private const val OCR_TIMEOUT_MS = 10_000L

private val KNOWN_SETS = setOf("OGN", "OGS", "ARC", "SFD", "UNL", "FND")

sealed interface QuickScanState {
    /** Camera live, user about to capture. */
    data object CameraReady : QuickScanState

    /** Bitmap submitted, OCR running. */
    data object Scanning : QuickScanState

    /** OCR + DB lookup yielded a single candidate; show variant picker. */
    data class Identified(val card: RiftboundCard, val confidence: Float) : QuickScanState

    /** Name fuzzy lookup yielded multiple candidates. */
    data class Ambiguous(val candidates: List<RiftboundCard>) : QuickScanState

    /** Loose scan was persisted (priceless). Sheet shows "Scan Another" / "Done". */
    data class Saved(val card: RiftboundCard, val variant: Variant) : QuickScanState

    /** OCR / save failure with a user-visible reason. */
    data class Failed(val reason: String) : QuickScanState
}

sealed interface QuickScanSubmitState {
    data object Idle : QuickScanSubmitState
    data object InFlight : QuickScanSubmitState
    data class Done(val priced: Int, val failed: Int, val totalValue: Double) : QuickScanSubmitState
    data class Failed(val reason: String) : QuickScanSubmitState
}

sealed interface QuickScanEvent {
    data class SubmitCompleted(val priced: Int, val failed: Int, val totalValue: Double) : QuickScanEvent
    data class SubmitFailed(val reason: String) : QuickScanEvent
}

/**
 * Tally chip data. [cardsAdded] is the count of scans since the user entered
 * QuickScan; [totalValue] aggregates the prices that have been fetched so far
 * (via the batched Submit button); [pendingCount] is how many haven't been
 * priced yet.
 */
data class SessionStats(
    val cardsAdded: Int = 0,
    val totalValue: Double = 0.0,
    val pendingCount: Int = 0,
)

class QuickScanViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val pricing: PricingRepository = app.pricing
    private val looseScans: LooseScanRepository = app.looseScanRepository
    private val settings: SettingsRepository = app.settingsRepository

    private val _state = MutableStateFlow<QuickScanState>(QuickScanState.CameraReady)
    val state: StateFlow<QuickScanState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val _submit = MutableStateFlow<QuickScanSubmitState>(QuickScanSubmitState.Idle)
    val submit: StateFlow<QuickScanSubmitState> = _submit.asStateFlow()

    private val _events = MutableSharedFlow<QuickScanEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<QuickScanEvent> = _events.asSharedFlow()

    /** Consecutive OCR failures. Resets on any success or explicit reset. */
    private val _ocrFailureCount = MutableStateFlow(0)
    val ocrFailureCount: StateFlow<Int> = _ocrFailureCount.asStateFlow()

    /**
     * Rapid-scan mode skips the variant-chooser sheet entirely. When on, an
     * identified card is recorded as STANDARD immediately and the camera goes
     * back to live preview. Useful for sleeving up a pile of bulk where the
     * user knows every card is just standard.
     */
    private val _rapidMode = MutableStateFlow(false)
    val rapidMode: StateFlow<Boolean> = _rapidMode.asStateFlow()

    fun setRapidMode(enabled: Boolean) { _rapidMode.value = enabled }

    /** Name of the most recently added card, surfaced in the tally chip. */
    private val _lastAdded = MutableStateFlow<String?>(null)
    val lastAdded: StateFlow<String?> = _lastAdded.asStateFlow()

    init {
        refreshPending()
    }

    /**
     * Re-seed the pending counter from whatever's already in the loose-scan
     * table — manually-added cards (Collection → "+ Add card") and previous
     * quick-scans that never got submitted both live here. Without this seed
     * the Submit button only appears for cards added in *this* session,
     * making manual adds invisible until the user navigates away and back.
     *
     * Called from init and again on screen re-entry: clearSessionStats() (run
     * from the screen's onDispose) resets cardsAdded to 0, so if the ViewModel
     * survives a dispose/recompose on the nav back-stack we must re-read pending
     * rather than relying solely on the one-time init seed.
     */
    fun refreshPending() {
        viewModelScope.launch {
            val pending = runCatching { looseScans.getPending() }
                .onFailure { Log.w(TAG, "Couldn't read pending loose scans", it) }
                .getOrDefault(emptyList())
            _stats.update { it.copy(pendingCount = pending.size) }
        }
    }

    fun onCardCaptured(bitmap: Bitmap) {
        if (_state.value is QuickScanState.Scanning) return
        _state.value = QuickScanState.Scanning
        viewModelScope.launch {
            val result = try {
                identify(bitmap)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            // Rapid mode: an unambiguous identification skips the sheet and
            // records the card as STANDARD straight away. Persist inline (no
            // nested coroutine, never touching Saved) so the camera only returns
            // to live preview after the save + stats update have completed.
            if (_rapidMode.value && result is QuickScanState.Identified) {
                persistScan(result.card, Variant.STANDARD)
                _ocrFailureCount.value = 0
                _state.value = QuickScanState.CameraReady
                return@launch
            }
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

    /**
     * Persist the scanned card to the loose-scan table with no price attached
     * (the next Submit batches them all). Per-scan pricing is intentionally
     * avoided — the free tier of JustTCG only gives ~1000 requests/month.
     */
    fun confirmVariant(card: RiftboundCard, variant: Variant) {
        viewModelScope.launch {
            persistScan(card, variant)
            _state.value = QuickScanState.Saved(card, variant)
        }
    }

    /**
     * Persist a single scanned card and update the session tally. Shared by the
     * variant-chooser path ([confirmVariant]) and the rapid-mode auto-save path
     * so both go through the exact same save + stats sequence without racing.
     */
    private suspend fun persistScan(card: RiftboundCard, variant: Variant) {
        runCatching { looseScans.saveEntry(card, variant, price = null) }
            .onFailure { Log.e(TAG, "Saving loose scan failed", it) }
        _stats.update { it.copy(cardsAdded = it.cardsAdded + 1, pendingCount = it.pendingCount + 1) }
        _lastAdded.value = card.name
    }

    /**
     * Batch-price every unpriced loose-scan row in one JustTCG call (or several
     * sequential ones if the user scanned more than `MAX_BATCH` cards in a
     * sitting — JustTCG caps each request at 20 items). Patches successful
     * prices back into the rows, updates the session tally.
     */
    fun submitPending() {
        if (_submit.value is QuickScanSubmitState.InFlight) return
        _submit.value = QuickScanSubmitState.InFlight
        viewModelScope.launch {
            when (val result = looseScans.submitPendingPrices(pricing)) {
                is LooseScanRepository.SubmitResult.Empty -> {
                    _submit.value = QuickScanSubmitState.Done(priced = 0, failed = 0, totalValue = 0.0)
                }
                is LooseScanRepository.SubmitResult.NetworkError -> {
                    Log.e(TAG, "QuickScan submit failed: ${result.reason}")
                    _submit.value = QuickScanSubmitState.Failed(result.reason)
                    _events.tryEmit(QuickScanEvent.SubmitFailed(result.reason))
                }
                is LooseScanRepository.SubmitResult.Done -> {
                    _stats.update {
                        it.copy(
                            totalValue = it.totalValue + result.totalValue,
                            pendingCount = (it.pendingCount - result.priced).coerceAtLeast(0),
                        )
                    }
                    _submit.value = QuickScanSubmitState.Done(
                        priced = result.priced,
                        failed = result.failed,
                        totalValue = result.totalValue,
                    )
                    _events.tryEmit(
                        QuickScanEvent.SubmitCompleted(
                            priced = result.priced,
                            failed = result.failed,
                            totalValue = result.totalValue,
                        ),
                    )
                }
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
        _submit.value = QuickScanSubmitState.Idle
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
        val debugLogging = BuildConfig.DEBUG && current.ocrDebugLogging
        if (debugLogging) {
            Log.d(
                TAG,
                "OCR raw='${blocks.joinToString(" | ") { it.text }}' parsed=$parsed",
            )
        }
        parsed.collectorNumber?.let { number ->
            CardDatabase.lookupByNumber(number)?.let { card ->
                val confidence = if (parsed.setCode in KNOWN_SETS) 0.9f else 0.6f
                if (debugLogging) Log.d(TAG, "OCR lookup=collector confidence=$confidence card=${card.id}")
                return QuickScanState.Identified(card, confidence)
            }
        }
        parsed.name?.let { name ->
            val candidates = CardDatabase.lookupByNameFuzzy(name, limit = 3)
            if (debugLogging) Log.d(TAG, "OCR lookup=name candidates=${candidates.map { it.id }}")
            return when (candidates.size) {
                0 -> QuickScanState.Failed("No match for '$name'")
                1 -> QuickScanState.Identified(candidates.first(), 0.5f)
                else -> QuickScanState.Ambiguous(candidates)
            }
        }
        return QuickScanState.Failed("No readable text on card")
    }
}
