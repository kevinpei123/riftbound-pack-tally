package com.riftbound.packtally.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class ScannedEntry(
    val id: String = UUID.randomUUID().toString(),
    val card: RiftboundCard,
    val variant: Variant,
    // Null when the card has been scanned but not yet priced. The scan flow
    // intentionally defers JustTCG calls — a whole pack (14 cards) is priced
    // in one batch request rather than one-per-scan, since the free tier only
    // allows ~1000 requests/month.
    val price: CardPrice? = null,
    val confidence: Float,
    @Serializable(with = InstantIso8601Serializer::class)
    val scannedAt: Instant,
) {
    val isPriced: Boolean get() = price != null
    val marketPrice: Double get() = price?.marketPrice ?: 0.0
}
