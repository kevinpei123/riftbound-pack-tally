package com.riftbound.packtally.model

import com.riftbound.packtally.core.pricing.CardPrice
import com.riftbound.packtally.core.pricing.InstantIso8601Serializer
import com.riftbound.packtally.feature.scanner.Variant
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Serializable
data class ScannedEntry(
    val id: String = UUID.randomUUID().toString(),
    val card: RiftboundCard,
    val variant: Variant,
    val price: CardPrice,
    val confidence: Float,
    @Serializable(with = InstantIso8601Serializer::class)
    val scannedAt: Instant,
)
