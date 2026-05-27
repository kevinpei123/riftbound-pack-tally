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
        replaceCards(cardDao.getAll().map { it.toRiftboundCard() })
    }

    internal fun initForTests(testCards: List<RiftboundCard>) {
        replaceCards(testCards)
    }

    private fun replaceCards(loaded: List<RiftboundCard>) {
        cards = loaded
        byId = loaded.associateBy { it.id.lowercase() }
        byTcgplayerId = loaded.associateBy { it.tcgplayerId }
        bySetCodeAndNumber = loaded.associateBy { setNumKey(it.setCode, it.collectorNumber) }
        initialized = true
        Log.i(TAG, "Loaded ${loaded.size} cards from Room")
    }

    fun isReady(): Boolean = initialized

    /** Number of cards currently loaded. Used by MainActivity to detect a successful sync that nevertheless ended up with an empty table (schema drift, etc.). */
    val size: Int get() = cards.size

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
            bySetCodeAndNumber[setNumKey(set, num)]?.let { return it }
            // If OCR missed the alternate-art suffix, fall back to the base
            // number only when that resolves to a single card. Exact suffix
            // matches above always win.
            val baseKey = setNumKey(set, num.takeWhile { it.isDigit() })
            cards.filter { setNumKey(it.setCode, it.collectorNumber).removeSuffixLetter() == baseKey }
                .singleOrNull()
                ?.let { return it }
        }

        BARE_NUM.matchEntire(core)?.let { match ->
            val num = normalizeNumberPart(match.groupValues[1])
            return cards.filter { normalizeNumberPart(it.collectorNumber) == num }.singleOrNull()
        }

        return null
    }

    /** Reverse lookup used when restoring loose scans from Room by tcgplayer id. */
    fun lookupByTcgplayerId(id: String): RiftboundCard? = byTcgplayerId[id]

    fun lookupByNameFuzzy(query: String, limit: Int = 3): List<RiftboundCard> {
        if (!initialized || query.isBlank() || limit <= 0) return emptyList()
        val q = normalizeNameForLookup(query)
        return cards
            .asSequence()
            .map { card ->
                val name = normalizeNameForLookup(card.name)
                val distance = when {
                    name == q -> 0
                    name.contains(q) -> 1
                    else -> levenshtein(q, name)
                }
                card to distance
            }
            .filter { (_, distance) ->
                distance <= maxOf(2, (q.length * 0.42f).toInt())
            }
            .sortedWith(compareBy<Pair<RiftboundCard, Int>> { it.second }.thenBy { it.first.name })
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun setNumKey(setCode: String, collectorNumber: String): String =
        "${setCode.uppercase()}-${normalizeNumberPart(collectorNumber)}"

    private fun normalizeNumberPart(input: String): String {
        val raw = input.trim()
            .substringBefore('/')
            .substringAfter('-')
        val match = Regex("""^0*(\d+)([A-Za-z]?)$""").matchEntire(raw)
            ?: return raw.lowercase()
        val number = match.groupValues[1].ifEmpty { "0" }
        val suffix = match.groupValues[2].lowercase()
        return number + suffix
    }

    private fun String.removeSuffixLetter(): String =
        replace(Regex("""[a-z]$"""), "")

    private fun normalizeNameForLookup(input: String): String =
        input.lowercase()
            .replace('0', 'o')
            .replace('1', 'i')
            .replace('!', 'i')
            .replace('3', 'e')
            .replace('5', 's')
            .replace('8', 'b')
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")

    private val SET_NUM = Regex("""^([A-Za-z]{2,4})-(\d+[A-Za-z]?)$""")
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
