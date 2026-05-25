package com.riftbound.packtally.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.UUID

/**
 * One Riftbound booster pack's worth of scanned cards.
 *
 * Stateful rather than a Kotlin `data class` because it owns reactive state
 * ([entries], [runningTotal]) — `data class` equality/copy semantics don't
 * mix well with [StateFlow] members.
 *
 * Standard pack capacity is [CAPACITY] cards in the breakdown
 * [COMMON_SLOTS]/[UNCOMMON_SLOTS]/[RARE_PLUS_SLOTS]/[FOIL_SLOTS]/[RUNE_TOKEN_SLOTS].
 * The breakdown is informational — [addEntry] only enforces the total count cap.
 */
class PackSession(
    val id: String = UUID.randomUUID().toString(),
    val startedAt: Instant = Instant.now(),
) {

    private val _entries = MutableStateFlow<List<ScannedEntry>>(emptyList())
    val entries: StateFlow<List<ScannedEntry>> = _entries.asStateFlow()

    private val _runningTotal = MutableStateFlow(0.0)
    val runningTotal: StateFlow<Double> = _runningTotal.asStateFlow()

    val size: Int get() = _entries.value.size
    val isFull: Boolean get() = size >= CAPACITY

    /** Append an entry. Returns false (no-op) if the pack is already full. */
    fun addEntry(entry: ScannedEntry): Boolean {
        if (isFull) return false
        val updated = _entries.value + entry
        _entries.value = updated
        _runningTotal.value = updated.sumOf { it.price.marketPrice }
        return true
    }

    companion object {
        const val CAPACITY = 14

        // Riftbound booster slot breakdown (7 + 3 + 2 + 1 + 1 = 14).
        // Informational only — [addEntry] doesn't enforce rarity-by-slot.
        const val COMMON_SLOTS = 7
        const val UNCOMMON_SLOTS = 3
        const val RARE_PLUS_SLOTS = 2
        const val FOIL_SLOTS = 1
        const val RUNE_TOKEN_SLOTS = 1
    }
}
