package com.riftbound.packtally.core.settings

import kotlinx.coroutines.flow.Flow

enum class Currency(val symbol: String, val code: String) {
    USD(symbol = "$", code = "USD"),
    EUR(symbol = "€", code = "EUR"),
    GBP(symbol = "£", code = "GBP"),
    AUD(symbol = "A$", code = "AUD"),
}

/**
 * User-controlled settings snapshot.
 *
 * [usdToTargetRate] is the multiplier applied to USD prices to get [currency].
 * When [currency] is [Currency.USD], the rate is ignored (treat as 1.0).
 */
data class AppSettings(
    val apiKey: String? = null,
    val currency: Currency = Currency.AUD,
    val usdToTargetRate: Double = 1.55,
    // CHOICE: 24h default per Phase 2 — tcgapi.dev refreshes prices once daily,
    // so a 24-hour cache window matches the upstream cadence without missing data.
    val cacheTtlHours: Int = 24,
    val forceOcrPreprocessing: Boolean = false,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun getCurrentSettings(): AppSettings
    suspend fun getApiKey(): String?

    suspend fun setApiKey(value: String?)
    suspend fun setCurrency(currency: Currency)
    suspend fun setConversionRate(rate: Double)
    suspend fun setCacheTtlHours(hours: Int)
    suspend fun setForceOcrPreprocessing(value: Boolean)

    /** Wipe every preference back to defaults. Part of the nuclear reset path. */
    suspend fun resetAll()
}
