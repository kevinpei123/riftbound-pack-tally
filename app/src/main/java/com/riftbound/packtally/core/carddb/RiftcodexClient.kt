package com.riftbound.packtally.core.carddb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Riftcodex card-data client. No auth, no rate limit.
 *
 * Base URL: https://api.riftcodex.com
 * Endpoints used:
 *   GET /cards?size=100&page=N           — paginated full list
 *   GET /cards/riftbound/{id}            — lookup by Riftbound ID
 *   GET /cards/tcgplayer/{tcgplayer_id}  — reverse lookup
 *
 * Pagination: max size=100, walk page=1..N until response has < size items.
 */
class RiftcodexClient(
    baseUrl: String = DEFAULT_BASE_URL,
    okHttpClient: OkHttpClient = defaultOkHttpClient(),
) {
    private val api: RiftcodexApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(RiftcodexApi::class.java)

    /** Walks every page (size=[PAGE_SIZE]) until exhausted. Returns the flattened card list. */
    suspend fun fetchAllCards(): List<RiftcodexCardDto> {
        val out = mutableListOf<RiftcodexCardDto>()
        var page = 1
        while (true) {
            val resp = api.listCards(size = PAGE_SIZE, page = page)
            val items = resp.data ?: resp.cards ?: emptyList()
            out.addAll(items)
            if (items.size < PAGE_SIZE) break
            page += 1
            // Hard ceiling at 50 pages (5000 cards) as a safety net against
            // pagination loops on a misbehaving server. Riftbound has ~1k cards
            // current; we shouldn't need anywhere near this.
            if (page > 50) break
        }
        return out
    }

    suspend fun lookupByRiftboundId(id: String): RiftcodexCardDto? =
        runCatching { api.lookupByRiftboundId(id) }.getOrNull()

    suspend fun lookupByTcgplayerId(tcgplayerId: String): RiftcodexCardDto? =
        runCatching { api.lookupByTcgplayerId(tcgplayerId) }.getOrNull()

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.riftcodex.com/"
        const val PAGE_SIZE = 100

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC },
            )
            .build()
    }
}

private interface RiftcodexApi {
    @GET("cards")
    suspend fun listCards(
        @Query("size") size: Int,
        @Query("page") page: Int,
    ): RiftcodexListResponse

    @GET("cards/riftbound/{id}")
    suspend fun lookupByRiftboundId(@Path("id") id: String): RiftcodexCardDto

    @GET("cards/tcgplayer/{id}")
    suspend fun lookupByTcgplayerId(@Path("id") id: String): RiftcodexCardDto
}

@Serializable
data class RiftcodexListResponse(
    // Riftcodex's list endpoint shape isn't documented precisely; support
    // both `data` and `cards` envelopes so this works regardless.
    val data: List<RiftcodexCardDto>? = null,
    val cards: List<RiftcodexCardDto>? = null,
    val total: Int? = null,
    val page: Int? = null,
)

@Serializable
data class RiftcodexCardDto(
    val id: String,
    val name: String,
    @SerialName("riftbound_id") val riftboundId: String? = null,
    @SerialName("tcgplayer_id") val tcgplayerId: String? = null,
    @SerialName("collector_number") val collectorNumber: Int? = null,
    val attributes: RiftcodexAttributes? = null,
    val classification: RiftcodexClassification? = null,
    val text: RiftcodexText? = null,
    val set: RiftcodexSet? = null,
    val media: RiftcodexMedia? = null,
    val tags: List<String>? = null,
    val orientation: String? = null,
    val metadata: RiftcodexMetadata? = null,
)

@Serializable
data class RiftcodexAttributes(
    val energy: Int? = null,
    val might: Int? = null,
    val power: Int? = null,
)

@Serializable
data class RiftcodexClassification(
    val type: String? = null,
    val supertype: String? = null,
    val rarity: String? = null,
    val domain: List<String>? = null,
)

@Serializable
data class RiftcodexText(
    val rich: String? = null,
    val plain: String? = null,
    val flavour: String? = null,
)

@Serializable
data class RiftcodexSet(
    @SerialName("set_id") val setId: String? = null,
    val label: String? = null,
)

@Serializable
data class RiftcodexMedia(
    @SerialName("image_url") val imageUrl: String? = null,
    val artist: String? = null,
    @SerialName("accessibility_text") val accessibilityText: String? = null,
)

@Serializable
data class RiftcodexMetadata(
    @SerialName("clean_name") val cleanName: String? = null,
    @SerialName("updated_on") val updatedOn: String? = null,
    @SerialName("alternate_art") val alternateArt: Boolean = false,
    val overnumbered: Boolean = false,
    val signature: Boolean = false,
)
