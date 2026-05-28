package com.riftbound.packtally.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            apiKey = prefs[Keys.ApiKey],
            currency = prefs[Keys.Currency]
                ?.let { runCatching { Currency.valueOf(it) }.getOrNull() }
                ?: Currency.AUD,
            usdToTargetRate = prefs[Keys.ConversionRate] ?: 1.0,
            exchangeRateBase = prefs[Keys.ExchangeRateBase] ?: "USD",
            exchangeRateTarget = prefs[Keys.ExchangeRateTarget]
                ?: (
                    prefs[Keys.Currency]
                        ?.let { runCatching { Currency.valueOf(it) }.getOrNull()?.code }
                        ?: Currency.AUD.code
                    ),
            exchangeRateFetchedAt = prefs[Keys.ExchangeRateFetchedAt]?.let(Instant::ofEpochMilli),
            exchangeRateSource = prefs[Keys.ExchangeRateSource],
            exchangeRateWarning = prefs[Keys.ExchangeRateWarning],
            cacheTtlHours = prefs[Keys.CacheTtlHours] ?: 6,
            forceOcrPreprocessing = prefs[Keys.ForcePreprocessing] ?: false,
            ocrDebugLogging = prefs[Keys.OcrDebugLogging] ?: false,
        )
    }

    override suspend fun getCurrentSettings(): AppSettings = settings.first()

    override suspend fun getApiKey(): String? = dataStore.data.first()[Keys.ApiKey]

    override suspend fun setApiKey(value: String?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(Keys.ApiKey)
            else prefs[Keys.ApiKey] = value
        }
    }

    override suspend fun setCurrency(currency: Currency) {
        dataStore.edit { it[Keys.Currency] = currency.name }
    }

    override suspend fun setConversionRate(rate: Double) {
        dataStore.edit { it[Keys.ConversionRate] = rate }
    }

    override suspend fun setExchangeRate(
        rate: Double,
        base: String,
        target: String,
        fetchedAt: Instant,
        source: String,
    ) {
        dataStore.edit { prefs ->
            prefs[Keys.ConversionRate] = rate
            prefs[Keys.ExchangeRateBase] = base
            prefs[Keys.ExchangeRateTarget] = target
            prefs[Keys.ExchangeRateFetchedAt] = fetchedAt.toEpochMilli()
            prefs[Keys.ExchangeRateSource] = source
            prefs.remove(Keys.ExchangeRateWarning)
        }
    }

    override suspend fun setExchangeRateWarning(message: String?) {
        dataStore.edit { prefs ->
            if (message == null) prefs.remove(Keys.ExchangeRateWarning)
            else prefs[Keys.ExchangeRateWarning] = message
        }
    }

    override suspend fun setCacheTtlHours(hours: Int) {
        dataStore.edit { it[Keys.CacheTtlHours] = hours }
    }

    override suspend fun setForceOcrPreprocessing(value: Boolean) {
        dataStore.edit { it[Keys.ForcePreprocessing] = value }
    }

    override suspend fun setOcrDebugLogging(value: Boolean) {
        dataStore.edit { it[Keys.OcrDebugLogging] = value }
    }

    override suspend fun resetAll() {
        dataStore.edit { it.clear() }
    }

    private object Keys {
        val ApiKey = stringPreferencesKey("api_key")
        val Currency = stringPreferencesKey("currency")
        val ConversionRate = doublePreferencesKey("conversion_rate")
        val ExchangeRateBase = stringPreferencesKey("exchange_rate_base")
        val ExchangeRateTarget = stringPreferencesKey("exchange_rate_target")
        val ExchangeRateFetchedAt = longPreferencesKey("exchange_rate_fetched_at")
        val ExchangeRateSource = stringPreferencesKey("exchange_rate_source")
        val ExchangeRateWarning = stringPreferencesKey("exchange_rate_warning")
        val CacheTtlHours = intPreferencesKey("cache_ttl_hours")
        val ForcePreprocessing = booleanPreferencesKey("force_ocr_preprocessing")
        val OcrDebugLogging = booleanPreferencesKey("ocr_debug_logging")
    }
}
