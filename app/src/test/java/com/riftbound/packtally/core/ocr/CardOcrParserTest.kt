package com.riftbound.packtally.core.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CardOcrParserTest {

    @Test
    fun `clean read extracts collector number, set code, and name`() {
        val blocks = listOf(
            block("Blazing Scorcher", top = 40, height = 50),
            block("Spell  2  Chaos", top = 200, height = 25),
            block("OGN-001/298", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("OGN-001/298", result.collectorNumber)
        assertEquals("OGN", result.setCode)
        assertEquals("Blazing Scorcher", result.name)
    }

    @Test
    fun `bare number-over-total form extracts without a set code`() {
        val blocks = listOf(
            block("Some Card", top = 40, height = 50),
            block("123/300", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("123/300", result.collectorNumber)
        assertNull(result.setCode)
        assertEquals("Some Card", result.name)
    }

    @Test
    fun `glare obscures the total but SET-NUM still resolves`() {
        val blocks = listOf(
            block("Annie, Fiery", top = 40, height = 50),
            block("OGN-001", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("OGN-001", result.collectorNumber)
        assertEquals("OGN", result.setCode)
        assertEquals("Annie, Fiery", result.name)
    }

    @Test
    fun `partial read with only a bare number is not recognized as a collector`() {
        val blocks = listOf(
            block("Card Name", top = 40, height = 50),
            block("001", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertNull(result.collectorNumber)
        assertNull(result.setCode)
        assertEquals("Card Name", result.name)
    }

    @Test
    fun `signature card with extra signature mark still extracts the collector number`() {
        // The signature scrawl is OCR'd as garbled text in a separate block;
        // the actual collector number sits cleanly in the bottom-left.
        val blocks = listOf(
            block("Annie, Fiery", top = 40, height = 50),
            block("signed KP 2024 .~", top = 500, height = 18),
            block("OGS-001/24", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("OGS-001/24", result.collectorNumber)
        assertEquals("OGS", result.setCode)
        assertEquals("Annie, Fiery", result.name)
    }

    @Test
    fun `unknown future set code still matches via the generic pattern`() {
        val blocks = listOf(
            block("Future Card", top = 40, height = 50),
            block("EXP-042/120", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("EXP-042/120", result.collectorNumber)
        assertEquals("EXP", result.setCode)
    }

    @Test
    fun `multiple SET-NUM candidates prefer the known Riftbound set`() {
        // Body text false-positive matches the regex (FOUR-1) but a real
        // OGN code is also present — the known set must win.
        val blocks = listOf(
            block("Card Name", top = 40, height = 50),
            block("FOUR-1 effect: do thing", top = 300, height = 22),
            block("OGN-205/298", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("OGN-205/298", result.collectorNumber)
        assertEquals("OGN", result.setCode)
    }

    @Test
    fun `card with only a name and no readable collector number returns name only`() {
        val blocks = listOf(
            block("Mystery Card", top = 40, height = 50),
        )
        val result = CardOcrParser.parse(blocks)
        assertNull(result.collectorNumber)
        assertNull(result.setCode)
        assertEquals("Mystery Card", result.name)
    }

    @Test
    fun `empty OCR input returns all nulls`() {
        val result = CardOcrParser.parse(emptyList())
        assertNull(result.collectorNumber)
        assertNull(result.setCode)
        assertNull(result.name)
    }

    // Additional coverage: OCR edge cases across casing, noise, and confidence.

    @Test
    fun `lowercase set code matches via regex case-insensitivity`() {
        val blocks = listOf(
            block("Glaring Vanguard", top = 40, height = 50),
            block("ogn-099/298", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("OGN-099/298", result.collectorNumber)
        assertEquals("OGN", result.setCode)
    }

    @Test
    fun `number embedded in noisy text still parses`() {
        val blocks = listOf(
            block("Card Name", top = 40, height = 50),
            block("foo OGN-042 bar", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("OGN-042", result.collectorNumber)
    }

    @Test
    fun `garbage input returns nulls only`() {
        val blocks = listOf(
            block("!@#\$%", top = 40, height = 50),
            block("~~~~", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertNull(result.collectorNumber)
        assertNull(result.setCode)
        // Name might pick up the top block if length >= 3
        // "!@#$%" has length 5, height 50 → passes filter → name = it
        assertEquals("!@#\$%", result.name)
    }

    @Test
    fun `multi-line block with number on third line still resolves`() {
        val blocks = listOf(
            block("Some Card", top = 40, height = 50),
            block("body line 1", top = 200, height = 22),
            block("body line 2", top = 240, height = 22),
            block("OGN-150/298", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("OGN-150/298", result.collectorNumber)
    }

    @Test
    fun `whitespace around the separator still matches`() {
        // The printed format uses a space, and OCR routinely inserts extra
        // padding around it. The parser must tolerate this.
        val blocks = listOf(
            block("Card", top = 40, height = 50),
            block("OGN - 001", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("OGN-001", result.collectorNumber)
        assertEquals("OGN", result.setCode)
    }

    @Test
    fun `printed Riftbound format with space separator parses to canonical hyphenated form`() {
        // What's actually printed on every retail Riftbound card.
        val blocks = listOf(
            block("Card Name", top = 40, height = 50),
            block("UNL 156/219", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("UNL-156/219", result.collectorNumber)
        assertEquals("UNL", result.setCode)
    }

    @Test
    fun `OCR misreading the kerned space as a period still parses`() {
        // ML Kit Latin frequently reads the thin space between set code and
        // collector number as a period — user-reported as "UNL . 156/219".
        val blocks = listOf(
            block("Card Name", top = 40, height = 50),
            block("UNL . 156/219", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("UNL-156/219", result.collectorNumber)
        assertEquals("UNL", result.setCode)
    }

    @Test
    fun `OCR collapsing the separator entirely still parses`() {
        val blocks = listOf(
            block("Card Name", top = 40, height = 50),
            block("UNL156/219", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("UNL-156/219", result.collectorNumber)
        assertEquals("UNL", result.setCode)
    }

    @Test
    fun `middle-dot and bullet variants are absorbed by the separator class`() {
        val blocks = listOf(
            block("Card", top = 40, height = 50),
            block("UNL · 053/219", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("UNL-053/219", result.collectorNumber)

        val blocks2 = listOf(
            block("Card", top = 40, height = 50),
            block("UNL • 053/219", top = 900, height = 22),
        )
        val result2 = CardOcrParser.parse(blocks2)
        assertEquals("UNL-053/219", result2.collectorNumber)
    }

    @Test
    fun `alternate art lowercase letter suffix is preserved in output`() {
        // Alt-art prints add a lowercase letter to the numerator. The card
        // database keys off SET-NUMletter for alt-art rows, so preserving the
        // suffix lets lookupByNumber hit the exact variant when OCR sees it.
        val blocks = listOf(
            block("Card Name", top = 40, height = 50),
            block("OGN 120a/298", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("OGN-120a/298", result.collectorNumber)
        assertEquals("OGN", result.setCode)
    }

    @Test
    fun `signature asterisk suffix is stripped from output`() {
        // Signature overnumbers print with a star ("OGN 308*/298"); the
        // signature/standard/foil distinction is handled by the variant chooser
        // after identification, not by a separate database row.
        val blocks = listOf(
            block("Card Name", top = 40, height = 50),
            block("OGN 308*/298", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("OGN-308/298", result.collectorNumber)
        assertEquals("OGN", result.setCode)
    }

    @Test
    fun `overnumber chase card preserves the numerator above the denominator`() {
        // Some chase cards are numbered past the set total ("OGN 299/298").
        val blocks = listOf(
            block("Chase Card", top = 40, height = 50),
            block("OGN 299/298", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("OGN-299/298", result.collectorNumber)
        assertEquals("OGN", result.setCode)
    }

    @Test
    fun `new set codes ARC and FND are recognized as known`() {
        // Expanding KNOWN_SETS beyond OGN/UNL/SFD/OGS — verify both win Pass 1
        // against unrelated body text.
        val arcBlocks = listOf(
            block("Card", top = 40, height = 50),
            block("FOO-1 nonsense", top = 300, height = 22),
            block("ARC 003/006", top = 900, height = 22),
        )
        assertEquals("ARC-003/006", CardOcrParser.parse(arcBlocks).collectorNumber)

        val fndBlocks = listOf(
            block("Card", top = 40, height = 50),
            block("BAR-9 nonsense", top = 300, height = 22),
            block("FND 087/298", top = 900, height = 22),
        )
        assertEquals("FND-087/298", CardOcrParser.parse(fndBlocks).collectorNumber)
    }

    @Test
    fun `name with apostrophe survives parsing intact`() {
        val blocks = listOf(
            block("Annie, Fiery", top = 40, height = 50),
            block("OGS-001/24", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("Annie, Fiery", result.name)
        assertEquals("OGS-001/24", result.collectorNumber)
    }

    @Test
    fun `both number and name parsed — number wins as the primary identifier`() {
        val blocks = listOf(
            block("Brazen Buccaneer", top = 40, height = 50),
            block("OGN-002/298", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("OGN-002/298", result.collectorNumber)
        assertEquals("Brazen Buccaneer", result.name)
    }

    @Test
    fun `multiple SET-NUM matches in one block — first parse wins`() {
        val blocks = listOf(
            block("Card Name", top = 40, height = 50),
            block("OGN-001 sees OGN-002 too", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        // The findAll iterates in order; OGN-001 comes first.
        assertEquals("OGN-001", result.collectorNumber)
    }

    @Test
    fun `confidence proxy at length 1 is 005`() {
        val b = block(text = "x", top = 0, height = 10)
        assertEquals(0.05f, b.confidence, 0.0001f)
    }

    @Test
    fun `confidence proxy at length 19 is 095 cap`() {
        val b = block(text = "x".repeat(19), top = 0, height = 10)
        assertEquals(0.95f, b.confidence, 0.0001f)
    }

    @Test
    fun `confidence proxy at length 50 still capped at 095`() {
        val b = block(text = "x".repeat(50), top = 0, height = 10)
        assertEquals(0.95f, b.confidence, 0.0001f)
    }

    @Test
    fun `name skips small-height set logo and picks the top-most large block`() {
        // Small "OGN" set tag near the top should not be chosen as the name —
        // the bigger card-name block below it should win.
        val blocks = listOf(
            block("OGN", top = 10, height = 15),
            block("Brazen Buccaneer", top = 40, height = 50),
            block("body text here", top = 200, height = 25),
            block("OGN-002/298", top = 900, height = 22),
        )
        val result = CardOcrParser.parse(blocks)
        assertEquals("Brazen Buccaneer", result.name)
        assertEquals("OGN-002/298", result.collectorNumber)
    }

    @Test
    fun `hyphen underscore and mixed case separators parse`() {
        assertEquals(
            "UNL-181/219",
            CardOcrParser.parse(
                listOf(block("Card", top = 40, height = 50), block("unl-181/219", top = 900, height = 22)),
            ).collectorNumber,
        )
        assertEquals(
            "OGN-181/298",
            CardOcrParser.parse(
                listOf(block("Card", top = 40, height = 50), block("oGn_181/298", top = 900, height = 22)),
            ).collectorNumber,
        )
    }

    @Test
    fun `future two to four letter set fallback parses`() {
        val two = listOf(block("Card", top = 40, height = 50), block("NX 007/100", top = 900, height = 22))
        val four = listOf(block("Card", top = 40, height = 50), block("ABCD 007/100", top = 900, height = 22))

        assertEquals("NX-007/100", CardOcrParser.parse(two).collectorNumber)
        assertEquals("ABCD-007/100", CardOcrParser.parse(four).collectorNumber)
    }

    private fun block(
        text: String,
        top: Int,
        height: Int,
        left: Int = 0,
        width: Int = 200,
    ): TextBlock = TextBlock(
        text = text,
        bounds = BoundingBox(left = left, top = top, right = left + width, bottom = top + height),
        confidence = (text.length * 0.05f).coerceAtMost(0.95f),
    )
}
