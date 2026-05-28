package com.riftbound.packtally.core.carddb

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.riftbound.packtally.core.persistence.CardDao
import com.riftbound.packtally.core.persistence.CardEntity
import com.riftbound.packtally.model.Rarity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant

private const val TAG = "CardDbSync"

/**
 * Pulls the full Riftcodex card catalogue and persists it to Room.
 *
 * - First launch: sync runs blocking the first-launch screen until done.
 * - Manual re-sync: triggered from Settings.
 * - Periodic re-sync: every 7 days via WorkManager (DEFERRED — see Open Questions).
 *
 * State persisted to DataStore under `cards_synced_at`. Clients consume it as
 * a Flow so the UI can react to sync completion.
 */
class CardDbSync(
    private val client: RiftcodexCardSource,
    private val cardDao: CardDao,
    private val dataStore: DataStore<Preferences>,
) {
    val lastSyncedAt: Flow<Instant?> = dataStore.data.map { prefs ->
        prefs[KEY_SYNCED_AT]?.let(Instant::ofEpochMilli)
    }

    suspend fun isSynced(): Boolean = lastSyncedAt.first() != null

    suspend fun cardCount(): Int = withContext(Dispatchers.IO) { cardDao.count() }

    /**
     * Walk Riftcodex pages, write to Room. Returns the number of cards now in the
     * local DB. Throws on network or parse failure — caller (FirstLaunchScreen
     * or Settings re-sync button) decides how to surface the error.
     */
    suspend fun runFullSync(): Int = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting Riftcodex full sync…")
        val dtos = client.fetchAllCards()
        Log.i(TAG, "Riftcodex returned ${dtos.size} cards")
        val entities = dtos.mapNotNull { it.toEntityOrNull() }
        if (entities.size < dtos.size) {
            Log.w(
                TAG,
                "Dropped ${dtos.size - entities.size} cards lacking tcgplayer_id or riftbound_id",
            )
        }
        // Refuse to commit an empty catalogue. Without this guard, a schema
        // drift on the Riftcodex side (e.g. envelope key change) silently wipes
        // the local table AND marks it as synced, leaving the app stuck on an
        // empty DB until the user finds the Settings re-sync button. Throwing
        // here surfaces the failure on FirstLaunchScreen and leaves any prior
        // good state intact.
        if (entities.isEmpty()) {
            error(
                "Riftcodex returned ${dtos.size} cards but none matched the expected " +
                    "schema. Check that the Riftcodex API contract hasn't changed.",
            )
        }
        // Strategy: full replace (deleteAll + upsert). Riftcodex is the source
        // of truth; we don't merge. ~1k rows so the throwaway is cheap.
        cardDao.deleteAll()
        cardDao.upsertAll(entities)
        dataStore.edit { prefs ->
            prefs[KEY_SYNCED_AT] = Instant.now().toEpochMilli()
        }
        Log.i(TAG, "Sync complete — ${entities.size} cards in DB")
        entities.size
    }

    private fun RiftcodexCardDto.toEntityOrNull(): CardEntity? {
        // tcgplayer_id is the JustTCG join key. Without it the card is useless
        // for pricing — drop it from the local catalogue and log.
        val tcg = tcgplayerId ?: run {
            Log.d(TAG, "Card ${id} (${name}) missing tcgplayer_id; skipping")
            return null
        }
        val rifId = riftboundId ?: run {
            Log.d(TAG, "Card ${id} (${name}) missing riftbound_id; skipping")
            return null
        }
        // Riftcodex's riftbound_id is lowercase "set-num[letter]-total"
        // (e.g. "unl-060a-219", "ogn-181-298"). The OCR parser keys lookups
        // off "SET-NUM" (uppercase, no total) — see CardDatabase.lookupByNumber —
        // so we normalise the same shape here. The alt-art letter suffix is
        // preserved so the regular print and its alt-art share the (set, number)
        // base but stay distinct rows.
        val parts = rifId.split('-')
        if (parts.size < 2) {
            Log.d(TAG, "Card $id has unexpected riftbound_id '$rifId'; skipping")
            return null
        }
        val numWithSuffix = parts[1]
        val setCode = (
            set?.setId?.takeIf { it.isNotBlank() }
                ?: set?.id?.takeIf { it.isNotBlank() }
                ?: parts[0]
            ).uppercase()
        val collectorNumber = "$setCode-$numWithSuffix"
        return CardEntity(
            id = id,
            name = name,
            collectorNumber = collectorNumber,
            setCode = setCode.ifBlank { "UNK" },
            rarity = (classification?.rarity ?: "common").lowercase(),
            isFoilByDefault = false,
            hasSignatureVariant = metadata?.signature == true,
            tcgplayerId = tcg,
            hasAlternateArt = metadata?.alternateArt == true,
            imageUrl = media?.imageUrl,
            domains = classification?.domain
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.joinToString("|")
                .orEmpty(),
        )
    }

    private companion object {
        val KEY_SYNCED_AT = longPreferencesKey("cards_synced_at")
    }
}

/** Map the persisted CardEntity row to the in-memory domain model. */
fun CardEntity.toRiftboundCard(): com.riftbound.packtally.model.RiftboundCard =
    com.riftbound.packtally.model.RiftboundCard(
        id = id,
        collectorNumber = collectorNumber,
        name = name,
        setCode = setCode,
        rarity = parseRarity(rarity),
        isFoilByDefault = isFoilByDefault,
        hasSignatureVariant = hasSignatureVariant,
        tcgplayerId = tcgplayerId,
        hasAlternateArt = hasAlternateArt,
        imageUrl = imageUrl,
        domains = domains.split('|').map { it.trim() }.filter { it.isNotBlank() },
    )

private fun parseRarity(s: String): Rarity = when (s.lowercase()) {
    "common" -> Rarity.COMMON
    "uncommon" -> Rarity.UNCOMMON
    "rare" -> Rarity.RARE
    "epic" -> Rarity.EPIC
    "showcase" -> Rarity.SHOWCASE
    else -> Rarity.COMMON
}
