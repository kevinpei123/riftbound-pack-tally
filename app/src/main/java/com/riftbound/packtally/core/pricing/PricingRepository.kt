package com.riftbound.packtally.core.pricing

import com.riftbound.packtally.feature.scanner.Variant
import com.riftbound.packtally.model.RiftboundCard
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * Pricing API after the JustTCG migration. Batch-first:
 *
 *   priceMany(requests) → Map<tcgplayerId, Result<CardPrice>>
 *
 * Implementations split larger inputs into network batches of ≤20 (free-tier
 * limit). The single-card [price] helper wraps in a one-element batch so
 * call-sites can stay terse.
 */
interface PricingRepository {

    suspend fun priceMany(
        requests: List<PriceRequest>,
    ): Map<String, Result<CardPrice>>

    suspend fun price(request: PriceRequest): Result<CardPrice> =
        priceMany(listOf(request))[request.tcgplayerId]
            ?: Result.failure(IllegalStateException("Missing result for ${request.tcgplayerId}"))

    /** Convenience: build a [PriceRequest] from a [RiftboundCard] + the user's variant choice. */
    suspend fun price(card: RiftboundCard, variant: Variant): Result<CardPrice> =
        price(PriceRequest(card.tcgplayerId, variant))
}

data class PriceRequest(
    val tcgplayerId: String,
    val variant: Variant,
)

@Serializable
data class CardPrice(
    val marketPrice: Double,
    val lowPrice: Double,
    val midPrice: Double,
    val highPrice: Double,
    val currency: String = "USD",
    @Serializable(with = InstantIso8601Serializer::class)
    val lastUpdated: Instant,
)

internal object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant =
        Instant.parse(decoder.decodeString())
}
