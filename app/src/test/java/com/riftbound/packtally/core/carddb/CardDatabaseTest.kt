package com.riftbound.packtally.core.carddb

import com.riftbound.packtally.model.Rarity
import com.riftbound.packtally.model.RiftboundCard
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CardDatabaseTest {

    @Test
    fun `lookup by collector number handles padding suffixes and totals`() {
        val standard = card(id = "std", collectorNumber = "OGN-120", name = "Standard Card")
        val alt = card(id = "alt", collectorNumber = "OGN-120a", name = "Alt Card")
        val unl = card(id = "unl", collectorNumber = "UNL-060", name = "Unleashed")
        CardDatabase.initForTests(listOf(standard, alt, unl))

        assertEquals(standard, CardDatabase.lookupByNumber("OGN-120/298"))
        assertEquals(alt, CardDatabase.lookupByNumber("OGN-120a/298"))
        assertEquals(unl, CardDatabase.lookupByNumber("UNL-60/219"))
    }

    @Test
    fun `lookup by bare number only returns unambiguous matches`() {
        val ogn = card(id = "ogn", collectorNumber = "OGN-001", name = "Origin")
        val unl = card(id = "unl", collectorNumber = "UNL-001", name = "Unleashed")
        CardDatabase.initForTests(listOf(ogn, unl))

        assertNull(CardDatabase.lookupByNumber("001"))

        CardDatabase.initForTests(listOf(ogn))
        assertEquals(ogn, CardDatabase.lookupByNumber("1"))
    }

    @Test
    fun `fuzzy lookup handles exact clean partial and OCR substitutions`() {
        val annie = card(id = "annie", collectorNumber = "OGN-001", name = "Annie, Fiery")
        val jinx = card(id = "jinx", collectorNumber = "OGN-002", name = "Jinx, Loose Cannon")
        val vilemaw = card(id = "vile", collectorNumber = "UNL-156", name = "Vilemaw")
        CardDatabase.initForTests(listOf(annie, jinx, vilemaw))

        assertEquals(annie, CardDatabase.lookupByNameFuzzy("Annie, Fiery").first())
        assertEquals(vilemaw, CardDatabase.lookupByNameFuzzy("vile").first())
        assertEquals(jinx, CardDatabase.lookupByNameFuzzy("J1nx Loose Cann0n").first())
    }

    @Test
    fun `fuzzy lookup refuses very low confidence noise`() {
        CardDatabase.initForTests(
            listOf(card(id = "annie", collectorNumber = "OGN-001", name = "Annie, Fiery")),
        )

        assertEquals(emptyList<RiftboundCard>(), CardDatabase.lookupByNameFuzzy("zzzzzzzz"))
    }

    private fun card(
        id: String,
        collectorNumber: String,
        name: String,
        setCode: String = collectorNumber.substringBefore('-'),
        tcgplayerId: String = id,
    ): RiftboundCard = RiftboundCard(
        id = id,
        collectorNumber = collectorNumber,
        name = name,
        setCode = setCode,
        rarity = Rarity.COMMON,
        isFoilByDefault = false,
        hasSignatureVariant = false,
        tcgplayerId = tcgplayerId,
    )
}
