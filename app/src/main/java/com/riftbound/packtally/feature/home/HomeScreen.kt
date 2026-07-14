package com.riftbound.packtally.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.model.ScanSession
import com.riftbound.packtally.ui.currency.LocalCurrencyFormatter
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Localized short date/time formatter for the "last finished session" timestamp.
 * Hoisted to a top-level val so it is allocated once (not per recomposition) and
 * follows the device locale / 12-24h preference instead of a hardcoded pattern.
 */
private val LAST_SESSION_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

@Composable
fun HomeScreen(
    onNavigateToScanner: () -> Unit,
    onNavigateToCurrent: () -> Unit,
    onNavigateToCollection: () -> Unit,
) {
    val vm: HomeViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Riftbound Pack Tally", style = MaterialTheme.typography.headlineSmall)

        PrimarySessionCard(
            activeSession = state.activeSession,
            onStart = { vm.startNewSession(onNavigateToScanner) },
            onContinue = onNavigateToCurrent,
        )

        CollectionSummaryCard(
            totalCards = state.totalCards,
            uniqueCards = state.uniqueCards,
            totalValue = state.totalValueUsd,
            pendingPrices = state.pendingPrices,
            onOpenCollection = onNavigateToCollection,
        )

        LastSessionCard(session = state.lastCompletedSession)
    }
}

@Composable
private fun PrimarySessionCard(
    activeSession: ScanSession?,
    onStart: () -> Unit,
    onContinue: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Scan session", style = MaterialTheme.typography.titleMedium)
            if (activeSession == null) {
                Text(
                    "Start one running list for every card you scan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                    Text("Start new scan session")
                }
            } else {
                Text(
                    activeSession.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${activeSession.totalCards} cards",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${activeSession.pendingPriceCount} to price",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onContinue, modifier = Modifier.weight(1f)) {
                        Text("Current list")
                    }
                    OutlinedButton(onClick = { showConfirm = true }, modifier = Modifier.weight(1f)) {
                        Text("New")
                    }
                }
            }
        }
    }

    if (showConfirm && activeSession != null) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Start a new session?") },
            text = {
                Text(
                    buildString {
                        append("Your current session has ${activeSession.totalCards} cards")
                        if (activeSession.pendingPriceCount > 0) {
                            append(" and ${activeSession.pendingPriceCount} pending prices")
                        }
                        append(". Starting a new one will finish the current session.")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onStart()
                }) {
                    Text("Start new")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun CollectionSummaryCard(
    totalCards: Int,
    uniqueCards: Int,
    totalValue: Double,
    pendingPrices: Int,
    onOpenCollection: () -> Unit,
) {
    val formatter = LocalCurrencyFormatter.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Collection", style = MaterialTheme.typography.titleMedium)
            Text(
                formatter.format(totalValue),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "$totalCards cards, $uniqueCards unique, $pendingPrices pending prices",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenCollection, modifier = Modifier.fillMaxWidth()) {
                Text("Open collection")
            }
        }
    }
}

@Composable
private fun LastSessionCard(session: ScanSession?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Last finished session", style = MaterialTheme.typography.titleMedium)
            if (session == null) {
                Text(
                    "No completed sessions yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    session.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${session.totalCards} cards, finished ${
                        session.completedAt
                            ?.atZone(ZoneId.systemDefault())
                            ?.format(LAST_SESSION_FORMAT)
                            ?: "recently"
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
