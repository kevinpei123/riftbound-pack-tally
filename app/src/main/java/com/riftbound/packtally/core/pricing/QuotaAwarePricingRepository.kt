package com.riftbound.packtally.core.pricing

import com.riftbound.packtally.model.RiftboundCard

/**
 * Decorator that sits between [CachedPricingRepository] and [HttpPricingRepository]
 * so cache hits bypass it entirely (and don't count against quota).
 *
 * Wiring order:
 *   CachedPricingRepository(QuotaAwarePricingRepository(HttpPricingRepository(...)))
 *
 * Flow on a [price] call:
 *   1. Cache hit  → returns inside Cached, never reaches us. No quota burn.
 *   2. Cache miss → Cached delegates to us.
 *      a. If [QuotaTracker.isAtCapacity] → fail fast with [RateLimitedException].
 *      b. If [QuotaTracker.useCachedOnly] is on → fail with [CachedOnlyModeException].
 *      c. Otherwise call the network. On Result.success, increment.
 *         On Result.failure (4xx/5xx, network drops), don't increment.
 */
class QuotaAwarePricingRepository(
    private val delegate: PricingRepository,
    private val quota: QuotaTracker,
) : PricingRepository {

    override suspend fun price(
        card: RiftboundCard,
        foil: Boolean,
        signature: Boolean,
    ): Result<CardPrice> {
        val state = quota.currentState()
        if (state.isAtCapacity) {
            return Result.failure(
                RateLimitedException(
                    used = state.used,
                    limit = state.limit,
                    resetsAt = state.resetsAt,
                ),
            )
        }
        if (quota.useCachedOnly.value) {
            return Result.failure(CachedOnlyModeException())
        }

        val result = delegate.price(card, foil, signature)
        result.onSuccess { quota.recordNetworkCall() }
        return result
    }
}
