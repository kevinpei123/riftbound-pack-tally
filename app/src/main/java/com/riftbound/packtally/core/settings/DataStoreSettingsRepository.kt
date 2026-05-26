package com.riftbound.packtally.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            apiKey = prefs[Keys.ApiKey],
            currency = prefs[Keys.Currency]
                ?.let { runCatching { Currency.valueOf(it) }.getOrNull() }
                ?: Currency.AUD,
            usdToTargetRate = prefs[Keys.ConversionRate] ?: 1.55,
            cacheTtlHours = prefs[Keys.CacheTtlHours] ?: 6,
            forceOcrPreprocessing = prefs[Keys.ForcePreprocessing] ?: false,
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

    override suspend fun setCacheTtlHours(hours: Int) {
        dataStore.edit { it[Keys.CacheTtlHours] = hours }
    }

    override suspend fun setForceOcrPreprocessing(value: Boolean) {
        dataStore.edit { it[Keys.ForcePreprocessing] = value }
    }

    override suspend fun resetAll() {
        dataStore.edit { it.clear() }
    }

    private object Keys {
        val ApiKey = stringPreferencesKey("api_key")
        val Currency = stringPreferencesKey("currency")
        val ConversionRate = doublePreferencesKey("conversion_rate")
        val CacheTtlHours = intPreferencesKey("cache_ttl_hours")
        val ForcePreprocessing = booleanPreferencesKey("force_ocr_preprocessing")
    }
}
