package com.riftbound.packtally.core.pricing

import android.util.Log
import com.riftbound.packtally.model.CardPrice
import com.riftbound.packtally.model.Variant
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
 * Among exact matching variants in a response, English wins. If JustTCG has no
 * exact printing match, fall back to a same-condition English/highest variant
 * so cards whose product listing only exposes foil rows can still be priced.
 */
class JustTcgPricingRepository(
    private val client: JustTcgApiClient,
    private val quota: QuotaTracker? = null,
) : PricingRepository {

    override suspend fun priceMany(
        requests: List<PriceRequest>,
        forceRefresh: Boolean,
    ): Map<PriceRequest, Result<CardPrice>> {
        // forceRefresh is a no-op here: this repository always hits the network.
        // The flag only matters to cache decorators wrapping this one.
        if (requests.isEmpty()) return emptyMap()

        val results = mutableMapOf<PriceRequest, Result<CardPrice>>()
        val distinctRequests = requests.distinctBy { it.tcgplayerId to it.variant }
        val chunks = distinctRequests.chunked(JustTcgClient.MAX_BATCH)

        chunks.forEachIndexed { idx, chunk ->
            if (idx > 0) delay(INTER_BATCH_DELAY_MS)

            // Quota pre-flight: hard stop if any bucket is at capacity.
            quota?.let { q ->
                if (q.isAtCapacity()) {
                    val state = q.currentState()
                    chunk.forEach { req ->
                        results[req] = Result.failure(RateLimitedException(state))
                    }
                    return@forEachIndexed
                }
                if (q.useCachedOnly.value) {
                    chunk.forEach { req ->
                        results[req] = Result.failure(CachedOnlyModeException())
                    }
                    return@forEachIndexed
                }
                if (q.shouldBackoff()) {
                    Log.i(TAG, "Minute bucket near limit; backing off ${QuotaTracker.BACKOFF_DELAY_MS}ms")
                    delay(QuotaTracker.BACKOFF_DELAY_MS)
                }
            }

            val items = chunk.map { req ->
                // Do not pre-filter by printing/condition at the API boundary.
                // Some Riftbound TCGPlayer products only expose a foil listing;
                // fetching the product by id lets local exact/fallback variant
                // matching price those cards instead of turning them into false
                // failures.
                JustTcgRequestItem(
                    tcgplayerId = req.tcgplayerId,
                )
            }

            val response = runCatching { client.postCards(items) }
                .getOrElse { exc ->
                    Log.e(TAG, "Batch ${idx + 1}/${chunks.size} failed", exc)
                    val mapped = exc.toPricingException()
                    chunk.forEach { req ->
                        results[req] = Result.failure(mapped)
                    }
                    return@forEachIndexed
                }

            // Successful network call — increment + correct via server hints.
            quota?.recordNetworkCall(calls = 1)
            response.metadata?.let { quota?.applyServerHints(it) }

            val responseById = response.data.associateBy { it.tcgplayerId }
            chunk.forEach { req ->
                val card = responseById[req.tcgplayerId]
                if (card == null) {
                    results[req] = Result.failure(NotFoundException(req.tcgplayerId))
                    return@forEach
                }
                val filter = client.filterFor(req.variant)
                val pickedVariant = pickVariant(card.variants, filter)
                if (pickedVariant == null) {
                    results[req] = Result.failure(
                        NoMatchingVariantException(card.tcgplayerId, filter),
                    )
                    return@forEach
                }
                val price = pickedVariant.toCardPrice()
                if (price == null) {
                    results[req] = Result.failure(
                        IllegalStateException("JustTCG returned no price fields for ${card.tcgplayerId}"),
                    )
                } else {
                    results[req] = Result.success(price)
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
            it.printing.matchesJustTcgValue(filter.printing) &&
                it.condition.matchesJustTcgValue(filter.condition)
        }
        matching.bestVariantOrNull()?.let { return it }

        val sameCondition = variants.filter {
            it.condition.matchesJustTcgValue(filter.condition)
        }
        sameCondition.bestVariantOrNull()?.let { return it }

        return variants.bestVariantOrNull()
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

private fun Throwable.toPricingException(): Throwable {
    val http = this as? retrofit2.HttpException ?: return this
    return when (http.code()) {
        400 -> InvalidPricingRequestException()
        401 -> ApiKeyRejectedException()
        429 -> ServerRateLimitedException()
        in 500..599 -> PricingServerException(http.code())
        else -> this
    }
}

private fun List<JustTcgVariant>.bestVariantOrNull(): JustTcgVariant? {
    if (isEmpty()) return null
    val english = filter { it.language.matchesJustTcgValue("English") }
    val pool = english.ifEmpty { this }
    return pool.maxByOrNull { it.bestKnownPrice() }
}

private fun JustTcgVariant.bestKnownPrice(): Double =
    price ?: midPrice ?: highPrice ?: lowPrice ?: 0.0

private fun String?.matchesJustTcgValue(expected: String): Boolean =
    this.normalizeJustTcgValue() == expected.normalizeJustTcgValue()

private fun String?.normalizeJustTcgValue(): String =
    orEmpty()
        .trim()
        .lowercase()
        .filter { it.isLetterOrDigit() }

class NoMatchingVariantException(
    val tcgplayerId: String,
    val filter: VariantFilter,
) : Exception("JustTCG: no variant matching $filter for tcgplayerId=$tcgplayerId")

class NotFoundException(val tcgplayerId: String) :
    Exception("JustTCG: tcgplayerId=$tcgplayerId not present in response")

class InvalidPricingRequestException :
    Exception("JustTCG rejected the pricing request. Check card IDs and variants.")

class ApiKeyRejectedException :
    Exception("JustTCG API key missing or rejected. Re-enter the key in Settings.")

class ServerRateLimitedException :
    Exception("JustTCG rate limit reached. Wait, use cache-only mode, or retry later.")

class PricingServerException(code: Int) :
    Exception("JustTCG server error ($code). Prices were not saved; retry later.")
