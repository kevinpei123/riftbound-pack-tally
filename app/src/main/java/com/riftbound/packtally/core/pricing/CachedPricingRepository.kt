package com.riftbound.packtally.core.pricing

import android.util.Log
import com.riftbound.packtally.model.CardPrice
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant

private const val TAG = "CachedPricing"
private const val LEGACY_PRICES_DIR = "prices"
private const val V2_PRICES_DIR = "prices_v2"

private val DEFAULT_TTL: Duration = Duration.ofHours(6)

/**
 * Batch-aware cache decorator over a [PricingRepository].
 *
 *  1. For each request in [priceMany], look in `cacheDir/prices_v2/v2_<tcgplayerId>_<variant>.json`.
 *  2. Fresh hits go straight into the result map. They don't burn quota.
 *  3. Misses are bundled into a smaller batch and forwarded to [delegate].
 *  4. Delegate successes are written back to the cache and merged into the result.
 *
 * Old tcgapi.dev cache entries from the previous era of this app live under
 * the legacy `prices/` directory; [maybeMigrateLegacyCache] wipes that dir on
 * first run after upgrading.
 */
class CachedPricingRepository(
    private val delegate: PricingRepository,
    cacheDir: File,
    private val ttlProvider: suspend () -> Duration = { DEFAULT_TTL },
    private val clock: () -> Instant = { Instant.now() },
) : PricingRepository {

    private val pricesDir: File = File(cacheDir, V2_PRICES_DIR)
    private val legacyDir: File = File(cacheDir, LEGACY_PRICES_DIR)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    init {
        maybeMigrateLegacyCache()
    }

    override suspend fun priceMany(
        requests: List<PriceRequest>,
    ): Map<PriceRequest, Result<CardPrice>> {
        if (requests.isEmpty()) return emptyMap()

        val ttl = ttlProvider()
        val results = mutableMapOf<PriceRequest, Result<CardPrice>>()
        val misses = mutableListOf<PriceRequest>()

        for (req in requests.distinctBy { it.tcgplayerId to it.variant }) {
            val cached = readFresh(req, ttl)
            if (cached != null) {
                results[req] = Result.success(cached)
            } else {
                misses += req
            }
        }

        if (misses.isEmpty()) return results

        val fetched = delegate.priceMany(misses)
        for ((request, result) in fetched) {
            result.onSuccess { price ->
                write(req = request, price = price)
            }
            results[request] = result
        }

        return results
    }

    /** Delete every file under `cacheDir/prices_v2/`. */
    fun clearCache() {
        pricesDir.listFiles()?.forEach { it.delete() }
    }

    /** Total bytes used by cached price files. Returns 0 if the dir doesn't exist yet. */
    fun cacheSizeBytes(): Long {
        if (!pricesDir.isDirectory) return 0L
        return pricesDir.walk().filter { it.isFile }.sumOf { it.length() }
    }

    private fun maybeMigrateLegacyCache() {
        if (legacyDir.isDirectory) {
            Log.i(TAG, "Wiping legacy tcgapi.dev cache at ${legacyDir.absolutePath}")
            legacyDir.deleteRecursively()
        }
    }

    private fun cacheFileFor(req: PriceRequest): File {
        val safeId = req.tcgplayerId.replace(File.separatorChar, '_')
        return File(pricesDir, "v2_${safeId}_${req.variant.name}.json")
    }

    private fun readFresh(req: PriceRequest, ttl: Duration): CardPrice? {
        val file = cacheFileFor(req)
        if (!file.isFile) return null
        val price = runCatching { json.decodeFromString<CardPrice>(file.readText()) }
            .getOrNull() ?: return null
        return if (clock().isBefore(price.lastUpdated.plus(ttl))) price else null
    }

    private fun write(req: PriceRequest, price: CardPrice) {
        runCatching {
            pricesDir.mkdirs()
            cacheFileFor(req).writeText(json.encodeToString(CardPrice.serializer(), price))
        }.onFailure { exc ->
            Log.w(TAG, "Cache write failed for ${req.tcgplayerId}/${req.variant}: ${exc.message}")
        }
    }
}
