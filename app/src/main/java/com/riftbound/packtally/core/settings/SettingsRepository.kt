package com.riftbound.packtally.core.settings

import kotlinx.coroutines.flow.Flow
import java.time.Instant

enum class Currency(val symbol: String, val code: String) {
    USD(symbol = "$", code = "USD"),
    AUD(symbol = "A$", code = "AUD"),
    NZD(symbol = "NZ$", code = "NZD"),
    CAD(symbol = "C$", code = "CAD"),
    EUR(symbol = "EUR ", code = "EUR"),
    GBP(symbol = "GBP ", code = "GBP"),
    BGN(symbol = "BGN ", code = "BGN"),
    JPY(symbol = "JPY ", code = "JPY"),
    CNY(symbol = "CNY ", code = "CNY"),
    HKD(symbol = "HK$", code = "HKD"),
    SGD(symbol = "S$", code = "SGD"),
    KRW(symbol = "KRW ", code = "KRW"),
    INR(symbol = "INR ", code = "INR"),
    IDR(symbol = "IDR ", code = "IDR"),
    MYR(symbol = "MYR ", code = "MYR"),
    PHP(symbol = "PHP ", code = "PHP"),
    THB(symbol = "THB ", code = "THB"),
    CHF(symbol = "CHF ", code = "CHF"),
    SEK(symbol = "SEK ", code = "SEK"),
    NOK(symbol = "NOK ", code = "NOK"),
    DKK(symbol = "DKK ", code = "DKK"),
    PLN(symbol = "PLN ", code = "PLN"),
    CZK(symbol = "CZK ", code = "CZK"),
    HUF(symbol = "HUF ", code = "HUF"),
    RON(symbol = "RON ", code = "RON"),
    TRY(symbol = "TRY ", code = "TRY"),
    ILS(symbol = "ILS ", code = "ILS"),
    ZAR(symbol = "ZAR ", code = "ZAR"),
    BRL(symbol = "R$", code = "BRL"),
    MXN(symbol = "MX$", code = "MXN"),
    ISK(symbol = "ISK ", code = "ISK"),
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
