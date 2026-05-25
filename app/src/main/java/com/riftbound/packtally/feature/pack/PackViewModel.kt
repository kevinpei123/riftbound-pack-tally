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
     * new pack if the active one is full. Silently no-ops if the box is full —
     * call [startNewBox] when that happens.
     */
    fun append(entry: ScannedEntry) {
        _box.value.appendToActivePack(entry)
    }

    fun startNewBox() {
        _box.value = BoxSession()
    }
}
