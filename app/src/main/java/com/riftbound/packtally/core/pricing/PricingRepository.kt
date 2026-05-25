package com.riftbound.packtally.core.pricing

import com.riftbound.packtally.model.RiftboundCard
import java.time.Instant

interface PricingRepository {
    suspend fun price(
        card: RiftboundCard,
        foil: Boolean,
        signature: Boolean,
    ): Result<CardPrice>
}

data class CardPrice(
    val marketPrice: Double,
    val lowPrice: Double,
    val midPrice: Double,
    val highPrice: Double,
    val currency: String = "USD",
    val lastUpdated: Instant,
)
