package com.riftbound.packtally.core.carddb

import android.content.Context
import android.util.Log
import com.riftbound.packtally.model.RiftboundCard
import kotlinx.serialization.json.Json

object CardDatabase {

    private val json = Json { ignoreUnknownKeys = true }

    private var cards: List<RiftboundCard> = emptyList()
    private var byId: Map<String, RiftboundCard> = emptyMap()
    private var bySetCodeAndNumber: Map<String, RiftboundCard> = emptyMap()

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        val parsed = json.decodeFromString<List<RiftboundCard>>(text)
        cards = parsed
        byId = parsed.associateBy { it.id.lowercase() }
        bySetCodeAndNumber = parsed.associateBy { setNumKey(it.setCode, it.collectorNumber) }
        initialized = true
        Log.i(TAG, "Loaded ${parsed.size} cards from $ASSET_PATH")
    }

    /**
     * Accepts the printed collector code in several forms:
     *   "ogn-001-298"      (internal id)
     *   "OGN-001/298"      (publicCode as printed on the card)
     *   "OGN-001" / "OGN-1" (set + number, padded or not)
     *   "001" / "1"        (bare number — returns null if the number is ambiguous across sets)
     */
    fun lookupByNumber(input: String): RiftboundCard? {
        check(initialized) { "CardDatabase.init(context) must be called before lookups" }
        val core = input.trim().substringBefore('/').trim()
        if (core.isEmpty()) return null

        byId[core.lowercase()]?.let { return it }

        SET_NUM.matchEntire(core)?.let { match ->
            val (set, num) = match.destructured
            bySetCodeAndNumber[setNumKey(set, num.trimStart('0').ifEmpty { "0" })]?.let { return it }
        }

        BARE_NUM.matchEntire(core)?.let { match ->
            val num = match.groupValues[1].trimStart('0').ifEmpty { "0" }
            return cards.filter { it.collectorNumber == num }.singleOrNull()
        }

        return null
    }

    fun lookupByNameFuzzy(query: String, limit: Int = 3): List<RiftboundCard> {
        check(initialized) { "CardDatabase.init(context) must be called before lookups" }
        if (query.isBlank() || limit <= 0) return emptyList()
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

    private const val TAG = "CardDatabase"
    private const val ASSET_PATH = "cards.json"
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
