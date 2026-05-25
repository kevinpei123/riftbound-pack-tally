package com.riftbound.packtally.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.UUID

/**
 * A Riftbound booster box — up to [CAPACITY] sequential [PackSession]s plus a
 * reactive [grandTotal] summed across every pack.
 *
 * Owns pack creation: [appendToActivePack] auto-rolls into a new pack when the
 * current one fills, and refuses (returns false) once the box itself is full.
 * That way the scan UI never has to think about pack boundaries.
 *
 * Like [PackSession], this is a stateful class rather than a `data class` —
 * it holds [StateFlow]s and lives in memory only (persistence TBD).
 */
class BoxSession(
    val id: String = UUID.randomUUID().toString(),
    val startedAt: Instant = Instant.now(),
) {

    private val _packs = MutableStateFlow<List<PackSession>>(emptyList())
    val packs: StateFlow<List<PackSession>> = _packs.asStateFlow()

    private val _grandTotal = MutableStateFlow(0.0)
    val grandTotal: StateFlow<Double> = _grandTotal.asStateFlow()

    val packCount: Int get() = _packs.value.size
    val isFull: Boolean
        get() {
            val current = _packs.value
            return current.size >= CAPACITY && current.last().isFull
        }

    /**
     * Append an entry to the currently open pack, starting a new one if needed.
     * Returns false when the box has no remaining capacity.
     */
    fun appendToActivePack(entry: ScannedEntry): Boolean {
        val pack = currentOpenPackOrStart() ?: return false
        if (!pack.addEntry(entry)) return false
        recomputeGrandTotal()
        return true
    }

    private fun currentOpenPackOrStart(): PackSession? {
        val current = _packs.value.lastOrNull()
        return when {
            current != null && !current.isFull -> current
            _packs.value.size < CAPACITY -> {
                val newPack = PackSession()
                _packs.value = _packs.value + newPack
                newPack
            }
            else -> null
        }
    }

    private fun recomputeGrandTotal() {
        _grandTotal.value = _packs.value.sumOf { it.runningTotal.value }
    }

    companion object {
        const val CAPACITY = 24
    }
}
