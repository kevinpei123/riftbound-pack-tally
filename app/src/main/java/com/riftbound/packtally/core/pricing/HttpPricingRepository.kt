package com.riftbound.packtally.core.pricing

import com.riftbound.packtally.core.settings.SettingsRepository
import com.riftbound.packtally.model.RiftboundCard
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.time.Instant

private const val DEFAULT_BASE_URL = "https://api.tcgapi.dev/v1/"
private const val GAME_SLUG = "riftbound"

private const val PRINTING_NORMAL = "Normal"
private const val PRINTING_FOIL = "Foil"
private const val PRINTING_SIGNATURE = "Signature"

class HttpPricingRepository(
    private val settings: SettingsRepository,
    baseUrl: String = DEFAULT_BASE_URL,
    okHttpClient: OkHttpClient = defaultOkHttpClient(),
) : PricingRepository {

    private val api: TcgApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(TcgApi::class.java)

    override suspend fun price(
        card: RiftboundCard,
        foil: Boolean,
        signature: Boolean,
    ): Result<CardPrice> = runCatching {
        val apiKey = settings.getApiKey()
            ?: error("Missing tcgapi.dev API key — set it in Settings")
        val printing = printingFor(foil, signature)
        val query = "${card.setCode}-${card.collectorNumber}"

        val response = api.search(
            apiKey = apiKey,
            q = query,
            game = GAME_SLUG,
            printing = printing,
        )

        val match = response.data.firstOrNull()
            ?: error("No tcgapi.dev result for $query ($printing)")

        val lastUpdated = match.lastUpdatedAt
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: error("tcgapi.dev response missing last_updated_at for $query")

        CardPrice(
            marketPrice = match.marketPrice,
            lowPrice = match.lowPrice,
            midPrice = match.medianPrice,
            highPrice = match.lowestWithShipping,
            currency = "USD",
            lastUpdated = lastUpdated,
        )
    }

    private fun printingFor(foil: Boolean, signature: Boolean): String = when {
        signature -> PRINTING_SIGNATURE
        foil -> PRINTING_FOIL
        else -> PRINTING_NORMAL
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
            )
            .build()
    }
}

private interface TcgApi {
    /**
     * tcgapi.dev's documented entry point for single-card lookup with prices embedded.
     * Auth is via `X-API-Key` per their quickstart.
     */
    @GET("search")
    suspend fun search(
        @Header("X-API-Key") apiKey: String,
        @Query("q") q: String,
        @Query("game") game: String,
        @Query("printing") printing: String,
    ): SearchResponse
}

@Serializable
private data class SearchResponse(
    val data: List<TcgPriceDto> = emptyList(),
)

@Serializable
private data class TcgPriceDto(
    val id: Long = 0,
    @SerialName("market_price") val marketPrice: Double = 0.0,
    @SerialName("low_price") val lowPrice: Double = 0.0,
    @SerialName("median_price") val medianPrice: Double = 0.0,
    @SerialName("lowest_with_shipping") val lowestWithShipping: Double = 0.0,
    @SerialName("last_updated_at") val lastUpdatedAt: String? = null,
)
