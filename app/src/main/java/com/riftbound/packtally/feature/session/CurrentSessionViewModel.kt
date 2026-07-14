package com.riftbound.packtally.feature.session

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riftbound.packtally.App
import com.riftbound.packtally.core.persistence.SessionRepository
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.ScanEntrySource
import com.riftbound.packtally.model.ScanSession
import com.riftbound.packtally.model.Variant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "CurrentSessionVM"

sealed interface CurrentSessionEvent {
    data object Started : CurrentSessionEvent
    data class Added(val name: String) : CurrentSessionEvent
    data object Removed : CurrentSessionEvent
    data object Undone : CurrentSessionEvent
    data object Cleared : CurrentSessionEvent
    data object Completed : CurrentSessionEvent
    data object Renamed : CurrentSessionEvent
    data class PricingDone(val priced: Int, val failed: Int, val unpriceable: Int) : CurrentSessionEvent
    data class Error(val message: String) : CurrentSessionEvent
}

class CurrentSessionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val repository: SessionRepository = app.sessionRepository

    private val _isLoading = MutableStateFlow(true)

    /**
     * True until [observeActiveSession] emits for the first time, letting the UI tell
     * "still reading the DB" apart from "there is genuinely no active session".
     */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val activeSession: StateFlow<ScanSession?> =
        repository.observeActiveSession()
            .onEach { _isLoading.value = false }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = null,
            )

    private val _submitInFlight = MutableStateFlow(false)
    val submitInFlight: StateFlow<Boolean> = _submitInFlight.asStateFlow()

    private val _pricingProgress = MutableStateFlow<String?>(null)
    val pricingProgress: StateFlow<String?> = _pricingProgress.asStateFlow()

    private val _events = MutableSharedFlow<CurrentSessionEvent>()
    val events: SharedFlow<CurrentSessionEvent> = _events.asSharedFlow()

    fun startNewSession() {
        viewModelScope.launch {
            runCatching { repository.startNewSession() }
                .onSuccess { _events.emit(CurrentSessionEvent.Started) }
                .onFailure { emitError("Could not start session", it) }
        }
    }

    fun addManual(card: RiftboundCard, variant: Variant) {
        viewModelScope.launch {
            runCatching {
                repository.addEntry(
                    card = card,
                    variant = variant,
                    source = ScanEntrySource.MANUAL,
                    confidence = 1.0f,
                )
            }.onSuccess {
                _events.emit(CurrentSessionEvent.Added(card.name))
            }.onFailure {
                emitError("Could not add card", it)
            }
        }
    }

    fun remove(entryId: String) {
        viewModelScope.launch {
            runCatching { repository.removeEntry(entryId) }
                .onSuccess { _events.emit(CurrentSessionEvent.Removed) }
                .onFailure { emitError("Could not remove entry", it) }
        }
    }

    fun undoLastScan() {
        viewModelScope.launch {
            runCatching { repository.undoLastScan() }
                .onSuccess { _events.emit(CurrentSessionEvent.Undone) }
                .onFailure { emitError("Could not undo", it) }
        }
    }

    fun changeVariant(entryId: String, variant: Variant) {
        viewModelScope.launch {
            runCatching { repository.changeVariant(entryId, variant) }
                .onFailure { emitError("Could not change variant", it) }
        }
    }

    fun clearSession() {
        viewModelScope.launch {
            runCatching { repository.clearActiveSession() }
                .onSuccess { _events.emit(CurrentSessionEvent.Cleared) }
                .onFailure { emitError("Could not clear session", it) }
        }
    }

    fun completeSession() {
        viewModelScope.launch {
            runCatching { repository.completeActiveSession() }
                .onSuccess { _events.emit(CurrentSessionEvent.Completed) }
                .onFailure { emitError("Could not complete session", it) }
        }
    }

    fun renameSession(name: String?) {
        viewModelScope.launch {
            val sessionId = activeSession.value?.id
            if (sessionId == null) {
                _events.emit(CurrentSessionEvent.Error("No active session"))
                return@launch
            }
            runCatching { repository.renameSession(sessionId, name) }
                .onSuccess { _events.emit(CurrentSessionEvent.Renamed) }
                .onFailure { emitError("Could not rename session", it) }
        }
    }

    fun submitPendingPrices() {
        if (_submitInFlight.value) return
        _submitInFlight.value = true
        viewModelScope.launch {
            val sessionId = activeSession.value?.id
            if (sessionId == null) {
                _submitInFlight.value = false
                _events.emit(CurrentSessionEvent.Error("No active session"))
                return@launch
            }
            val result = runCatching {
                repository.submitPendingPrices(
                    pricing = app.pricing,
                    sessionId = sessionId,
                    onProgress = { progress ->
                        _pricingProgress.value =
                            "Pricing batch ${progress.currentBatch} of ${progress.totalBatches}"
                    },
                )
            }
            _submitInFlight.value = false
            _pricingProgress.value = null
            result
                .onSuccess { submit ->
                    when (submit) {
                        SessionRepository.SubmitResult.Empty ->
                            _events.emit(CurrentSessionEvent.PricingDone(0, 0, 0))
                        is SessionRepository.SubmitResult.Done ->
                            _events.emit(
                                CurrentSessionEvent.PricingDone(
                                    priced = submit.priced,
                                    failed = submit.failed,
                                    unpriceable = submit.unpriceable,
                                ),
                            )
                    }
                }
                .onFailure { emitError("Pricing failed", it) }
        }
    }

    private suspend fun emitError(prefix: String, throwable: Throwable) {
        Log.e(TAG, prefix, throwable)
        _events.emit(CurrentSessionEvent.Error("$prefix: ${throwable.message ?: "unknown error"}"))
    }
}
