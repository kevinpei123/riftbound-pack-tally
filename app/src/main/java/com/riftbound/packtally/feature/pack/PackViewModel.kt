package com.riftbound.packtally.feature.pack

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riftbound.packtally.App
import com.riftbound.packtally.core.persistence.SessionRepository
import com.riftbound.packtally.core.pricing.PriceRequest
import com.riftbound.packtally.core.pricing.PricingRepository
import com.riftbound.packtally.model.BoxSession
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.ScannedEntry
import com.riftbound.packtally.model.Variant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "PackViewModel"

private const val CONFIDENCE_MANUAL = 1.0f

sealed interface CorrectionState {
    val entry: ScannedEntry

    data class Editing(override val entry: ScannedEntry) : CorrectionState
}

sealed interface SubmitState {
    data object Idle : SubmitState
    data object InFlight : SubmitState

    /** A submit completed; `priced` is how many entries got a price, `failed` is how many didn't. */
    data class Done(val priced: Int, val failed: Int) : SubmitState
    data class Failed(val reason: String) : SubmitState
}

sealed interface PackEvent {
    /** "Submitted N cards — total $X.XX" toast after the batch returns. */
    data class SubmitCompleted(val priced: Int, val failed: Int, val packTotal: Double) : PackEvent
    data class SubmitFailed(val reason: String) : PackEvent
}

class PackViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val pricing: PricingRepository = app.pricing
    private val sessionRepository: SessionRepository = app.sessionRepository

    private val _box = MutableStateFlow(BoxSession())
    val box: StateFlow<BoxSession> = _box.asStateFlow()

    private val _correction = MutableStateFlow<CorrectionState?>(null)
    val correction: StateFlow<CorrectionState?> = _correction.asStateFlow()

    private val _submit = MutableStateFlow<SubmitState>(SubmitState.Idle)
    val submit: StateFlow<SubmitState> = _submit.asStateFlow()

    private val _events = MutableSharedFlow<PackEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PackEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            // Restore the most recent unfinished session, if any.
            val restored = runCatching { sessionRepository.loadMostRecentBox() }
                .onFailure { Log.e(TAG, "Restore failed", it) }
                .getOrNull()
            if (restored != null && !restored.isFull) {
                _box.value = restored
            }
        }
        viewModelScope.launch {
            // Nuclear reset triggered from Settings — drop the in-memory session
            // so the next mutation doesn't immediately repopulate Room.
            app.resetEvents.collect {
                _box.value = BoxSession()
                _correction.value = null
            }
        }
    }

    /**
     * Re-read the active box from disk. The Pack screen calls this on entry so
     * that edits made elsewhere (e.g. cross-pack remove from Collection) show
     * up immediately when the user navigates here.
     */
    fun refreshFromDisk() {
        viewModelScope.launch {
            val restored = runCatching { sessionRepository.loadMostRecentBox() }
                .onFailure { Log.e(TAG, "Refresh-from-disk failed", it) }
                .getOrNull()
            if (restored != null && !restored.isFull) {
                _box.value = restored
            }
        }
    }

    fun append(entry: ScannedEntry) {
        _box.value.appendToActivePack(entry)
        persist()
    }

    /**
     * Batch-price every unpriced entry in the active pack with a single JustTCG
     * request and patch the prices back into the in-memory pack. Idempotent —
     * a second invocation on an already-priced pack is a no-op and emits Done(0,0).
     *
     * Failure surfaces a [PackEvent.SubmitFailed] event. The unpriced entries
     * remain in the pack; the user can retry from the same button.
     */
    fun submitPack() {
        if (_submit.value is SubmitState.InFlight) return
        val activePack = _box.value.packs.value.lastOrNull() ?: return
        val unpriced = activePack.entries.value.filterNot { it.isPriced }
        if (unpriced.isEmpty()) {
            _submit.value = SubmitState.Done(priced = 0, failed = 0)
            return
        }
        _submit.value = SubmitState.InFlight
        viewModelScope.launch {
            val requests = unpriced
                .mapNotNull { entry ->
                    entry.card.tcgplayerId.takeIf { it.isNotBlank() }
                        ?.let { PriceRequest(it, entry.variant) }
                }
            val resultMap = if (requests.isNotEmpty()) {
                runCatching { pricing.priceMany(requests) }
                    .getOrElse { exc ->
                        Log.e(TAG, "Pack submit failed", exc)
                        val reason = exc.message ?: "Pricing call failed"
                        _submit.value = SubmitState.Failed(reason)
                        _events.tryEmit(PackEvent.SubmitFailed(reason))
                        return@launch
                    }
            } else {
                emptyMap()
            }

            var priced = 0
            var failed = 0
            unpriced.forEach { entry ->
                val request = entry.card.tcgplayerId.takeIf { it.isNotBlank() }
                    ?.let { PriceRequest(it, entry.variant) }
                val r = request?.let { resultMap[it] }
                r?.onSuccess { price ->
                    activePack.replaceEntry(entry.id, entry.copy(price = price))
                    priced += 1
                }?.onFailure { exc ->
                    Log.w(TAG, "Pricing failed for ${entry.card.id} (${entry.variant})", exc)
                    failed += 1
                } ?: run { failed += 1 }
            }
            _box.value.recomputeGrandTotalPublic()
            persist()
            _submit.value = SubmitState.Done(priced = priced, failed = failed)
            _events.tryEmit(
                PackEvent.SubmitCompleted(
                    priced = priced,
                    failed = failed,
                    packTotal = activePack.runningTotal.value,
                ),
            )
        }
    }

    /**
     * Advance to the next pack. If the active pack still has unpriced entries,
     * this batches a submit first and only advances once prices come back
     * (success or failure). The user can keep going either way — failed
     * entries just stay at $0 in the running totals until the next submit.
     */
    fun completePack() {
        val current = _box.value
        val activePack = current.packs.value.lastOrNull()
        if (activePack != null && activePack.hasPendingPrices) {
            viewModelScope.launch {
                if (submitAndAwait()) {
                    advanceToNextPack()
                }
            }
        } else {
            advanceToNextPack()
        }
    }

    private suspend fun submitAndAwait(): Boolean {
        if (_submit.value is SubmitState.InFlight) return false
        val activePack = _box.value.packs.value.lastOrNull() ?: return false
        val unpriced = activePack.entries.value.filterNot { it.isPriced }
        if (unpriced.isEmpty()) return true
        _submit.value = SubmitState.InFlight
        val requests = unpriced
            .mapNotNull { entry ->
                entry.card.tcgplayerId.takeIf { it.isNotBlank() }
                    ?.let { PriceRequest(it, entry.variant) }
            }
        val resultMap = if (requests.isNotEmpty()) {
            runCatching { pricing.priceMany(requests) }
                .getOrElse { exc ->
                    Log.e(TAG, "Pack submit (via completePack) failed", exc)
                    val reason = exc.message ?: "Pricing call failed"
                    _submit.value = SubmitState.Failed(reason)
                    _events.tryEmit(PackEvent.SubmitFailed(reason))
                    return false
                }
        } else {
            emptyMap()
        }
        var priced = 0
        var failed = 0
        unpriced.forEach { entry ->
            val request = entry.card.tcgplayerId.takeIf { it.isNotBlank() }
                ?.let { PriceRequest(it, entry.variant) }
            val r = request?.let { resultMap[it] }
            r?.onSuccess { price ->
                activePack.replaceEntry(entry.id, entry.copy(price = price))
                priced += 1
            }?.onFailure { failed += 1 } ?: run { failed += 1 }
        }
        _box.value.recomputeGrandTotalPublic()
        _submit.value = SubmitState.Done(priced = priced, failed = failed)
        _events.tryEmit(
            PackEvent.SubmitCompleted(
                priced = priced,
                failed = failed,
                packTotal = activePack.runningTotal.value,
            ),
        )
        return failed == 0
    }

    private fun advanceToNextPack() {
        val current = _box.value
        if (!current.startNextPack()) {
            _box.value = BoxSession(mode = current.mode)
        }
        persist()
    }

    fun startNewSession(mode: BoxSession.Mode = _box.value.mode) {
        _box.value = BoxSession(mode = mode)
        _correction.value = null
        persist()
    }

    fun beginCorrection(entry: ScannedEntry) {
        _correction.value = CorrectionState.Editing(entry)
    }

    fun cancelCorrection() {
        _correction.value = null
    }

    fun deleteEntry(entry: ScannedEntry) {
        _box.value.removeEntry(entry.id)
        _correction.value = null
        persist()
    }

    /**
     * Replace [oldEntry] with one built from [newCard] and [newVariant]. The new
     * entry has `confidence = 1.0` (manual) and `price = null` — it'll be priced
     * on the next batch [submitPack] call. Per-correction pricing would burn the
     * free-tier quota one card at a time, which is exactly what we're avoiding.
     */
    fun applyReplacement(
        oldEntry: ScannedEntry,
        newCard: RiftboundCard,
        newVariant: Variant,
    ) {
        val newEntry = oldEntry.copy(
            card = newCard,
            variant = newVariant,
            price = null,
            confidence = CONFIDENCE_MANUAL,
        )
        _box.value.replaceEntry(oldEntry.id, newEntry)
        _correction.value = null
        persist()
    }

    private fun persist() {
        val snapshot = _box.value
        viewModelScope.launch {
            runCatching { sessionRepository.save(snapshot) }
                .onFailure { Log.e(TAG, "Persist failed for box ${snapshot.id}", it) }
        }
    }
}
