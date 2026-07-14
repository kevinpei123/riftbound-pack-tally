package com.riftbound.packtally.core.pricing

import com.riftbound.packtally.model.CardPrice
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.Variant

/**
 * Pricing API after the JustTCG migration. Batch-first:
 *
 *   priceMany(requests) -> Map<PriceRequest, Result<CardPrice>>
 *
 * Implementations split larger inputs into network batches of ≤20 (free-tier
 * limit). The single-card [price] helper wraps in a one-element batch so
 * call-sites can stay terse.
 */
interface PricingRepository {

    /**
     * Price a batch of requests.
     *
     * @param forceRefresh when true, cache layers must bypass their stored
     * entries and fetch fresh prices from the network (still writing the fresh
     * results back to cache). Used by "refresh all prices" so the displayed
     * values are genuinely up to date rather than served from a fresh-TTL cache.
     */
    suspend fun priceMany(
        requests: List<PriceRequest>,
        forceRefresh: Boolean = false,
    ): Map<PriceRequest, Result<CardPrice>>

    suspend fun price(request: PriceRequest): Result<CardPrice> =
        priceMany(listOf(request))[request]
            ?: Result.failure(IllegalStateException("Missing result for $request"))

    /** Convenience: build a [PriceRequest] from a [RiftboundCard] + the user's variant choice. */
    suspend fun price(card: RiftboundCard, variant: Variant): Result<CardPrice> =
        price(PriceRequest(card.tcgplayerId, variant))
}

data class PriceRequest(
    val tcgplayerId: String,
    val variant: Variant,
)
