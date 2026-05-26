package com.riftbound.packtally.model

import com.riftbound.packtally.core.pricing.CardPrice
import com.riftbound.packtally.feature.scanner.Variant
import java.time.Instant
import java.util.UUID

data class ScannedEntry(
    val id: String = UUID.randomUUID().toString(),
    val card: RiftboundCard,
    val variant: Variant,
    val price: CardPrice,
    val confidence: Float,
    val scannedAt: Instant,
)
