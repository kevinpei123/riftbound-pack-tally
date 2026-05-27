package com.riftbound.packtally.core.persistence

import android.util.Log
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.core.pricing.PriceRequest
import com.riftbound.packtally.core.pricing.PricingRepository
import com.riftbound.packtally.model.CardPrice
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.ScannedEntry
import com.riftbound.packtally.model.Variant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant

private const val TAG = "LooseScanRepository"

/**
 * Repository for the loose-scan pool (Quick Scan). Cards added here are NOT
 * part of any pack/box session. Conversion in/out of [ScannedEntry] matches
 * the schema CollectionViewModel aggregates over, so loose scans appear in
 * the Collection grouping exactly like pack-derived entries do.
 */
class LooseScanRepository(private val dao: LooseScanDao) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Reactive feed of all loose scans, newest-first. */
    val entries: Flow<List<ScannedEntry>> = dao.observeAll().map { rows ->
        rows.mapNotNull { it.toScannedEntryOrNull() }
    }

    /**
     * Persist a scanned card. [price] may be `null` — that's the normal path
     * since QuickScan defers pricing to a batched submit. The empty-string
     * sentinel for `priceJson` keeps the column non-null without a Room
     * migration; [toScannedEntryOrNull] decodes it back to a null price.
     */
    suspend fun saveEntry(
        card: RiftboundCard,
        variant: Variant,
        price: CardPrice? = null,
        notes: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val entity = LooseScanEntity(
            cardId = card.id,
            variant = variant.name,
            priceJson = price?.let { json.encodeToString(CardPrice.serializer(), it) }.orEmpty(),
            scannedAt = Instant.now().toEpochMilli(),
            notes = notes,
            tcgplayerId = card.tcgplayerId,
        )
        dao.insert(entity)
    }

    /** Attach a freshly fetched price to an existing loose scan row. */
    suspend fun setPrice(rowId: Long, price: CardPrice): Boolean = withContext(Dispatchers.IO) {
        val existing = dao.getById(rowId) ?: return@withContext false
        dao.update(
            existing.copy(priceJson = json.encodeToString(CardPrice.serializer(), price)),
        )
        true
    }

    /** Snapshot of every loose-scan row that still has no price attached. */
    suspend fun getPending(): List<LooseScanEntity> = withContext(Dispatchers.IO) {
        dao.getAll().filter { it.priceJson.isBlank() }
    }

    /**
     * Delete the most-recently-scanned loose row matching [cardId] and [variant].
     * Returns `true` if a row was deleted, `false` if no such loose scan exists
     * (the entry might still live inside a pack — caller's responsibility to
     * surface that).
     */
    suspend fun deleteOneMatching(cardId: String, variant: Variant): Boolean = withContext(Dispatchers.IO) {
        val target = dao.getAll()
            .firstOrNull { it.cardId == cardId && it.variant == variant.name }
            ?: return@withContext false
        dao.deleteById(target.id)
        true
    }

    /**
     * Batch-price every pending row. Used by Quick Scan AND Collection so the
     * same submit semantics drive both surfaces. Returns the number of rows
     * priced, the number that failed, and the total $ value added in.
     */
    suspend fun submitPendingPrices(pricing: PricingRepository): SubmitResult = withContext(Dispatchers.IO) {
        val pending = getPending()
        if (pending.isEmpty()) return@withContext SubmitResult.Empty
        val requests = pending.mapNotNull { row ->
            val variant = runCatching { Variant.valueOf(row.variant) }.getOrDefault(Variant.STANDARD)
            row.tcgplayerId?.takeIf { it.isNotBlank() }?.let { PriceRequest(it, variant) }
        }
        val resultMap = if (requests.isNotEmpty()) {
            runCatching { pricing.priceMany(requests) }
                .getOrElse { exc -> return@withContext SubmitResult.NetworkError(exc.message ?: "pricing call failed") }
        } else {
            emptyMap()
        }

        var priced = 0
        var failed = 0
        var total = 0.0
        pending.forEach { row ->
            val tcg = row.tcgplayerId
            val variant = runCatching { Variant.valueOf(row.variant) }.getOrDefault(Variant.STANDARD)
            val r = tcg?.takeIf { it.isNotBlank() }?.let { resultMap[PriceRequest(it, variant)] }
            r?.onSuccess { price ->
                val ok = runCatching { setPrice(row.id, price) }.getOrDefault(false)
                if (ok) { priced += 1; total += price.marketPrice } else failed += 1
            }?.onFailure { failed += 1 } ?: run { failed += 1 }
        }
        SubmitResult.Done(priced = priced, failed = failed, totalValue = total)
    }

    sealed interface SubmitResult {
        data object Empty : SubmitResult
        data class Done(val priced: Int, val failed: Int, val totalValue: Double) : SubmitResult
        data class NetworkError(val reason: String) : SubmitResult
    }

    suspend fun deleteEntryById(id: Long): Unit = withContext(Dispatchers.IO) {
        // Synthetic ScannedEntry.id is "loose-<row>"; strip the prefix at the call site.
        dao.deleteById(id)
    }

    suspend fun replaceEntry(
        rowId: Long,
        newCard: RiftboundCard,
        newVariant: Variant,
        newPrice: CardPrice,
    ): Boolean = withContext(Dispatchers.IO) {
        val existing = dao.getById(rowId) ?: return@withContext false
        dao.update(
            existing.copy(
                cardId = newCard.id,
                variant = newVariant.name,
                priceJson = json.encodeToString(CardPrice.serializer(), newPrice),
            ),
        )
        true
    }

    /** Read-once snapshot for the Collection export. */
    suspend fun getAllForExport(): List<ScannedEntry> = withContext(Dispatchers.IO) {
        dao.getAll().mapNotNull { it.toScannedEntryOrNull() }
    }

    private fun LooseScanEntity.toScannedEntryOrNull(): ScannedEntry? {
        val card = CardDatabase.lookupByNumber(cardId)
            ?: CardDatabase.lookupByTcgplayerId(tcgplayerId ?: "")
            ?: run {
                Log.w(TAG, "Loose scan $id references unknown card $cardId; dropping from view")
                return null
            }
        // Empty priceJson is the "not yet priced" sentinel. Non-empty but
        // un-parseable is logged and treated the same way — the row still
        // appears in Collection, just without a price.
        val price = priceJson.takeIf { it.isNotBlank() }?.let { raw ->
            runCatching { json.decodeFromString<CardPrice>(raw) }
                .onFailure { Log.w(TAG, "Failed to parse priceJson for loose scan $id", it) }
                .getOrNull()
        }
        val parsedVariant = runCatching { Variant.valueOf(variant) }.getOrDefault(Variant.STANDARD)
        return ScannedEntry(
            id = "loose-$id",
            card = card,
            variant = parsedVariant,
            price = price,
            // Manual loose scans are user-confirmed — stamp 1.0 confidence
            // so they don't show up as "low confidence" needing review.
            confidence = 1.0f,
            scannedAt = Instant.ofEpochMilli(scannedAt),
        )
    }
}

/** Extract the numeric row id from a synthetic ScannedEntry.id like "loose-42". */
fun ScannedEntry.looseScanRowIdOrNull(): Long? =
    id.removePrefix("loose-").toLongOrNull().takeIf { id.startsWith("loose-") }
