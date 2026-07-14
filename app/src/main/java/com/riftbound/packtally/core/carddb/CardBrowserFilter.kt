package com.riftbound.packtally.core.carddb

import com.riftbound.packtally.model.Rarity
import com.riftbound.packtally.model.RiftboundCard

enum class CardSortOrder {
    NAME_ASC,
    NAME_DESC,
    SET_NUMBER_ASC,
    RARITY_ASC,
}

/**
 * Card browser filter/sort state. Empty [setCodes]/[rarities]/[domains] mean
 * "no restriction on this facet" rather than "match nothing."
 */
data class CardBrowserQuery(
    val search: String = "",
    val setCodes: Set<String> = emptySet(),
    val rarities: Set<Rarity> = emptySet(),
    val domains: Set<String> = emptySet(),
    val sortOrder: CardSortOrder = CardSortOrder.NAME_ASC,
)

/**
 * Pure in-memory filter/sort over the already-loaded [CardDatabase] catalogue.
 * ~1k cards makes this trivial to run on every keystroke; no need for a Room
 * query or paging library.
 */
object CardBrowserFilter {

    fun apply(cards: List<RiftboundCard>, query: CardBrowserQuery): List<RiftboundCard> {
        val needle = query.search.trim().lowercase()
        val filtered = cards.filter { card ->
            matchesSearch(card, needle) &&
                (query.setCodes.isEmpty() || card.setCode in query.setCodes) &&
                (query.rarities.isEmpty() || card.rarity in query.rarities) &&
                (query.domains.isEmpty() || card.domains.any { it in query.domains })
        }
        return sort(filtered, query.sortOrder)
    }

    private fun matchesSearch(card: RiftboundCard, needle: String): Boolean {
        if (needle.isEmpty()) return true
        return card.name.lowercase().contains(needle) ||
            card.collectorNumber.lowercase().contains(needle)
    }

    private fun sort(cards: List<RiftboundCard>, order: CardSortOrder): List<RiftboundCard> =
        when (order) {
            CardSortOrder.NAME_ASC -> cards.sortedBy { it.name.lowercase() }
            CardSortOrder.NAME_DESC -> cards.sortedByDescending { it.name.lowercase() }
            CardSortOrder.SET_NUMBER_ASC -> cards.sortedWith(
                compareBy({ it.setCode }, { it.collectorNumber }),
            )
            CardSortOrder.RARITY_ASC -> cards.sortedWith(
                compareBy({ it.rarity.ordinal }, { it.name.lowercase() }),
            )
        }

    fun availableSetCodes(cards: List<RiftboundCard>): List<String> =
        cards.map { it.setCode }.distinct().sorted()

    fun availableDomains(cards: List<RiftboundCard>): List<String> =
        cards.flatMap { it.domains }.distinct().sorted()
}
