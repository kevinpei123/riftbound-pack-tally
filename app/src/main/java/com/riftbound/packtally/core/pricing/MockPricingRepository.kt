package com.riftbound.packtally.core.pricing

import com.riftbound.packtally.model.Rarity
import com.riftbound.packtally.model.RiftboundCard
import java.time.Instant

/**
 * Deterministic fake pricing for `@Preview` composables and offline testing.
 * Prices are derived from rarity and variant flags — not real market data.
 */
class MockPricingRepository : PricingRepository {

    override suspend fun price(
        card: RiftboundCard,
        foil: Boolean,
        signature: Boolean,
    ): Result<CardPrice> {
        val base = baseForRarity(card.rarity)
        val market = base * variantMultiplier(foil, signature)
        return Result.success(
            CardPrice(
                marketPrice = market,
                lowPrice = market * 0.7,
                midPrice = market * 0.95,
                highPrice = market * 1.4,
                currency = "USD",
                lastUpdated = Instant.parse("2026-05-26T08:00:00Z"),
            ),
        )
    }

    private fun baseForRarity(rarity: Rarity): Double = when (rarity) {
        Rarity.COMMON -> 0.10
        Rarity.UNCOMMON -> 0.25
        Rarity.RARE -> 1.50
        Rarity.EPIC -> 5.00
        Rarity.SHOWCASE -> 12.00
    }

    private fun variantMultiplier(foil: Boolean, signature: Boolean): Double = when {
        signature -> 10.0
        foil -> 3.0
        else -> 1.0
    }
}
