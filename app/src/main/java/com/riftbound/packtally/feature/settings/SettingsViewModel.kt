package com.riftbound.packtally.feature.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riftbound.packtally.App
import com.riftbound.packtally.core.pricing.CachedPricingRepository
import com.riftbound.packtally.core.settings.AppSettings
import com.riftbound.packtally.core.settings.Currency
import com.riftbound.packtally.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "SettingsViewModel"

sealed interface SettingsEvent {
    data object CacheCleared : SettingsEvent
    data object ResetComplete : SettingsEvent
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val settingsRepository: SettingsRepository = app.settingsRepository
    private val cachedPricing: CachedPricingRepository = app.cachedPricing

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AppSettings(),
        )

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    init {
        refreshCacheSize()
    }

    fun refreshCacheSize() {
        viewModelScope.launch(Dispatchers.IO) {
            _cacheSizeBytes.value = cachedPricing.cacheSizeBytes()
        }
    }

    fun setApiKey(key: String?) {
        viewModelScope.launch {
            settingsRepository.setApiKey(key?.takeIf { it.isNotBlank() })
        }
    }

    fun setCurrency(currency: Currency) {
        viewModelScope.launch { settingsRepository.setCurrency(currency) }
    }

    fun setConversionRate(rate: Double) {
        viewModelScope.launch { settingsRepository.setConversionRate(rate) }
    }

    fun setCacheTtlHours(hours: Int) {
        viewModelScope.launch { settingsRepository.setCacheTtlHours(hours) }
    }

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { cachedPricing.clearCache() }
            _cacheSizeBytes.value = 0L
            _events.emit(SettingsEvent.CacheCleared)
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            runCatching { app.resetAll() }
                .onFailure { Log.e(TAG, "Reset failed", it) }
            _cacheSizeBytes.value = 0L
            _events.emit(SettingsEvent.ResetComplete)
        }
    }
}
