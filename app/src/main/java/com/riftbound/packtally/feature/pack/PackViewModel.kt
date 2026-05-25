package com.riftbound.packtally.feature.pack

import androidx.lifecycle.ViewModel
import com.riftbound.packtally.model.BoxSession
import com.riftbound.packtally.model.ScannedEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PackViewModel : ViewModel() {

    private val _box = MutableStateFlow(BoxSession())
    val box: StateFlow<BoxSession> = _box.asStateFlow()

    /**
     * Append a scanned entry to the box's currently active pack. Auto-starts a
     * new pack if the active one is full. Silently no-ops when the session is
     * at capacity for its mode.
     */
    fun append(entry: ScannedEntry) {
        _box.value.appendToActivePack(entry)
    }

    /**
     * Driver for the "Complete pack →" / "Finish" UI button. If the box has
     * room for another pack, start one. Otherwise (single-pack done, or box
     * fully opened), restart the session in the same mode.
     */
    fun completePack() {
        val current = _box.value
        if (!current.startNextPack()) {
            _box.value = BoxSession(mode = current.mode)
        }
    }

    fun startNewSession(mode: BoxSession.Mode = _box.value.mode) {
        _box.value = BoxSession(mode = mode)
    }
}
