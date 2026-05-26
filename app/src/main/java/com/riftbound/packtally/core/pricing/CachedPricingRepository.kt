package com.riftbound.packtally.core.pricing

import android.util.Log
import com.riftbound.packtally.model.RiftboundCard
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant

private const val TAG = "CachedPricing"

private val DEFAULT_TTL: Duration = Duration.ofHours(6)

/**
 * File-based cache decorator over any [PricingRepository].
 *
 * On each [price] call:
 * 1. Look for `${cacheDir}/prices/${cardId}_${foil}_${signature}.json`.
 * 2. If the file exists and `now() < lastUpdated + ttlProvider()`, return it.
 * 3. Otherwise delegate to [delegate], and on success persist the result.
 *
 * [ttlProvider] is read fresh on every call so SettingsScreen can change the
 * cache window at runtime without rebuilding the repository.
 *
 * Cache writes are best-effort — IO failures are swallowed so a transient disk
 * problem can't break pricing. Cache reads of corrupt files are treated as a
 * miss (the bad file gets overwritten by the next successful fetch).
 */
class CachedPricingRepository(
    private val delegate: PricingRepository,
    cacheDir: File,
    private val ttlProvider: suspend () -> Duration = { DEFAULT_TTL },
    private val clock: () -> Instant = { Instant.now() },
) : PricingRepository {

    private val pricesDir: File = File(cacheDir, "prices")

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    override suspend fun price(
        card: RiftboundCard,
        foil: Boolean,
        signature: Boolean,
    ): Result<CardPrice> {
        val ttl = ttlProvider()
        val file = cacheFileFor(card.id, foil, signature)
        readFresh(file, ttl)?.let { return Result.success(it) }

        val fetched = delegate.price(card, foil, signature)
        fetched.onSuccess { write(file, it) }
        return fetched
    }

    /** Delete every file under `${cacheDir}/prices/`. Cheap — there's never more than ~1000. */
    fun clearCache() {
        pricesDir.listFiles()?.forEach { it.delete() }
    }

    /** Total bytes used by cached price files. Returns 0 if the directory doesn't exist yet. */
    fun cacheSizeBytes(): Long {
        if (!pricesDir.isDirectory) return 0L
        return pricesDir.walk()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    private fun cacheFileFor(cardId: String, foil: Boolean, signature: Boolean): File {
        val safeId = cardId.replace(File.separatorChar, '_')
        return File(pricesDir, "${safeId}_${foil}_${signature}.json")
    }

    private fun readFresh(file: File, ttl: Duration): CardPrice? {
        if (!file.isFile) return null
        val price = runCatching { json.decodeFromString<CardPrice>(file.readText()) }
            .getOrNull() ?: return null
        return if (clock().isBefore(price.lastUpdated.plus(ttl))) price else null
    }

    private fun write(file: File, price: CardPrice) {
        runCatching {
            pricesDir.mkdirs()
            file.writeText(json.encodeToString(CardPrice.serializer(), price))
        }.onFailure { exc ->
            // Best-effort: failed cache writes are non-fatal — the next request
            // just refetches from network and tries to write again.
            Log.w(TAG, "Cache write failed for ${file.name}: ${exc.message}")
        }
    }
}
