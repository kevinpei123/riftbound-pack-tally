package com.riftbound.packtally.core.carddb

import com.riftbound.packtally.model.Card

interface CardDatabase {
    suspend fun lookup(collectorNumber: String): Card?
}
