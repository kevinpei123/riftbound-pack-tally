package com.riftbound.packtally.feature.cardbrowser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riftbound.packtally.core.carddb.CardBrowserFilter
import com.riftbound.packtally.core.carddb.CardBrowserQuery
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.core.carddb.CardSortOrder
import com.riftbound.packtally.model.Rarity
import com.riftbound.packtally.model.RiftboundCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class CardBrowserState(
    val catalogueReady: Boolean = false,
    val cards: List<RiftboundCard> = emptyList(),
    val query: CardBrowserQuery = CardBrowserQuery(),
    val availableSetCodes: List<String> = emptyList(),
    val availableDomains: List<String> = emptyList(),
    val availableTypes: List<String> = emptyList(),
    val availableEnergyValues: List<Int> = emptyList(),
    val availableMightValues: List<Int> = emptyList(),
    val availablePowerValues: List<Int> = emptyList(),
) {
    companion object {
        val Empty = CardBrowserState()
    }
}

class CardBrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val _query = MutableStateFlow(CardBrowserQuery())

    val state: StateFlow<CardBrowserState> = _query
        .map { query ->
            val all = CardDatabase.allCards()
            CardBrowserState(
                catalogueReady = CardDatabase.isReady(),
                cards = CardBrowserFilter.apply(all, query),
                query = query,
                availableSetCodes = CardBrowserFilter.availableSetCodes(all),
                availableDomains = CardBrowserFilter.availableDomains(all),
                availableTypes = CardBrowserFilter.availableTypes(all),
                availableEnergyValues = CardBrowserFilter.availableEnergyValues(all),
                availableMightValues = CardBrowserFilter.availableMightValues(all),
                availablePowerValues = CardBrowserFilter.availablePowerValues(all),
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CardBrowserState.Empty)

    fun setSearch(text: String) {
        _query.update { it.copy(search = text) }
    }

    fun toggleSetCode(code: String) {
        _query.update { it.copy(setCodes = it.setCodes.toggled(code)) }
    }

    fun toggleRarity(rarity: Rarity) {
        _query.update { it.copy(rarities = it.rarities.toggled(rarity)) }
    }

    fun toggleDomain(domain: String) {
        _query.update { it.copy(domains = it.domains.toggled(domain)) }
    }

    fun toggleType(type: String) {
        _query.update { it.copy(types = it.types.toggled(type)) }
    }

    fun toggleEnergyValue(value: Int) {
        _query.update { it.copy(energyValues = it.energyValues.toggled(value)) }
    }

    fun toggleMightValue(value: Int) {
        _query.update { it.copy(mightValues = it.mightValues.toggled(value)) }
    }

    fun togglePowerValue(value: Int) {
        _query.update { it.copy(powerValues = it.powerValues.toggled(value)) }
    }

    fun setSortOrder(order: CardSortOrder) {
        _query.update { it.copy(sortOrder = order) }
    }

    fun clearFilters() {
        _query.update {
            it.copy(
                setCodes = emptySet(),
                rarities = emptySet(),
                domains = emptySet(),
                types = emptySet(),
                energyValues = emptySet(),
                mightValues = emptySet(),
                powerValues = emptySet(),
            )
        }
    }

    private fun <T> Set<T>.toggled(item: T): Set<T> =
        if (contains(item)) this - item else this + item
}
