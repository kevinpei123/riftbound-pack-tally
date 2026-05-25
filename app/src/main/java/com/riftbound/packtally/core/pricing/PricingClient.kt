package com.riftbound.packtally.core.pricing

interface PricingClient {
    suspend fun getPrice(collectorNumber: String): PriceQuote?
}

data class PriceQuote(
    val collectorNumber: String,
    val priceUsd: Double,
    val fetchedAtEpochMs: Long,
)
