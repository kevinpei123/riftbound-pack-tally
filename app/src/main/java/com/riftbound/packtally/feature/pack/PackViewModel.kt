package com.riftbound.packtally.feature.pack

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riftbound.packtally.App
import com.riftbound.packtally.core.pricing.PricingRepository
import com.riftbound.packtally.feature.scanner.Variant
import com.riftbound.packtally.model.BoxSession
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.ScannedEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "PackViewModel"

/** Confidence stamped onto entries created via manual correction. */
private const val CONFIDENCE_MANUAL = 1.0f

sealed interface CorrectionState {
    val entry: ScannedEntry

    data class Editing(override val entry: ScannedEntry) : CorrectionState
    data class Pricing(override val entry: ScannedEntry) : CorrectionState
}

class PackViewModel(application: Application) : AndroidViewModel(application) {

    private val pricing: PricingRepository = (application as App).pricing

    private val _box = MutableStateFlow(BoxSession())
    val box: StateFlow<BoxSession> = _box.asStateFlow()

    private val _correction = MutableStateFlow<CorrectionState?>(null)
    val correction: StateFlow<CorrectionState?> = _correction.asStateFlow()

    fun append(entry: ScannedEntry) {
        _box.value.appendToActivePack(entry)
    }

    fun completePack() {
        val current = _box.value
        if (!current.startNextPack()) {
            _box.value = BoxSession(mode = current.mode)
        }
    }

    fun startNewSession(mode: BoxSession.Mode = _box.value.mode) {
        _box.value = BoxSession(mode = mode)
        _correction.value = null
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
    }

    /**
     * Replace [oldEntry] with one built from [newCard] and [newVariant], re-fetching
     * pricing for the new (card, variant) pair. On success the new entry stamps
     * `confidence = 1.0f` (manual correction). On failure, reverts to Editing and
     * the user can retry.
     */
    fun applyReplacement(
        oldEntry: ScannedEntry,
        newCard: RiftboundCard,
        newVariant: Variant,
    ) {
        _correction.value = CorrectionState.Pricing(oldEntry)
        viewModelScope.launch {
            pricing.price(
                card = newCard,
                foil = newVariant == Variant.FOIL,
                signature = newVariant == Variant.SIGNATURE,
            )
                .onSuccess { price ->
                    val newEntry = oldEntry.copy(
                        card = newCard,
                        variant = newVariant,
                        price = price,
                        confidence = CONFIDENCE_MANUAL,
                    )
                    _box.value.replaceEntry(oldEntry.id, newEntry)
                    _correction.value = null
                }
                .onFailure { exc ->
                    Log.e(TAG, "Replacement pricing failed for ${newCard.id} as $newVariant", exc)
                    _correction.value = CorrectionState.Editing(oldEntry)
                }
        }
    }
}
