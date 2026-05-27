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

    suspend fun priceMany(
        requests: List<PriceRequest>,
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
