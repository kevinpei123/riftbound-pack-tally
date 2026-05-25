package com.riftbound.packtally.core.ocr

data class CardIdentifier(
    val collectorNumber: String?,
    val setCode: String?,
    val name: String?,
)

object CardOcrParser {

    private val KNOWN_SETS = setOf("OGN", "UNL", "SFD", "OGS")

    private val SET_NUM_REGEX = Regex(
        """\b([A-Z]{2,4})-(\d{1,4})(?:/(\d{1,4}))?\b""",
    )
    private val NUM_TOTAL_REGEX = Regex("""\b(\d{1,4})/(\d{1,4})\b""")

    fun parse(ocrBlocks: List<TextBlock>): CardIdentifier {
        val collector = findCollectorNumber(ocrBlocks)
        val name = findCardName(ocrBlocks)
        return CardIdentifier(
            collectorNumber = collector?.collectorNumber,
            setCode = collector?.setCode,
            name = name,
        )
    }

    private data class CollectorMatch(val collectorNumber: String, val setCode: String?)

    private fun findCollectorNumber(blocks: List<TextBlock>): CollectorMatch? {
        // Collector numbers live in the bottom-left of a card, so prefer blocks
        // farther from the top.
        val bottomFirst = blocks.sortedByDescending { it.bounds.top }

        // Pass 1: any SET-NUM match where the set is a known Riftbound set.
        for (block in bottomFirst) {
            SET_NUM_REGEX.findAll(block.text.uppercase()).forEach { m ->
                val set = m.groupValues[1]
                if (set in KNOWN_SETS) {
                    return CollectorMatch(formatSetNum(m), set)
                }
            }
        }

        // Pass 2: any SET-NUM match, even with an unknown set code
        // (forward-compat for future Riftbound sets).
        for (block in bottomFirst) {
            val m = SET_NUM_REGEX.find(block.text.uppercase()) ?: continue
            return CollectorMatch(formatSetNum(m), m.groupValues[1])
        }

        // Pass 3: bare NUM/TOTAL form.
        for (block in bottomFirst) {
            val m = NUM_TOTAL_REGEX.find(block.text) ?: continue
            return CollectorMatch("${m.groupValues[1]}/${m.groupValues[2]}", null)
        }

        return null
    }

    private fun formatSetNum(match: MatchResult): String {
        val set = match.groupValues[1]
        val num = match.groupValues[2]
        val total = match.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }
        return if (total != null) "$set-$num/$total" else "$set-$num"
    }

    private fun findCardName(blocks: List<TextBlock>): String? {
        if (blocks.isEmpty()) return null
        val maxHeight = blocks.maxOf { it.bounds.height }
        val threshold = (maxHeight * 0.6).toInt().coerceAtLeast(1)
        return blocks
            .filter { it.bounds.height >= threshold && it.text.length >= 3 }
            .minByOrNull { it.bounds.top }
            ?.text
            ?.trim()
    }
}
