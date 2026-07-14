package com.riftbound.packtally.core.pricing

import com.riftbound.packtally.model.CardPrice
import com.riftbound.packtally.model.Variant
import java.time.Instant

/**
 * Deterministic fake pricing for `@Preview` composables and offline testing.
 * Implements the post-migration [PricingRepository] batch contract.
 *
 * Returns the same fake CardPrice for every (tcgplayerId, variant) regardless
 * of the actual card — prices are derived from the variant only since the
 * Mock doesn't have access to the Riftcodex catalogue.
 */
class MockPricingRepository : PricingRepository {

    override suspend fun priceMany(
        requests: List<PriceRequest>,
        forceRefresh: Boolean,
    ): Map<PriceRequest, Result<CardPrice>> {
        return requests.associate { req ->
            req to Result.success(fakePrice(req.variant))
        }
    }

    private fun fakePrice(variant: Variant): CardPrice {
        val market = when (variant) {
            Variant.STANDARD -> 0.50
            Variant.FOIL -> 1.75
            Variant.SIGNATURE -> 12.00
        }
        return CardPrice(
            marketPrice = market,
            lowPrice = market * 0.7,
            midPrice = market * 0.95,
            highPrice = market * 1.4,
            currency = "USD",
            lastUpdated = Instant.parse("2026-05-26T08:00:00Z"),
        )
    }
}
