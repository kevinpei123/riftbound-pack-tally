package com.riftbound.packtally.core.currency

import com.riftbound.packtally.core.settings.AppSettings
import com.riftbound.packtally.core.settings.Currency
import com.riftbound.packtally.core.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class CurrencyRateRepositoryTest {

    private val now = Instant.parse("2026-05-27T00:00:00Z")

    @Test
    fun `successful refresh stores latest rate metadata`() = runBlocking {
        val settings = FakeSettingsRepository(AppSettings(currency = Currency.AUD))
        val service = FakeCurrencyService(rate = 1.52)
        val repo = CurrencyRateRepository(service, settings, clock = { now })

        val result = repo.refreshNow(Currency.AUD)

        assertTrue(result is CurrencyRateRepository.RefreshResult.Updated)
        val saved = settings.getCurrentSettings()
        assertEquals(1.52, saved.usdToTargetRate)
        assertEquals("USD", saved.exchangeRateBase)
        assertEquals("AUD", saved.exchangeRateTarget)
        assertEquals(now, saved.exchangeRateFetchedAt)
        assertEquals("FakeRates", saved.exchangeRateSource)
        assertEquals(null, saved.exchangeRateWarning)
    }

    @Test
    fun `failed refresh keeps cached rate and records warning`() = runBlocking {
        val settings = FakeSettingsRepository(
            AppSettings(
                currency = Currency.AUD,
                usdToTargetRate = 1.4,
                exchangeRateFetchedAt = now.minus(Duration.ofHours(30)),
                exchangeRateSource = "Frankfurter",
            ),
        )
        val repo = CurrencyRateRepository(FailingCurrencyService, settings, clock = { now })

        val result = repo.refreshNow(Currency.AUD)

        assertTrue(result is CurrencyRateRepository.RefreshResult.Failed)
        val saved = settings.getCurrentSettings()
        assertEquals(1.4, saved.usdToTargetRate)
        assertEquals("offline", saved.exchangeRateWarning)
    }

    @Test
    fun `fresh cache avoids network call`() = runBlocking {
        val settings = FakeSettingsRepository(
            AppSettings(
                currency = Currency.AUD,
                usdToTargetRate = 1.5,
                exchangeRateBase = "USD",
                exchangeRateTarget = "AUD",
                exchangeRateFetchedAt = now.minus(Duration.ofHours(2)),
                exchangeRateSource = "Frankfurter",
            ),
        )
        val service = FakeCurrencyService(rate = 9.9)
        val repo = CurrencyRateRepository(service, settings, clock = { now })

        val result = repo.refreshIfStale()

        assertTrue(result is CurrencyRateRepository.RefreshResult.Fresh)
        assertEquals(0, service.calls)
        assertEquals(1.5, settings.getCurrentSettings().usdToTargetRate)
    }

    @Test
    fun `changing currency fetches target currency rate`() = runBlocking {
        val settings = FakeSettingsRepository(AppSettings(currency = Currency.AUD))
        val service = FakeCurrencyService(rate = 0.91)
        val repo = CurrencyRateRepository(service, settings, clock = { now })

        repo.setCurrencyAndRefresh(Currency.EUR)

        val saved = settings.getCurrentSettings()
        assertEquals(Currency.EUR, saved.currency)
        assertEquals("EUR", saved.exchangeRateTarget)
        assertEquals(0.91, saved.usdToTargetRate)
    }

    @Test
    fun `Frankfurter v2 array response parses rate row`() {
        val element = Json.parseToJsonElement(
            """
            [
              { "date": "2026-05-27", "base": "USD", "quote": "AUD", "rate": 1.53 }
            ]
            """.trimIndent(),
        )

        val rate = FrankfurterCurrencyRateService.parseRateResponse(element, Currency.AUD, now)

        assertEquals(1.53, rate.rate)
        assertEquals("USD", rate.base)
        assertEquals("AUD", rate.target)
        assertEquals(now, rate.fetchedAt)
    }

    private class FakeCurrencyService(private val rate: Double) : CurrencyRateService {
        var calls = 0

        override suspend fun latestUsdRate(target: Currency): ExchangeRate {
            calls += 1
            return ExchangeRate(
                rate = rate,
                base = Currency.USD.code,
                target = target.code,
                fetchedAt = Instant.parse("2026-05-27T00:00:00Z"),
                source = "FakeRates",
            )
        }
    }

    private object FailingCurrencyService : CurrencyRateService {
        override suspend fun latestUsdRate(target: Currency): ExchangeRate = error("offline")
    }

    private class FakeSettingsRepository(initial: AppSettings) : SettingsRepository {
        private val flow = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = flow

        override suspend fun getCurrentSettings(): AppSettings = flow.value
        override suspend fun getApiKey(): String? = flow.value.apiKey

        override suspend fun setApiKey(value: String?) {
            flow.value = flow.value.copy(apiKey = value)
        }

        override suspend fun setCurrency(currency: Currency) {
            flow.value = flow.value.copy(currency = currency)
        }

        override suspend fun setConversionRate(rate: Double) {
            flow.value = flow.value.copy(usdToTargetRate = rate)
        }

        override suspend fun setExchangeRate(
            rate: Double,
            base: String,
            target: String,
            fetchedAt: Instant,
            source: String,
        ) {
            flow.value = flow.value.copy(
                usdToTargetRate = rate,
                exchangeRateBase = base,
                exchangeRateTarget = target,
                exchangeRateFetchedAt = fetchedAt,
                exchangeRateSource = source,
                exchangeRateWarning = null,
            )
        }

        override suspend fun setExchangeRateWarning(message: String?) {
            flow.value = flow.value.copy(exchangeRateWarning = message)
        }

        override suspend fun setCacheTtlHours(hours: Int) {
            flow.value = flow.value.copy(cacheTtlHours = hours)
        }

        override suspend fun setForceOcrPreprocessing(value: Boolean) {
            flow.value = flow.value.copy(forceOcrPreprocessing = value)
        }

        override suspend fun setOcrDebugLogging(value: Boolean) {
            flow.value = flow.value.copy(ocrDebugLogging = value)
        }

        override suspend fun resetAll() {
            flow.value = AppSettings()
        }
    }
}
