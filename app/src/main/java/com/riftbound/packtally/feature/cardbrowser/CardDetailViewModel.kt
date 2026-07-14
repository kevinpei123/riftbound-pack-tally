package com.riftbound.packtally.feature.cardbrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.riftbound.packtally.App
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.core.pricing.CachedPricingRepository
import com.riftbound.packtally.core.pricing.PriceRequest
import com.riftbound.packtally.model.CardPrice
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.Variant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CardDetailState(
    val isLoading: Boolean = true,
    val card: RiftboundCard? = null,
    /** Cached price per applicable variant. Null value means no cached price yet. */
    val prices: Map<Variant, CardPrice?> = emptyMap(),
)

/**
 * Shows cached prices only — a card-detail view is browsing, not an explicit
 * Submit action, so it must never trigger a network price fetch.
 */
class CardDetailViewModel(
    private val cardId: String,
    private val pricing: CachedPricingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CardDetailState())
    val state: StateFlow<CardDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val card = CardDatabase.lookupById(cardId)
            if (card == null) {
                _state.value = CardDetailState(isLoading = false, card = null)
                return@launch
            }
            val variants = applicableVariants(card)
            val prices = if (card.tcgplayerId.isBlank()) {
                variants.associateWith { null }
            } else {
                variants.associateWith { variant ->
                    pricing.peekCached(PriceRequest(card.tcgplayerId, variant))
                }
            }
            _state.value = CardDetailState(isLoading = false, card = card, prices = prices)
        }
    }

    private fun applicableVariants(card: RiftboundCard): List<Variant> = buildList {
        add(Variant.STANDARD)
        add(Variant.FOIL)
        if (card.hasSignatureVariant) add(Variant.SIGNATURE)
    }

    companion object {
        fun factory(app: App, cardId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CardDetailViewModel(cardId = cardId, pricing = app.cachedPricing)
            }
        }
    }
}
