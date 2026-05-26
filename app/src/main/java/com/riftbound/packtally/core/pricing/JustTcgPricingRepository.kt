package com.riftbound.packtally.core.pricing

import android.util.Log
import com.riftbound.packtally.feature.scanner.Variant
import kotlinx.coroutines.delay
import java.time.Instant

private const val TAG = "JustTcgPricingRepository"
private const val INTER_BATCH_DELAY_MS = 100L

/**
 * Batched pricing client over JustTCG.
 *
 * Splits inputs into chunks of [JustTcgClient.MAX_BATCH] (20), issues
 * `POST /v1/cards` per chunk with a 100ms gap between, merges responses into
 * `Map<tcgplayerId, Result<CardPrice>>`.
 *
 * Quota concerns live here, not in a separate decorator, because the network
 * boundary IS the per-call quota unit:
 *
 *  - Before each network call: if quota is at capacity → fail fast with
 *    [RateLimitedException] for every request in the chunk.
 *  - Before each network call: if cache-only mode is active → fail with
 *    [CachedOnlyModeException].
 *  - At ≥7/10 minute usage: insert a 6-second back-off before the next call.
 *  - After each successful response: `quota.recordNetworkCall()` once
 *    (the batch counts as 1 request regardless of item count) + apply server
 *    hints from `_metadata`.
 *
 * Among the matching variants in a response, English wins; if no English, the
 * highest-priced variant.
 */
class JustTcgPricingRepository(
    private val client: JustTcgClient,
    private val quota: QuotaTracker? = null,
) : PricingRepository {

    override suspend fun priceMany(
        requests: List<PriceRequest>,
    ): Map<String, Result<CardPrice>> {
        if (requests.isEmpty()) return emptyMap()

        val variantByTcgplayerId: Map<String, Variant> =
            requests.associate { it.tcgplayerId to it.variant }

        val results = mutableMapOf<String, Result<CardPrice>>()
        val chunks = requests.distinctBy { it.tcgplayerId }.chunked(JustTcgClient.MAX_BATCH)

        chunks.forEachIndexed { idx, chunk ->
            if (idx > 0) delay(INTER_BATCH_DELAY_MS)

            // Quota pre-flight: hard stop if any bucket is at capacity.
            quota?.let { q ->
                if (q.isAtCapacity()) {
                    val state = q.currentState()
                    chunk.forEach { req ->
                        results[req.tcgplayerId] = Result.failure(RateLimitedException(state))
                    }
                    return@forEachIndexed
                }
                if (q.useCachedOnly.value) {
                    chunk.forEach { req ->
                        results[req.tcgplayerId] = Result.failure(CachedOnlyModeException())
                    }
                    return@forEachIndexed
                }
                if (q.shouldBackoff()) {
                    Log.i(TAG, "Minute bucket near limit; backing off ${QuotaTracker.BACKOFF_DELAY_MS}ms")
                    delay(QuotaTracker.BACKOFF_DELAY_MS)
                }
            }

            val items = chunk.map { req ->
                val filter = client.filterFor(req.variant)
                JustTcgRequestItem(
                    tcgplayerId = req.tcgplayerId,
                    condition = filter.condition,
                    printing = filter.printing,
                )
            }

            val response = runCatching { client.postCards(items) }
                .getOrElse { exc ->
                    Log.e(TAG, "Batch ${idx + 1}/${chunks.size} failed", exc)
                    chunk.forEach { req ->
                        results[req.tcgplayerId] = Result.failure(exc)
                    }
                    return@forEachIndexed
                }

            // Successful network call — increment + correct via server hints.
            quota?.recordNetworkCall(calls = 1)
            response.metadata?.let { quota?.applyServerHints(it) }

            response.data.forEach { card ->
                val variant = variantByTcgplayerId[card.tcgplayerId] ?: return@forEach
                val filter = client.filterFor(variant)
                val pickedVariant = pickVariant(card.variants, filter)
                if (pickedVariant == null) {
                    results[card.tcgplayerId] = Result.failure(
                        NoMatchingVariantException(card.tcgplayerId, filter),
                    )
                    return@forEach
                }
                val price = pickedVariant.toCardPrice()
                if (price == null) {
                    results[card.tcgplayerId] = Result.failure(
                        IllegalStateException("JustTCG returned no price fields for ${card.tcgplayerId}"),
                    )
                } else {
                    results[card.tcgplayerId] = Result.success(price)
                }
            }
            // Mark any chunk-requested IDs missing from the response as not-found.
            chunk.forEach { req ->
                if (req.tcgplayerId !in results) {
                    results[req.tcgplayerId] = Result.failure(NotFoundException(req.tcgplayerId))
                }
            }
        }
        return results
    }

    private fun pickVariant(
        variants: List<JustTcgVariant>,
        filter: VariantFilter,
    ): JustTcgVariant? {
        val matching = variants.filter {
            it.printing.equals(filter.printing, ignoreCase = true) &&
                it.condition.equals(filter.condition, ignoreCase = true)
        }
        if (matching.isEmpty()) return null
        val english = matching.firstOrNull { it.language.equals("English", ignoreCase = true) }
        if (english != null) return english
        return matching.maxByOrNull { it.price ?: 0.0 }
    }

    private fun JustTcgVariant.toCardPrice(): CardPrice? {
        val market = price ?: midPrice ?: return null
        val low = lowPrice ?: market
        val mid = midPrice ?: market
        val high = highPrice ?: market
        val updated = lastUpdated?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: Instant.now()
        return CardPrice(
            marketPrice = market,
            lowPrice = low,
            midPrice = mid,
            highPrice = high,
            currency = "USD",
            lastUpdated = updated,
        )
    }
}

class NoMatchingVariantException(
    val tcgplayerId: String,
    val filter: VariantFilter,
) : Exception("JustTCG: no variant matching $filter for tcgplayerId=$tcgplayerId")

class NotFoundException(val tcgplayerId: String) :
    Exception("JustTCG: tcgplayerId=$tcgplayerId not present in response")
