package com.riftbound.packtally.core.carddb

import android.util.Log
import com.riftbound.packtally.core.persistence.CardDao
import com.riftbound.packtally.model.RiftboundCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "CardDatabase"

/**
 * In-memory lookup layer over the Riftcodex-sourced [CardDao]. Same public API
 * (lookupByNumber, lookupByNameFuzzy) as the prior bundled-JSON implementation —
 * only the data source changed.
 *
 * Loaded once at app start (after first-launch sync completes). For Riftbound's
 * ~1k cards this is trivial memory-wise (~200 KB).
 */
object CardDatabase {

    private var cards: List<RiftboundCard> = emptyList()
    private var byId: Map<String, RiftboundCard> = emptyMap()
    private var byTcgplayerId: Map<String, RiftboundCard> = emptyMap()
    private var bySetCodeAndNumber: Map<String, RiftboundCard> = emptyMap()

    @Volatile
    private var initialized = false

    /** Load the catalogue from Room. Idempotent — repeats refresh the in-memory maps. */
    suspend fun initFromRoom(cardDao: CardDao) = withContext(Dispatchers.IO) {
        val entities = cardDao.getAll()
        val loaded = entities.map { it.toRiftboundCard() }
        cards = loaded
        byId = loaded.associateBy { it.id.lowercase() }
        byTcgplayerId = loaded.associateBy { it.tcgplayerId }
        bySetCodeAndNumber = loaded.associateBy { setNumKey(it.setCode, it.collectorNumber) }
        initialized = true
        Log.i(TAG, "Loaded ${loaded.size} cards from Room")
    }

    fun isReady(): Boolean = initialized

    /**
     * Accepts the printed collector code in several forms:
     *   "ogn-001-298"      (internal id)
     *   "OGN-001/298"      (publicCode as printed on the card)
     *   "OGN-001" / "OGN-1" (set + number, padded or not)
     *   "001" / "1"        (bare number — returns null if ambiguous across sets)
     */
    fun lookupByNumber(input: String): RiftboundCard? {
        if (!initialized) return null
        val core = input.trim().substringBefore('/').trim()
        if (core.isEmpty()) return null

        byId[core.lowercase()]?.let { return it }

        SET_NUM.matchEntire(core)?.let { match ->
            val (set, num) = match.destructured
            val trimmed = num.trimStart('0').ifEmpty { "0" }
            bySetCodeAndNumber[setNumKey(set, trimmed)]?.let { return it }
            // Also try the original Riftbound id format (e.g. OGN-011 with leading zero)
            bySetCodeAndNumber[setNumKey(set, num)]?.let { return it }
            // Riftcodex stores `riftbound_id` like "OGN-011" — try the dash form
            // directly against byId in case the collectorNumber is the full string.
            cards.firstOrNull {
                it.setCode.equals(set, ignoreCase = true) &&
                    it.collectorNumber.equals("${set.uppercase()}-$num", ignoreCase = true)
            }?.let { return it }
        }

        BARE_NUM.matchEntire(core)?.let { match ->
            val num = match.groupValues[1].trimStart('0').ifEmpty { "0" }
            return cards.filter { it.collectorNumber == num }.singleOrNull()
        }

        return null
    }

    /** Reverse lookup used when restoring loose scans from Room by tcgplayer id. */
    fun lookupByTcgplayerId(id: String): RiftboundCard? = byTcgplayerId[id]

    fun lookupByNameFuzzy(query: String, limit: Int = 3): List<RiftboundCard> {
        if (!initialized || query.isBlank() || limit <= 0) return emptyList()
        val q = query.trim().lowercase()
        return cards
            .asSequence()
            .map { it to levenshtein(q, it.name.lowercase()) }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun setNumKey(setCode: String, collectorNumber: String): String =
        "${setCode.uppercase()}-$collectorNumber"

    private val SET_NUM = Regex("""^([A-Za-z]{2,4})-(\d+)$""")
    private val BARE_NUM = Regex("""^(\d+)$""")
}

private fun levenshtein(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    val n = a.length
    val m = b.length
    var prev = IntArray(m + 1) { it }
    var curr = IntArray(m + 1)

    for (i in 1..n) {
        curr[0] = i
        for (j in 1..m) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(
                curr[j - 1] + 1,
                prev[j] + 1,
                prev[j - 1] + cost,
            )
        }
        val tmp = prev
        prev = curr
        curr = tmp
    }
    return prev[m]
}
