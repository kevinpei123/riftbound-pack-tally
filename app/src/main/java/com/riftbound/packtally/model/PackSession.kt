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
    initialEntries: List<ScannedEntry> = emptyList(),
) {

    private val _entries = MutableStateFlow(initialEntries)
    val entries: StateFlow<List<ScannedEntry>> = _entries.asStateFlow()

    private val _runningTotal = MutableStateFlow(
        initialEntries.sumOf { it.price.marketPrice },
    )
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

    /** Remove the entry with [entryId]. Returns false if not present. */
    fun removeEntry(entryId: String): Boolean {
        val current = _entries.value
        val updated = current.filterNot { it.id == entryId }
        if (updated.size == current.size) return false
        _entries.value = updated
        _runningTotal.value = updated.sumOf { it.price.marketPrice }
        return true
    }

    /** Replace the entry with [entryId] in place. Returns false if not present. */
    fun replaceEntry(entryId: String, newEntry: ScannedEntry): Boolean {
        val current = _entries.value
        val index = current.indexOfFirst { it.id == entryId }
        if (index < 0) return false
        val updated = current.toMutableList().apply { set(index, newEntry) }
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
