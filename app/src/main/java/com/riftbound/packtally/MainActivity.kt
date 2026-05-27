package com.riftbound.packtally

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.core.settings.AppSettings
import com.riftbound.packtally.feature.firstlaunch.FirstLaunchScreen
import com.riftbound.packtally.ui.currency.CurrencyFormatter
import com.riftbound.packtally.ui.currency.LocalCurrencyFormatter
import com.riftbound.packtally.ui.nav.AppNav
import com.riftbound.packtally.ui.theme.RiftboundPackTallyTheme
import kotlinx.coroutines.flow.map
import java.time.Instant

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        setContent {
            RiftboundPackTallyTheme {
                val settings by app.settingsRepository.settings
                    .collectAsStateWithLifecycle(initialValue = AppSettings())
                val formatter = remember(settings.currency, settings.usdToTargetRate) {
                    CurrencyFormatter(settings.currency, settings.usdToTargetRate)
                }

                // Gate the entire UI on Riftcodex sync state. Keep Loading
                // distinct from "loaded null"; otherwise a synced cold start
                // can mount FirstLaunchScreen and start a duplicate sync before
                // DataStore emits the persisted timestamp.
                val syncGate by app.cardDbSync.lastSyncedAt
                    .map<Instant?, SyncGate> { SyncGate.Loaded(it) }
                    .collectAsStateWithLifecycle(initialValue = SyncGate.Loading)
                var cardDbLoaded by remember { mutableStateOf(CardDatabase.isReady()) }

                // Once synced (or on every recomposition where the in-memory db isn't loaded),
                // hydrate CardDatabase from Room and run the one-time v2→v3 backfill.
                LaunchedEffect(syncGate) {
                    val syncedAt = (syncGate as? SyncGate.Loaded)?.syncedAt
                    if (syncedAt != null && !CardDatabase.isReady()) {
                        runCatching {
                            CardDatabase.initFromRoom(app.cardDao)
                            runCatching { app.backfillJob.runIfNeeded() }
                        }.onFailure {
                            Log.e(TAG, "Card database hydration failed", it)
                        }
                        cardDbLoaded = true
                    }
                }

                CompositionLocalProvider(LocalCurrencyFormatter provides formatter) {
                    // Also gate on size>0 so an older build that "succeeded" with
                    // 0 cards (e.g. before the Riftcodex envelope fix) still
                    // routes through FirstLaunchScreen and re-runs the sync.
                    when (val gate = syncGate) {
                        SyncGate.Loading -> LoadingGateScreen("Loading settings")
                        is SyncGate.Loaded -> when {
                            gate.syncedAt == null -> {
                                FirstLaunchScreen(
                                    onSyncComplete = {
                                        cardDbLoaded = CardDatabase.isReady()
                                    },
                                )
                            }
                            !cardDbLoaded -> LoadingGateScreen("Loading card database")
                            CardDatabase.size == 0 -> {
                                FirstLaunchScreen(
                                    onSyncComplete = {
                                        cardDbLoaded = CardDatabase.isReady()
                                    },
                                )
                            }
                            else -> AppNav()
                        }
                    }
                }
            }
        }
    }
}

private sealed interface SyncGate {
    data object Loading : SyncGate
    data class Loaded(val syncedAt: Instant?) : SyncGate
}

@Composable
private fun LoadingGateScreen(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
