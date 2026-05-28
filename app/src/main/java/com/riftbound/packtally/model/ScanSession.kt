package com.riftbound.packtally.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ScanSession(
    val id: String = UUID.randomUUID().toString(),
    val createdAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
    val name: String? = null,
    val status: ScanSessionStatus = ScanSessionStatus.ACTIVE,
    val entries: List<ScanSessionEntry> = emptyList(),
) {
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: "Scan Session ${DISPLAY_FORMAT.format(createdAt.atZone(ZoneId.systemDefault()))}"

    val isActive: Boolean get() = status == ScanSessionStatus.ACTIVE
    val totalCards: Int get() = entries.size
    val pendingPriceCount: Int
        get() = entries.count {
            it.pricingStatus == PricingStatus.PENDING || it.pricingStatus == PricingStatus.FAILED
        }
    val unpriceableCount: Int get() = entries.count { it.pricingStatus == PricingStatus.UNPRICEABLE }
    val totalValueUsd: Double get() = entries.sumOf { it.marketPrice }

    companion object {
        private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")
    }
}

data class ScanSessionEntry(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val card: RiftboundCard,
    val variant: Variant,
    val price: CardPrice? = null,
    val pricingStatus: PricingStatus = PricingStatus.PENDING,
    val pricingError: String? = null,
    val scannedAt: Instant = Instant.now(),
    val source: ScanEntrySource = ScanEntrySource.OCR,
    val confidence: Float = 1.0f,
    val manuallyCorrected: Boolean = false,
    val notes: String? = null,
) {
    val isPriced: Boolean get() = pricingStatus == PricingStatus.PRICED && price != null
    val marketPrice: Double get() = price?.marketPrice ?: 0.0

    fun toScannedEntry(): ScannedEntry = ScannedEntry(
        id = id,
        card = card,
        variant = variant,
        price = price,
        confidence = confidence,
        scannedAt = scannedAt,
    )
}

enum class ScanSessionStatus {
    ACTIVE,
    COMPLETED,
    ARCHIVED,
}

enum class PricingStatus {
    PENDING,
    PRICED,
    FAILED,
    UNPRICEABLE,
}

enum class ScanEntrySource {
    OCR,
    RAPID,
    MANUAL,
    MIGRATED_PACK,
    MIGRATED_LOOSE,
}
