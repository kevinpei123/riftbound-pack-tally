package com.riftbound.packtally.core.carddb

import com.riftbound.packtally.model.Rarity
import com.riftbound.packtally.model.RiftboundCard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CardBrowserFilterTest {

    private val annie = card(id = "annie", name = "Annie, Fiery", setCode = "OGN", number = "001", rarity = Rarity.COMMON, domains = listOf("Fury"), type = "unit", energy = 1, might = 2, power = 1)
    private val jinx = card(id = "jinx", name = "Jinx, Loose Cannon", setCode = "OGN", number = "002", rarity = Rarity.EPIC, domains = listOf("Fury", "Chaos"), type = "unit", energy = 3, might = 4, power = 3)
    private val vilemaw = card(id = "vile", name = "Vilemaw", setCode = "UNL", number = "156", rarity = Rarity.RARE, domains = listOf("Chaos"), type = "rune")
    private val all = listOf(annie, jinx, vilemaw)

    @Test
    fun `blank query returns everything sorted by name`() {
        val result = CardBrowserFilter.apply(all, CardBrowserQuery())
        assertEquals(listOf(annie, jinx, vilemaw), result)
    }

    @Test
    fun `search matches name or collector number, case-insensitively`() {
        assertEquals(listOf(jinx), CardBrowserFilter.apply(all, CardBrowserQuery(search = "loose cannon")))
        assertEquals(listOf(vilemaw), CardBrowserFilter.apply(all, CardBrowserQuery(search = "unl-156")))
        assertEquals(emptyList<RiftboundCard>(), CardBrowserFilter.apply(all, CardBrowserQuery(search = "nonexistent")))
    }

    @Test
    fun `set code filter restricts to matching sets`() {
        val result = CardBrowserFilter.apply(all, CardBrowserQuery(setCodes = setOf("OGN")))
        assertEquals(listOf(annie, jinx), result)
    }

    @Test
    fun `rarity filter restricts to matching rarities`() {
        val result = CardBrowserFilter.apply(all, CardBrowserQuery(rarities = setOf(Rarity.EPIC)))
        assertEquals(listOf(jinx), result)
    }

    @Test
    fun `domain filter matches any overlapping domain`() {
        val result = CardBrowserFilter.apply(all, CardBrowserQuery(domains = setOf("Chaos")))
        assertEquals(listOf(jinx, vilemaw), result)
    }

    @Test
    fun `filters combine with AND semantics`() {
        val result = CardBrowserFilter.apply(
            all,
            CardBrowserQuery(setCodes = setOf("OGN"), domains = setOf("Chaos")),
        )
        assertEquals(listOf(jinx), result)
    }

    @Test
    fun `sort orders`() {
        assertEquals(
            listOf(vilemaw, jinx, annie),
            CardBrowserFilter.apply(all, CardBrowserQuery(sortOrder = CardSortOrder.NAME_DESC)),
        )
        assertEquals(
            listOf(annie, jinx, vilemaw),
            CardBrowserFilter.apply(all, CardBrowserQuery(sortOrder = CardSortOrder.SET_NUMBER_ASC)),
        )
        assertEquals(
            listOf(annie, vilemaw, jinx),
            CardBrowserFilter.apply(all, CardBrowserQuery(sortOrder = CardSortOrder.RARITY_ASC)),
        )
    }

    @Test
    fun `available set codes and domains are distinct and sorted`() {
        assertEquals(listOf("OGN", "UNL"), CardBrowserFilter.availableSetCodes(all))
        assertEquals(listOf("Chaos", "Fury"), CardBrowserFilter.availableDomains(all))
    }

    @Test
    fun `type filter restricts to matching types`() {
        assertEquals(listOf(annie, jinx), CardBrowserFilter.apply(all, CardBrowserQuery(types = setOf("unit"))))
        assertEquals(listOf(vilemaw), CardBrowserFilter.apply(all, CardBrowserQuery(types = setOf("rune"))))
    }

    @Test
    fun `energy might and power filters match exact values and exclude missing stats`() {
        assertEquals(listOf(annie), CardBrowserFilter.apply(all, CardBrowserQuery(energyValues = setOf(1))))
        assertEquals(listOf(jinx), CardBrowserFilter.apply(all, CardBrowserQuery(mightValues = setOf(4))))
        assertEquals(listOf(annie, jinx), CardBrowserFilter.apply(all, CardBrowserQuery(powerValues = setOf(1, 3))))
        // vilemaw has no energy/might/power — an active numeric filter never matches it.
        assertEquals(emptyList<RiftboundCard>(), CardBrowserFilter.apply(all, CardBrowserQuery(energyValues = setOf(99))))
    }

    @Test
    fun `available types and stat values are distinct sorted and skip missing values`() {
        assertEquals(listOf("rune", "unit"), CardBrowserFilter.availableTypes(all))
        assertEquals(listOf(1, 3), CardBrowserFilter.availableEnergyValues(all))
        assertEquals(listOf(2, 4), CardBrowserFilter.availableMightValues(all))
        assertEquals(listOf(1, 3), CardBrowserFilter.availablePowerValues(all))
    }

    private fun card(
        id: String,
        name: String,
        setCode: String,
        number: String,
        rarity: Rarity,
        domains: List<String> = emptyList(),
        type: String = "",
        energy: Int? = null,
        might: Int? = null,
        power: Int? = null,
    ): RiftboundCard = RiftboundCard(
        id = id,
        collectorNumber = "$setCode-$number",
        name = name,
        setCode = setCode,
        rarity = rarity,
        isFoilByDefault = false,
        hasSignatureVariant = false,
        tcgplayerId = id,
        domains = domains,
        type = type,
        energy = energy,
        might = might,
        power = power,
    )
}
