package com.riftbound.packtally.core.currency

import com.riftbound.packtally.core.settings.Currency
import com.riftbound.packtally.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

class CurrencyRateRepository(
    private val service: CurrencyRateService,
    private val settingsRepository: SettingsRepository,
    private val clock: () -> Instant = { Instant.now() },
    private val staleAfter: Duration = Duration.ofHours(18),
) {

    suspend fun refreshIfStale(): RefreshResult = withContext(Dispatchers.IO) {
        val settings = settingsRepository.getCurrentSettings()
        if (settings.currency == Currency.USD) {
            settingsRepository.setExchangeRate(
                rate = 1.0,
                base = Currency.USD.code,
                target = Currency.USD.code,
                fetchedAt = clock(),
                source = FrankfurterCurrencyRateService.LOCAL_SOURCE,
            )
            return@withContext RefreshResult.Updated(Currency.USD.code, 1.0)
        }
        val fetchedAt = settings.exchangeRateFetchedAt
        val isFresh = fetchedAt != null &&
            settings.exchangeRateBase == Currency.USD.code &&
            settings.exchangeRateTarget == settings.currency.code &&
            Duration.between(fetchedAt, clock()) < staleAfter
        if (isFresh) {
            RefreshResult.Fresh(settings.exchangeRateTarget, settings.usdToTargetRate)
        } else {
            refreshNow(settings.currency)
        }
    }

    suspend fun setCurrencyAndRefresh(currency: Currency): RefreshResult = withContext(Dispatchers.IO) {
        settingsRepository.setCurrency(currency)
        refreshNow(currency)
    }

    suspend fun refreshNow(target: Currency? = null): RefreshResult = withContext(Dispatchers.IO) {
        val currency = target ?: settingsRepository.getCurrentSettings().currency
        val result = runCatching { service.latestUsdRate(currency) }
        result.fold(
            onSuccess = { rate ->
                settingsRepository.setExchangeRate(
                    rate = rate.rate,
                    base = rate.base,
                    target = rate.target,
                    fetchedAt = rate.fetchedAt,
                    source = rate.source,
                )
                RefreshResult.Updated(rate.target, rate.rate)
            },
            onFailure = { exc ->
                val message = exc.message ?: "Exchange-rate refresh failed"
                settingsRepository.setExchangeRateWarning(message)
                RefreshResult.Failed(message)
            },
        )
    }

    sealed interface RefreshResult {
        data class Fresh(val target: String, val rate: Double) : RefreshResult
        data class Updated(val target: String, val rate: Double) : RefreshResult
        data class Failed(val reason: String) : RefreshResult
    }
}
