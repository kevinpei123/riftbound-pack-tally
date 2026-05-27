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
    /**
     * Join key into JustTCG's pricing API. Riftcodex carries one for every
     * card; cards lacking it are dropped during sync. The default empty string
     * exists only so older pack-session JSON deserializes cleanly after app
     * upgrades. Persistence code fills in the real value where possible.
     */
    val tcgplayerId: String = "",
    /** True when Riftcodex flags the card as having an alternate-art printing. */
    val hasAlternateArt: Boolean = false,
    /** Optional image URL from Riftcodex media block, useful in correction sheet. */
    val imageUrl: String? = null,
)

@Serializable
enum class Rarity {
    @SerialName("common") COMMON,
    @SerialName("uncommon") UNCOMMON,
    @SerialName("rare") RARE,
    @SerialName("epic") EPIC,
    @SerialName("showcase") SHOWCASE,
}
