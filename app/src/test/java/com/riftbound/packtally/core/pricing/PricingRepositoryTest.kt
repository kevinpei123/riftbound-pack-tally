package com.riftbound.packtally.core.pricing

import com.riftbound.packtally.model.CardPrice
import com.riftbound.packtally.model.Variant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class PricingRepositoryTest {

    @Test
    fun `fourteen card pack is one post and chunks stay within twenty`() = runBlocking {
        val client = FakeJustTcgClient()
        val repo = JustTcgPricingRepository(client)
        val requests = (1..14).map { PriceRequest("$it", Variant.STANDARD) }

        val results = repo.priceMany(requests)

        assertEquals(1, client.posts.size)
        assertTrue(client.posts.all { it.size <= 20 })
        assertEquals(14, results.size)
    }

    @Test
    fun `one hundred loose cards is five posts`() = runBlocking {
        val client = FakeJustTcgClient()
        val repo = JustTcgPricingRepository(client)
        val requests = (1..100).map { PriceRequest("$it", Variant.STANDARD) }

        repo.priceMany(requests)

        assertEquals(listOf(20, 20, 20, 20, 20), client.posts.map { it.size })
    }

    @Test
    fun `pricing batch boundaries split at twenty`() = runBlocking {
        val cases = listOf(
            0 to emptyList(),
            1 to listOf(1),
            18 to listOf(18),
            20 to listOf(20),
            21 to listOf(20, 1),
            46 to listOf(20, 20, 6),
            100 to listOf(20, 20, 20, 20, 20),
        )

        cases.forEach { (count, expected) ->
            val client = FakeJustTcgClient()
            val repo = JustTcgPricingRepository(client)
            repo.priceMany((1..count).map { PriceRequest("$it", Variant.STANDARD) })

            assertEquals(expected, client.posts.map { it.size }, "count=$count")
        }
    }

    @Test
    fun `same tcgplayer id with different variants gets different cached and network results`() = runBlocking {
        val client = FakeJustTcgClient()
        val repo = JustTcgPricingRepository(client)
        val standard = PriceRequest("42", Variant.STANDARD)
        val foil = PriceRequest("42", Variant.FOIL)

        val results = repo.priceMany(listOf(standard, foil))

        assertEquals(0.5, results.getValue(standard).getOrThrow().marketPrice)
        assertEquals(2.0, results.getValue(foil).getOrThrow().marketPrice)
    }

    @Test
    fun `cache hit does not call delegate and cache miss does`() = runBlocking {
        val delegate = CountingPricingRepository()
        val cache = CachedPricingRepository(
            delegate = delegate,
            cacheDir = tempDir.toFile(),
            ttlProvider = { Duration.ofHours(6) },
            clock = { Instant.parse("2026-05-26T00:00:00Z") },
        )
        val request = PriceRequest("cached", Variant.STANDARD)

        assertEquals(1.23, cache.priceMany(listOf(request)).getValue(request).getOrThrow().marketPrice)
        assertEquals(1, delegate.calls)
        assertEquals(1.23, cache.priceMany(listOf(request)).getValue(request).getOrThrow().marketPrice)
        assertEquals(1, delegate.calls)
    }

    @TempDir
    lateinit var tempDir: Path

    private class FakeJustTcgClient : JustTcgApiClient {
        val posts = mutableListOf<List<JustTcgRequestItem>>()

        override suspend fun postCards(items: List<JustTcgRequestItem>): JustTcgBatchResponse {
            posts += items
            return JustTcgBatchResponse(
                data = items.map { item ->
                    JustTcgCard(
                        tcgplayerId = item.tcgplayerId,
                        variants = listOf(
                            JustTcgVariant(
                                condition = "Near Mint",
                                printing = "Normal",
                                language = "English",
                                price = 0.5,
                                lastUpdated = "2026-05-26T00:00:00Z",
                            ),
                            JustTcgVariant(
                                condition = "Near Mint",
                                printing = "Foil",
                                language = "English",
                                price = 2.0,
                                lastUpdated = "2026-05-26T00:00:00Z",
                            ),
                        ),
                    )
                },
            )
        }

        override fun filterFor(variant: Variant): VariantFilter = when (variant) {
            Variant.STANDARD -> VariantFilter("Normal", "Near Mint")
            Variant.FOIL -> VariantFilter("Foil", "Near Mint")
            Variant.SIGNATURE -> VariantFilter("Foil", "Near Mint")
        }
    }

    private class CountingPricingRepository : PricingRepository {
        var calls = 0

        override suspend fun priceMany(
            requests: List<PriceRequest>,
        ): Map<PriceRequest, Result<CardPrice>> {
            calls += 1
            return requests.associateWith {
                Result.success(
                    CardPrice(
                        marketPrice = 1.23,
                        lowPrice = 1.0,
                        midPrice = 1.23,
                        highPrice = 2.0,
                        lastUpdated = Instant.parse("2026-05-26T00:00:00Z"),
                    ),
                )
            }
        }
    }
}
