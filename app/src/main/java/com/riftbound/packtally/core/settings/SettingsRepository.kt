package com.riftbound.packtally.core.settings

import kotlinx.coroutines.flow.Flow
import java.time.Instant

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
    val usdToTargetRate: Double = 1.0,
    val exchangeRateBase: String = "USD",
    val exchangeRateTarget: String = currency.code,
    val exchangeRateFetchedAt: Instant? = null,
    val exchangeRateSource: String? = null,
    val exchangeRateWarning: String? = null,
    // JustTCG refreshes Riftbound pricing every ~4h, so a 6h window matches
    // their cadence comfortably while reusing cached entries within a session.
    val cacheTtlHours: Int = 6,
    val forceOcrPreprocessing: Boolean = false,
    val ocrDebugLogging: Boolean = false,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun getCurrentSettings(): AppSettings
    suspend fun getApiKey(): String?

    suspend fun setApiKey(value: String?)
    suspend fun setCurrency(currency: Currency)
    suspend fun setConversionRate(rate: Double)
    suspend fun setExchangeRate(
        rate: Double,
        base: String,
        target: String,
        fetchedAt: Instant,
        source: String,
    )
    suspend fun setExchangeRateWarning(message: String?)
    suspend fun setCacheTtlHours(hours: Int)
    suspend fun setForceOcrPreprocessing(value: Boolean)
    suspend fun setOcrDebugLogging(value: Boolean)

    /** Wipe every preference back to defaults. Part of the nuclear reset path. */
    suspend fun resetAll()
}
