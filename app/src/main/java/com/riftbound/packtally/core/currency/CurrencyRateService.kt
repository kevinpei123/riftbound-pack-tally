package com.riftbound.packtally.core.currency

import com.riftbound.packtally.core.settings.Currency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.Instant

data class ExchangeRate(
    val rate: Double,
    val base: String,
    val target: String,
    val fetchedAt: Instant,
    val source: String,
)

interface CurrencyRateService {
    suspend fun latestUsdRate(target: Currency): ExchangeRate
}

class FrankfurterCurrencyRateService(
    baseUrl: String = DEFAULT_BASE_URL,
    okHttpClient: OkHttpClient = defaultOkHttpClient(),
    private val clock: () -> Instant = { Instant.now() },
) : CurrencyRateService {

    private val api: FrankfurterApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(FrankfurterApi::class.java)

    override suspend fun latestUsdRate(target: Currency): ExchangeRate {
        if (target == Currency.USD) {
            return ExchangeRate(
                rate = 1.0,
                base = Currency.USD.code,
                target = Currency.USD.code,
                fetchedAt = clock(),
                source = LOCAL_SOURCE,
            )
        }
        val response = api.latest(base = Currency.USD.code, quotes = target.code)
        return parseRateResponse(response, target, clock())
    }

    private interface FrankfurterApi {
        @GET("v2/rates")
        suspend fun latest(
            @Query("base") base: String,
            @Query("quotes") quotes: String,
        ): JsonElement
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.frankfurter.dev/"
        const val SOURCE = "Frankfurter"
        const val LOCAL_SOURCE = "Local USD"

        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        internal fun parseRateResponse(
            element: JsonElement,
            target: Currency,
            fetchedAt: Instant,
        ): ExchangeRate {
            val targetCode = target.code
            val row = when (element) {
                is JsonArray -> element.jsonArray
                    .map { it.jsonObject }
                    .firstOrNull {
                        it["quote"]?.jsonPrimitive?.contentOrNull.equals(targetCode, ignoreCase = true)
                    }
                is JsonObject -> element
                else -> null
            } ?: error("Frankfurter did not return $targetCode")

            row["rate"]?.jsonPrimitive?.doubleOrNull?.let { rate ->
                return ExchangeRate(
                    rate = rate,
                    base = row["base"]?.jsonPrimitive?.contentOrNull ?: Currency.USD.code,
                    target = row["quote"]?.jsonPrimitive?.contentOrNull ?: targetCode,
                    fetchedAt = fetchedAt,
                    source = SOURCE,
                )
            }

            val rates = row["rates"]?.jsonObject
            val legacyRate = rates?.get(targetCode)?.jsonPrimitive?.doubleOrNull
                ?: error("Frankfurter did not return $targetCode")
            return ExchangeRate(
                rate = legacyRate,
                base = row["base"]?.jsonPrimitive?.contentOrNull ?: Currency.USD.code,
                target = targetCode,
                fetchedAt = fetchedAt,
                source = SOURCE,
            )
        }

        private fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
            )
            .build()
    }
}
