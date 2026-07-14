package com.riftbound.packtally.feature.firstlaunch

import android.app.Application
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.App
import com.riftbound.packtally.core.carddb.CardDbSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

private const val TAG = "FirstLaunch"

/**
 * Shown on first launch (and any time the Riftcodex sync needs to run) until
 * the local card DB is populated. Blocks AppNav until done.
 */
@Composable
fun FirstLaunchScreen(onSyncComplete: () -> Unit) {
    val vm: FirstLaunchViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.startSync() }
    LaunchedEffect(state) {
        if (state is FirstLaunchState.Done) onSyncComplete()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Setting up card database",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Pulling the latest Riftbound cards from Riftcodex — about 1000 " +
                "cards, ~5 seconds. This only happens once.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))

        when (val s = state) {
            FirstLaunchState.Idle, FirstLaunchState.Syncing -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    "Fetching cards…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            is FirstLaunchState.Done -> {
                Text(
                    "Loaded ${s.cardCount} cards.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            is FirstLaunchState.Failed -> {
                Box(Modifier.padding(top = 8.dp)) {
                    Text(
                        s.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = vm::startSync,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Retry") }
            }
        }
    }
}

sealed interface FirstLaunchState {
    data object Idle : FirstLaunchState
    data object Syncing : FirstLaunchState
    data class Done(val cardCount: Int) : FirstLaunchState
    data class Failed(val reason: String) : FirstLaunchState
}

class FirstLaunchViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val sync: CardDbSync = app.cardDbSync

    private val _state = MutableStateFlow<FirstLaunchState>(FirstLaunchState.Idle)
    val state: StateFlow<FirstLaunchState> = _state.asStateFlow()

    fun startSync() {
        // Guard against re-running an in-flight or already-completed sync; a fresh
        // ViewModel's LaunchedEffect must not overwrite a Done state and re-fetch.
        // Failed stays re-runnable for the Retry button.
        val current = _state.value
        if (current is FirstLaunchState.Syncing || current is FirstLaunchState.Done) return
        _state.value = FirstLaunchState.Syncing
        viewModelScope.launch {
            try {
                val count = sync.runFullSync()
                // Re-init the in-memory CardDatabase so lookups see the new rows.
                com.riftbound.packtally.core.carddb.CardDatabase.initFromRoom(app.cardDao)
                _state.value = FirstLaunchState.Done(count)
            } catch (e: Throwable) {
                Log.e(TAG, "Riftcodex sync failed", e)
                val message = when (e) {
                    is UnknownHostException, is ConnectException ->
                        "No internet connection. Check your network and tap Retry."
                    is SocketTimeoutException ->
                        "The server took too long to respond. Tap Retry."
                    is retrofit2.HttpException ->
                        "Card service is temporarily unavailable (error ${e.code()}). Tap Retry."
                    else ->
                        "Couldn't set up the card database. Tap Retry, or try again later."
                }
                _state.value = FirstLaunchState.Failed(message)
            }
        }
    }
}
