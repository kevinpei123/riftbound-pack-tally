package com.riftbound.packtally.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RiftboundCard(
    val id: String,
    val collectorNumber: String,
    val name: String,
    val setCode: String,
    val rarity: Rarity,
    val isFoilByDefault: Boolean,
    val hasSignatureVariant: Boolean,
)

@Serializable
enum class Rarity {
    @SerialName("common") COMMON,
    @SerialName("uncommon") UNCOMMON,
    @SerialName("rare") RARE,
    @SerialName("epic") EPIC,
    @SerialName("showcase") SHOWCASE,
}
