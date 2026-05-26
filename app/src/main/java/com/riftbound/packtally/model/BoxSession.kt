package com.riftbound.packtally.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.UUID

/**
 * A Riftbound booster box (default mode = [Mode.BOX], 24 packs) or a single-pack
 * session ([Mode.SINGLE_PACK], capacity 1).
 *
 * Owns pack creation: [appendToActivePack] auto-rolls into a new pack when the
 * current one fills (up to [capacity]). [startNextPack] is the explicit version
 * driven by the "Complete pack →" UI button. Both honor the mode-derived
 * capacity.
 *
 * In-memory only; persistence TBD.
 */
class BoxSession(
    val id: String = UUID.randomUUID().toString(),
    val startedAt: Instant = Instant.now(),
    val mode: Mode = Mode.BOX,
) {

    enum class Mode { SINGLE_PACK, BOX }

    private val _packs = MutableStateFlow<List<PackSession>>(emptyList())
    val packs: StateFlow<List<PackSession>> = _packs.asStateFlow()

    private val _grandTotal = MutableStateFlow(0.0)
    val grandTotal: StateFlow<Double> = _grandTotal.asStateFlow()

    /** Maximum number of [PackSession]s allowed in this session, per [mode]. */
    val capacity: Int
        get() = when (mode) {
            Mode.SINGLE_PACK -> 1
            Mode.BOX -> BOX_CAPACITY
        }

    val packCount: Int get() = _packs.value.size

    val isFull: Boolean
        get() {
            val current = _packs.value
            return current.size >= capacity && current.last().isFull
        }

    /** Append entry to the active pack, auto-creating a new pack if needed. */
    fun appendToActivePack(entry: ScannedEntry): Boolean {
        val pack = currentOpenPackOrStart() ?: return false
        if (!pack.addEntry(entry)) return false
        recomputeGrandTotal()
        return true
    }

    /**
     * Explicitly start the next pack. Returns false if the current pack isn't
     * full yet, or the box has reached [capacity].
     */
    fun startNextPack(): Boolean {
        val current = _packs.value.lastOrNull()
        if (current?.isFull != true) return false
        if (_packs.value.size >= capacity) return false
        _packs.value = _packs.value + PackSession()
        return true
    }

    /** Locate the pack containing [entryId] and remove the entry. */
    fun removeEntry(entryId: String): Boolean {
        for (pack in _packs.value) {
            if (pack.removeEntry(entryId)) {
                recomputeGrandTotal()
                return true
            }
        }
        return false
    }

    /** Locate the pack containing [entryId] and replace the entry in place. */
    fun replaceEntry(entryId: String, newEntry: ScannedEntry): Boolean {
        for (pack in _packs.value) {
            if (pack.replaceEntry(entryId, newEntry)) {
                recomputeGrandTotal()
                return true
            }
        }
        return false
    }

    private fun currentOpenPackOrStart(): PackSession? {
        val current = _packs.value.lastOrNull()
        return when {
            current != null && !current.isFull -> current
            _packs.value.size < capacity -> {
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
        const val BOX_CAPACITY = 24
    }
}
