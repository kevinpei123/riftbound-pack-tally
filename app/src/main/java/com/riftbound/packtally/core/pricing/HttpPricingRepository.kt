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
import retrofit2.http.Path
import retrofit2.http.Query
import java.time.Instant

private const val DEFAULT_BASE_URL = "https://api.tcgapi.dev/v1/"

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
        val dto = api.getPrice(
            authHeader = "Bearer $apiKey",
            cardId = card.id,
            foil = foil,
            signature = signature,
        )
        CardPrice(
            marketPrice = dto.marketPrice,
            lowPrice = dto.lowPrice,
            midPrice = dto.midPrice,
            highPrice = dto.highPrice,
            currency = dto.currency,
            lastUpdated = Instant.parse(dto.lastUpdated),
        )
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
    @GET("cards/{id}/price")
    suspend fun getPrice(
        @Header("Authorization") authHeader: String,
        @Path("id") cardId: String,
        @Query("foil") foil: Boolean,
        @Query("signature") signature: Boolean,
    ): PriceDto
}

@Serializable
private data class PriceDto(
    @SerialName("marketPrice") val marketPrice: Double,
    @SerialName("lowPrice") val lowPrice: Double,
    @SerialName("midPrice") val midPrice: Double,
    @SerialName("highPrice") val highPrice: Double,
    @SerialName("currency") val currency: String = "USD",
    @SerialName("lastUpdated") val lastUpdated: String,
)
