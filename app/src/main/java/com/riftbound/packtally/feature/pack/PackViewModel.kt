package com.riftbound.packtally.feature.pack

import androidx.lifecycle.ViewModel
import com.riftbound.packtally.model.PackSession
import com.riftbound.packtally.model.ScannedEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class PackViewModel : ViewModel() {

    private val _session = MutableStateFlow(PackSession.empty())
    val session: StateFlow<PackSession> = _session.asStateFlow()

    fun append(entry: ScannedEntry) {
        _session.update { it.copy(entries = it.entries + entry) }
    }

    fun startNewSession() {
        _session.value = PackSession.empty()
    }
}
