package com.riftbound.packtally.core.ocr

data class CardIdentifier(
    val collectorNumber: String?,
    val setCode: String?,
    val name: String?,
)

object CardOcrParser {

    private val KNOWN_SETS = setOf("OGN", "OGS", "ARC", "SFD", "UNL", "FND")

    // Matches the bottom-left collector code. Riftbound prints it as
    // "SETCODE NUM/TOTAL" with a space (for example "UNL 156/219"), but OCR
    // often sees that thin gap as punctuation. The separator therefore accepts
    // whitespace, period, middle dot, bullet, hyphen, underscore, or nothing.
    //
    // Alternate-art suffixes are preserved because Riftcodex keys those rows
    // as SET-NUMletter. Signature stars are stripped because signature is a
    // user-selected variant, not a separate collector key.
    private val SET_NUM_REGEX = Regex(
        """\b([A-Z]{2,4})[\s.\u00B7\u2022\u2027\-_]*(\d{1,4})([A-Z])?\*?(?:\s*/\s*(\d{1,4}))?\b""",
        RegexOption.IGNORE_CASE,
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
            SET_NUM_REGEX.findAll(block.text).forEach { match ->
                val set = match.groupValues[1].uppercase()
                if (set in KNOWN_SETS) {
                    return CollectorMatch(formatSetNum(match), set)
                }
            }
        }

        // Pass 2: any SET-NUM match, even with an unknown set code
        // (forward-compat for future Riftbound sets).
        for (block in bottomFirst) {
            val match = SET_NUM_REGEX.find(block.text) ?: continue
            return CollectorMatch(formatSetNum(match), match.groupValues[1].uppercase())
        }

        // Pass 3: bare NUM/TOTAL form.
        for (block in bottomFirst) {
            val match = NUM_TOTAL_REGEX.find(block.text) ?: continue
            return CollectorMatch("${match.groupValues[1]}/${match.groupValues[2]}", null)
        }

        return null
    }

    private fun formatSetNum(match: MatchResult): String {
        val set = match.groupValues[1].uppercase()
        val num = match.groupValues[2]
        val suffix = match.groupValues.getOrNull(3)
            ?.takeIf { it.isNotEmpty() }
            ?.lowercase()
            .orEmpty()
        val total = match.groupValues.getOrNull(4)?.takeIf { it.isNotEmpty() }
        return if (total != null) "$set-$num$suffix/$total" else "$set-$num$suffix"
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
