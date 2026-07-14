package com.riftbound.packtally.core.pricing

import com.riftbound.packtally.BuildConfig
import com.riftbound.packtally.core.settings.SettingsRepository
import com.riftbound.packtally.model.Variant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * JustTCG pricing API client. Batch-first.
 *
 * Base URL: https://api.justtcg.com/v1
 * Auth: header `X-API-Key: tcg_…`
 *
 * Endpoint we use:
 *   POST /cards   — body = array of { tcgplayerId, condition?, printing? }
 *
 * Free tier batch limit: 20 items per POST. Caller (JustTcgPricingRepository)
 * splits larger batches.
 */
interface JustTcgApiClient {
    suspend fun postCards(items: List<JustTcgRequestItem>): JustTcgBatchResponse
    fun filterFor(variant: Variant): VariantFilter
}

class JustTcgClient(
    private val settings: SettingsRepository,
    baseUrl: String = DEFAULT_BASE_URL,
    okHttpClient: OkHttpClient = defaultOkHttpClient(),
) : JustTcgApiClient {
    private val api: JustTcgApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(JustTcgApi::class.java)

    override suspend fun postCards(items: List<JustTcgRequestItem>): JustTcgBatchResponse {
        require(items.size <= MAX_BATCH) {
            "JustTCG free tier accepts at most $MAX_BATCH items per POST; got ${items.size}"
        }
        val apiKey = settings.getApiKey()
            ?: error("Missing JustTCG API key — set it in Settings")
        return api.postCards(apiKey, items)
    }

    /** Variant → JustTCG (printing, condition) per the documented variant mapping. */
    override fun filterFor(variant: Variant): VariantFilter = when (variant) {
        // Signature reads as Foil because JustTCG doesn't have a separate
        // "Signature" printing key. Riftbound signature cards are numbered +
        // signed foils; the foil market price is a fair lower bound. Premium
        // for the actual signature isn't captured by raw market data and
        // would need a manual override (not implemented).
        Variant.STANDARD -> VariantFilter(printing = "Normal", condition = "Near Mint")
        Variant.FOIL -> VariantFilter(printing = "Foil", condition = "Near Mint")
        Variant.SIGNATURE -> VariantFilter(printing = "Foil", condition = "Near Mint")
    }

    companion object {
        const val MAX_BATCH: Int = 20
        const val DEFAULT_BASE_URL: String = "https://api.justtcg.com/v1/"

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .apply {
                // Only log in debug builds: keeps per-request logging overhead out
                // of release and request metadata out of logcat on user devices.
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor().apply {
                            level = HttpLoggingInterceptor.Level.BASIC
                        },
                    )
                }
            }
            .build()
    }
}

data class VariantFilter(val printing: String, val condition: String)

private interface JustTcgApi {
    @POST("cards")
    suspend fun postCards(
        @Header("X-API-Key") apiKey: String,
        @Body body: List<JustTcgRequestItem>,
    ): JustTcgBatchResponse
}

@Serializable
data class JustTcgRequestItem(
    val tcgplayerId: String,
    val condition: String? = null,
    val printing: String? = null,
)

@Serializable
data class JustTcgBatchResponse(
    val data: List<JustTcgCard> = emptyList(),
    @SerialName("_metadata") val metadata: JustTcgMetadata? = null,
)

@Serializable
data class JustTcgCard(
    val tcgplayerId: String,
    val name: String? = null,
    val variants: List<JustTcgVariant> = emptyList(),
)

@Serializable
data class JustTcgVariant(
    val id: String? = null,
    val condition: String? = null,
    val printing: String? = null,
    val language: String? = null,
    val price: Double? = null,
    @SerialName("lowPrice") val lowPrice: Double? = null,
    @SerialName("midPrice") val midPrice: Double? = null,
    @SerialName("highPrice") val highPrice: Double? = null,
    @SerialName("lastUpdated") val lastUpdated: String? = null,
    @SerialName("priceChange7d") val priceChange7d: Double? = null,
)

/**
 * Server-side quota hints. Every JustTCG response carries these — we use them
 * to correct the local [QuotaTracker] state via [QuotaTracker.applyServerHints].
 */
@Serializable
data class JustTcgMetadata(
    @SerialName("apiRequestsRemaining") val monthlyRemaining: Int? = null,
    @SerialName("apiDailyRequestsRemaining") val dailyRemaining: Int? = null,
    @SerialName("apiRateLimit") val rateLimit: Int? = null,
)
