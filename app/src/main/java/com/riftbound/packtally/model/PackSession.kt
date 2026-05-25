package com.riftbound.packtally.model

import com.riftbound.packtally.core.pricing.CardPrice
import com.riftbound.packtally.feature.scanner.Variant
import java.time.Instant
import java.util.UUID

data class PackSession(
    val id: String,
    val startedAt: Instant,
    val entries: List<ScannedEntry>,
) {
    companion object {
        fun empty(): PackSession = PackSession(
            id = UUID.randomUUID().toString(),
            startedAt = Instant.now(),
            entries = emptyList(),
        )
    }
}

data class ScannedEntry(
    val card: RiftboundCard,
    val variant: Variant,
    val price: CardPrice,
    val scannedAt: Instant,
)
