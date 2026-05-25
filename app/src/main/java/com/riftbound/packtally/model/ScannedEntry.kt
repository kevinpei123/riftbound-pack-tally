package com.riftbound.packtally.model

import com.riftbound.packtally.core.pricing.CardPrice
import com.riftbound.packtally.feature.scanner.Variant
import java.time.Instant

data class ScannedEntry(
    val card: RiftboundCard,
    val variant: Variant,
    val price: CardPrice,
    val scannedAt: Instant,
)
