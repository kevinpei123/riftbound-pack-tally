package com.riftbound.packtally.core.persistence

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.Instant

private const val TAG = "BackfillJob"

/**
 * One-time job that runs after the v2 → v3 Room migration.
 *
 * For every [LooseScanEntity] with `tcgplayerId == null`, this looks up the
 * matching [CardEntity] (by the old internal `cardId` and a few legacy fall-backs)
 * and writes the JustTCG join key back to the row. Unmatched rows are left
 * untouched and surface in Collection as "Unknown card — tap to identify".
 *
 * Idempotent: a `backfill_v3_completed_at` timestamp lives in DataStore. A
 * second run is a no-op.
 *
 * Pack-session entries (which embed full `RiftboundCard` objects inside
 * `entriesJson`) are NOT backfilled here. The pre-migration JSON deserializes
 * cleanly because `RiftboundCard.tcgplayerId` defaults to `""`; those cards
 * will fail pricing with a clear "blank tcgplayerId" error and the user can
 * fix each one through the correction sheet.
 */
class BackfillJob(
    private val looseScanDao: LooseScanDao,
    private val cardDao: CardDao,
    private val dataStore: DataStore<Preferences>,
) {

    suspend fun runIfNeeded(): BackfillResult = withContext(Dispatchers.IO) {
        val prefs = dataStore.data.first()
        if (prefs[KEY_BACKFILL_DONE] != null) {
            return@withContext BackfillResult(0, 0, alreadyRan = true)
        }

        val cards = cardDao.getAll()
        val byId: Map<String, CardEntity> = cards.associateBy { it.id.lowercase() }
        val byCollectorNumber: Map<String, CardEntity> = buildLegacyIndex(cards)

        val looseRows = looseScanDao.getAll()
        var filled = 0
        var skipped = 0
        for (row in looseRows) {
            if (row.tcgplayerId != null) continue
            val match = resolveCard(row.cardId, byId, byCollectorNumber)
            if (match != null) {
                looseScanDao.update(row.copy(tcgplayerId = match.tcgplayerId))
                filled += 1
            } else {
                skipped += 1
            }
        }

        dataStore.edit { it[KEY_BACKFILL_DONE] = Instant.now().toString() }
        Log.i(
            TAG,
            "Backfilled $filled of ${looseRows.size} orphaned scans; $skipped remain unresolved.",
        )
        BackfillResult(filled = filled, skipped = skipped)
    }

    private fun buildLegacyIndex(cards: List<CardEntity>): Map<String, CardEntity> {
        val out = mutableMapOf<String, CardEntity>()
        for (c in cards) {
            // Riftcodex format: "OGN-011"
            out[c.collectorNumber.uppercase()] = c
            val parts = c.collectorNumber.split('-')
            if (parts.size == 2) {
                // Bare number, padded
                out[parts[1]] = c
                // Bare number, unpadded
                out[parts[1].trimStart('0').ifEmpty { "0" }] = c
            }
        }
        return out
    }

    private fun resolveCard(
        oldCardId: String,
        byId: Map<String, CardEntity>,
        byLegacyKey: Map<String, CardEntity>,
    ): CardEntity? {
        // Direct id match (works when sources agree).
        byId[oldCardId.lowercase()]?.let { return it }
        // Old "set-NNN-TOT" scraper format → try "SET-NNN" then bare number.
        val parts = oldCardId.split('-')
        if (parts.size >= 2) {
            val setNum = "${parts[0].uppercase()}-${parts[1]}"
            byLegacyKey[setNum]?.let { return it }
            byLegacyKey[parts[1]]?.let { return it }
            byLegacyKey[parts[1].trimStart('0').ifEmpty { "0" }]?.let { return it }
        }
        // Could be a bare collector number or "OGN-011" format already.
        byLegacyKey[oldCardId.uppercase()]?.let { return it }
        return null
    }

    companion object {
        private val KEY_BACKFILL_DONE = stringPreferencesKey("backfill_v3_completed_at")
    }
}

data class BackfillResult(
    val filled: Int,
    val skipped: Int,
    val alreadyRan: Boolean = false,
) {
    val total: Int get() = filled + skipped
}
