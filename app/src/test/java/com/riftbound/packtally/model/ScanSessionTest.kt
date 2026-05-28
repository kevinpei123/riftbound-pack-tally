package com.riftbound.packtally.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ScanSessionTest {

    @Test
    fun `session counts pending failed unpriceable and priced entries`() {
        val session = ScanSession(
            id = "session",
            entries = listOf(
                entry("1", PricingStatus.PENDING),
                entry("2", PricingStatus.FAILED),
                entry("3", PricingStatus.UNPRICEABLE),
                entry(
                    "4",
                    PricingStatus.PRICED,
                    price = CardPrice(2.5, 2.0, 2.5, 3.0, lastUpdated = Instant.EPOCH),
                ),
            ),
        )

        assertEquals(4, session.totalCards)
        assertEquals(2, session.pendingPriceCount)
        assertEquals(1, session.unpriceableCount)
        assertEquals(2.5, session.totalValueUsd)
    }

    @Test
    fun `completed session is not active`() {
        assertTrue(ScanSession(status = ScanSessionStatus.ACTIVE).isActive)
        assertFalse(ScanSession(status = ScanSessionStatus.COMPLETED).isActive)
    }

    private fun entry(
        id: String,
        status: PricingStatus,
        price: CardPrice? = null,
    ): ScanSessionEntry = ScanSessionEntry(
        id = id,
        sessionId = "session",
        card = card(id),
        variant = Variant.STANDARD,
        price = price,
        pricingStatus = status,
        scannedAt = Instant.EPOCH,
    )

    private fun card(id: String): RiftboundCard = RiftboundCard(
        id = id,
        collectorNumber = "OGN-00$id",
        name = "Card $id",
        setCode = "OGN",
        rarity = Rarity.COMMON,
        isFoilByDefault = false,
        hasSignatureVariant = false,
        tcgplayerId = "tcg-$id",
    )
}
