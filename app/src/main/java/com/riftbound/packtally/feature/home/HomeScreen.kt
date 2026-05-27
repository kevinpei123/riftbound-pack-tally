package com.riftbound.packtally.feature.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.feature.collection.CollectionViewModel
import com.riftbound.packtally.feature.pack.PackViewModel
import com.riftbound.packtally.model.BoxSession
import com.riftbound.packtally.ui.currency.LocalCurrencyFormatter

@Composable
fun HomeScreen(onNavigateToScanner: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val packVm: PackViewModel = viewModel(viewModelStoreOwner = activity)
    val collectionVm: CollectionViewModel = viewModel()
    val box by packVm.box.collectAsStateWithLifecycle()
    val packs by box.packs.collectAsStateWithLifecycle()
    val grandTotal by box.grandTotal.collectAsStateWithLifecycle()
    val isActive = packs.isNotEmpty()
    val collectionState by collectionVm.state.collectAsStateWithLifecycle()
    val pendingPrice by collectionVm.pendingPriceCount.collectAsStateWithLifecycle()

    var selectedMode by remember { mutableStateOf(box.mode) }
    LaunchedEffect(box) { selectedMode = box.mode }

    // Refresh on every entry so the summary reflects loose scans / pack edits.
    LaunchedEffect(Unit) { collectionVm.refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text("Riftbound Pack Tally", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        CollectionSummaryCard(
            totalCards = collectionState.totalCards,
            totalValue = collectionState.totalValue,
            pendingPrice = pendingPrice,
        )
        Spacer(Modifier.height(16.dp))

        if (isActive) {
            ActiveSessionCard(
                mode = box.mode,
                packCount = packs.size,
                boxCapacity = box.capacity,
                grandTotal = grandTotal,
                onContinue = onNavigateToScanner,
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text(
                "Start a new session",
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            Text(
                "What are you tracking?",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(12.dp))

        ModeRadio(
            label = "Single Pack",
            description = "Track one booster's value.",
            selected = selectedMode == BoxSession.Mode.SINGLE_PACK,
            onClick = { selectedMode = BoxSession.Mode.SINGLE_PACK },
        )
        Spacer(Modifier.height(8.dp))
        ModeRadio(
            label = "Open a Box (24 packs)",
            description = "Auto-roll into the next pack as each fills.",
            selected = selectedMode == BoxSession.Mode.BOX,
            onClick = { selectedMode = BoxSession.Mode.BOX },
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                packVm.startNewSession(selectedMode)
                onNavigateToScanner()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isActive) "Start new session" else "Start scanning")
        }
    }
}

@Composable
private fun CollectionSummaryCard(
    totalCards: Int,
    totalValue: Double,
    pendingPrice: Int,
) {
    val formatter = LocalCurrencyFormatter.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Your collection",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        formatter.format(totalValue),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "$totalCards card" + if (totalCards == 1) "" else "s",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (pendingPrice > 0) {
                    Text(
                        "$pendingPrice pending price",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveSessionCard(
    mode: BoxSession.Mode,
    packCount: Int,
    boxCapacity: Int,
    grandTotal: Double,
    onContinue: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = when (mode) {
                    BoxSession.Mode.SINGLE_PACK -> "Active: single pack"
                    BoxSession.Mode.BOX -> "Active: box — $packCount / $boxCapacity packs"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Total so far: ${LocalCurrencyFormatter.current.format(grandTotal)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Continue scanning →") }
        }
    }
}

@Composable
private fun ModeRadio(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(selected = selected, onClick = null)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
