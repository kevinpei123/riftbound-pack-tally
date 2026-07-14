package com.riftbound.packtally.feature.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riftbound.packtally.App
import com.riftbound.packtally.core.carddb.CardDbSync
import com.riftbound.packtally.core.currency.CurrencyRateRepository
import com.riftbound.packtally.core.pricing.CachedPricingRepository
import com.riftbound.packtally.core.pricing.QuotaState
import com.riftbound.packtally.core.pricing.QuotaTracker
import com.riftbound.packtally.core.settings.AppSettings
import com.riftbound.packtally.core.settings.Currency
import com.riftbound.packtally.core.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted as SS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
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
    data class CardDbResynced(val cardCount: Int) : SettingsEvent
    data class CardDbResyncFailed(val reason: String) : SettingsEvent
    data class ExchangeRateUpdated(val target: String) : SettingsEvent
    data class ExchangeRateFailed(val reason: String) : SettingsEvent
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val settingsRepository: SettingsRepository = app.settingsRepository
    private val cachedPricing: CachedPricingRepository = app.cachedPricing
    private val quotaTracker: QuotaTracker = app.quotaTracker
    private val cardDbSync: CardDbSync = app.cardDbSync
    private val currencyRates: CurrencyRateRepository = app.currencyRateRepository

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = AppSettings(),
        )

    val quota: StateFlow<QuotaState> = quotaTracker.state

    val useCachedOnly: StateFlow<Boolean> = quotaTracker.useCachedOnly

    private val _cacheSizeBytes = MutableStateFlow(0L)
    val cacheSizeBytes: StateFlow<Long> = _cacheSizeBytes.asStateFlow()

    val cardDbLastSyncedAt: StateFlow<java.time.Instant?> =
        cardDbSync.lastSyncedAt.stateIn(viewModelScope, SS.Eagerly, null)

    private val _cardCount = MutableStateFlow(0)
    val cardCount: StateFlow<Int> = _cardCount.asStateFlow()

    private val _cardDbSyncing = MutableStateFlow(false)
    val cardDbSyncing: StateFlow<Boolean> = _cardDbSyncing.asStateFlow()

    private val _exchangeRateRefreshing = MutableStateFlow(false)
    val exchangeRateRefreshing: StateFlow<Boolean> = _exchangeRateRefreshing.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    init {
        refreshCacheSize()
        viewModelScope.launch {
            runCatching { _cardCount.value = cardDbSync.cardCount() }
                .onFailure { Log.w(TAG, "Card count read failed", it) }
        }
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
        viewModelScope.launch {
            _exchangeRateRefreshing.value = true
            val result = currencyRates.setCurrencyAndRefresh(currency)
            _exchangeRateRefreshing.value = false
            emitExchangeRateResult(result)
        }
    }

    fun refreshExchangeRate() {
        if (_exchangeRateRefreshing.value) return
        _exchangeRateRefreshing.value = true
        viewModelScope.launch {
            val result = currencyRates.refreshNow()
            _exchangeRateRefreshing.value = false
            emitExchangeRateResult(result)
        }
    }

    fun setCacheTtlHours(hours: Int) {
        viewModelScope.launch { settingsRepository.setCacheTtlHours(hours) }
    }

    fun setForceOcrPreprocessing(value: Boolean) {
        viewModelScope.launch { settingsRepository.setForceOcrPreprocessing(value) }
    }

    fun setOcrDebugLogging(value: Boolean) {
        viewModelScope.launch { settingsRepository.setOcrDebugLogging(value) }
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

    /** Debug-only — wipes today's quota counter to zero. */
    fun resetQuotaCounter() {
        viewModelScope.launch {
            runCatching { quotaTracker.reset() }
                .onFailure { Log.e(TAG, "Quota reset failed", it) }
        }
    }

    fun setUseCachedOnly(value: Boolean) {
        quotaTracker.setUseCachedOnly(value)
    }

    fun resyncCardDatabase() {
        if (_cardDbSyncing.value) return
        _cardDbSyncing.value = true
        viewModelScope.launch {
            val outcome = runCatching { cardDbSync.runFullSync() }
            _cardDbSyncing.value = false
            outcome
                .onSuccess { count ->
                    _cardCount.value = count
                    _events.emit(SettingsEvent.CardDbResynced(count))
                }
                .onFailure { exc ->
                    Log.e(TAG, "Card DB resync failed", exc)
                    _events.emit(SettingsEvent.CardDbResyncFailed(exc.message ?: "resync failed"))
                }
        }
    }

    private suspend fun emitExchangeRateResult(result: CurrencyRateRepository.RefreshResult) {
        when (result) {
            is CurrencyRateRepository.RefreshResult.Fresh ->
                _events.emit(SettingsEvent.ExchangeRateUpdated(result.target))
            is CurrencyRateRepository.RefreshResult.Updated ->
                _events.emit(SettingsEvent.ExchangeRateUpdated(result.target))
            is CurrencyRateRepository.RefreshResult.Failed ->
                _events.emit(SettingsEvent.ExchangeRateFailed(result.reason))
        }
    }
}
