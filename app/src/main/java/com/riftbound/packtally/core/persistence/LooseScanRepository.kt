package com.riftbound.packtally.core.persistence

import android.util.Log
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.core.pricing.CardPrice
import com.riftbound.packtally.feature.scanner.Variant
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.ScannedEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant

private const val TAG = "LooseScanRepository"

/**
 * Repository for the loose-scan pool (Quick Scan feature, Phase 3). Cards added
 * here are NOT part of any pack/box session. Conversion in/out of [ScannedEntry]
 * matches the schema CollectionViewModel aggregates over, so loose scans appear
 * in the Collection grouping exactly like pack-derived entries do.
 */
class LooseScanRepository(private val dao: LooseScanDao) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Reactive feed of all loose scans, newest-first. */
    val entries: Flow<List<ScannedEntry>> = dao.observeAll().map { rows ->
        rows.mapNotNull { it.toScannedEntryOrNull() }
    }

    suspend fun saveEntry(
        card: RiftboundCard,
        variant: Variant,
        price: CardPrice,
        notes: String? = null,
    ): Long = withContext(Dispatchers.IO) {
        val entity = LooseScanEntity(
            cardId = card.id,
            variant = variant.name,
            priceJson = json.encodeToString(CardPrice.serializer(), price),
            scannedAt = Instant.now().toEpochMilli(),
            notes = notes,
        )
        dao.insert(entity)
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
        val card = CardDatabase.lookupByNumber(cardId) ?: run {
            Log.w(TAG, "Loose scan $id references unknown card $cardId; dropping from view")
            return null
        }
        val price = runCatching { json.decodeFromString<CardPrice>(priceJson) }
            .onFailure { Log.w(TAG, "Failed to parse priceJson for loose scan $id", it) }
            .getOrNull() ?: return null
        val parsedVariant = runCatching { Variant.valueOf(variant) }.getOrDefault(Variant.STANDARD)
        return ScannedEntry(
            id = "loose-$id",
            card = card,
            variant = parsedVariant,
            price = price,
            // CHOICE: manual loose scans are user-confirmed — stamp 1.0 confidence
            // so they don't show up as "low confidence" needing review.
            confidence = 1.0f,
            scannedAt = Instant.ofEpochMilli(scannedAt),
        )
    }
}

/** Extract the numeric row id from a synthetic ScannedEntry.id like "loose-42". */
fun ScannedEntry.looseScanRowIdOrNull(): Long? =
    id.removePrefix("loose-").toLongOrNull().takeIf { id.startsWith("loose-") }
