package com.riftbound.packtally.feature.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riftbound.packtally.App
import com.riftbound.packtally.model.PricingStatus
import com.riftbound.packtally.model.ScanSession
import com.riftbound.packtally.model.ScanSessionStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val activeSession: ScanSession? = null,
    val lastCompletedSession: ScanSession? = null,
    val totalCards: Int = 0,
    val uniqueCards: Int = 0,
    val totalValueUsd: Double = 0.0,
    val pendingPrices: Int = 0,
    val isLoading: Boolean = true,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val repository = app.sessionRepository

    val state: StateFlow<HomeUiState> =
        combine(
            repository.observeActiveSession(),
            repository.observeSessions(),
            repository.observeAllEntries(),
        ) { active, sessions, entries ->
            HomeUiState(
                activeSession = active,
                lastCompletedSession = sessions.firstOrNull { it.status == ScanSessionStatus.COMPLETED },
                totalCards = entries.size,
                uniqueCards = entries.map { it.card.id }.distinct().size,
                totalValueUsd = entries.sumOf { it.marketPrice },
                pendingPrices = entries.count {
                    it.pricingStatus == PricingStatus.PENDING || it.pricingStatus == PricingStatus.FAILED
                },
                isLoading = false,
            )
        }
            .catch { e ->
                // If an upstream Room/DataStore Flow fails (e.g. DB corruption),
                // the combined Flow would otherwise terminate and freeze the UI on
                // its last value. Log and fall back to a non-loading empty state.
                Log.e("HomeViewModel", "Failed to observe home data", e)
                emit(HomeUiState(isLoading = false))
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    fun startNewSession(onStarted: () -> Unit) {
        viewModelScope.launch {
            repository.startNewSession()
            onStarted()
        }
    }
}
