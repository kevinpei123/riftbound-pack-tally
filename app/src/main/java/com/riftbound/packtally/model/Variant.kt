package com.riftbound.packtally.model

import kotlinx.serialization.Serializable

@Serializable
enum class Variant {
    STANDARD,
    FOIL,
    SIGNATURE,
}
