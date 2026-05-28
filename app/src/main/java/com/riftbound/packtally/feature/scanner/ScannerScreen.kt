package com.riftbound.packtally.feature.scanner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.App
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.Variant

private const val RESCAN_THRESHOLD = 0.7f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(onNavigateToCurrent: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as App
    val factory = remember(app) { ScannerViewModel.factory(app) }
    val scannerVm: ScannerViewModel = viewModel(factory = factory)

    val result by scannerVm.scanResult.collectAsStateWithLifecycle()
    val rapidMode by scannerVm.rapidMode.collectAsStateWithLifecycle()
    val activeSession by scannerVm.activeSession.collectAsStateWithLifecycle()
    val lastAdded by scannerVm.lastAdded.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        CameraScreen(onCardCaptured = scannerVm::onCardCaptured)

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            tonalElevation = 2.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Scan", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${activeSession?.totalCards ?: 0} cards - ${activeSession?.pendingPriceCount ?: 0} pending",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Rapid", style = MaterialTheme.typography.bodySmall)
                        Switch(checked = rapidMode, onCheckedChange = scannerVm::setRapidMode)
                    }
                }
                lastAdded?.let {
                    Text(
                        "Last added: ${it.card.name} (${it.variant.name.lowercase().replaceFirstChar { c -> c.uppercase() }})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = onNavigateToCurrent, modifier = Modifier.fillMaxWidth()) {
                    Text("Current session")
                }
            }
        }
    }

    val showSheet = result !is ScanResult.Idle && result !is ScanResult.Scanning
    if (showSheet) {
        ModalBottomSheet(onDismissRequest = scannerVm::reset) {
            when (val r = result) {
                is ScanResult.Identified -> IdentifiedContent(
                    card = r.card,
                    showRescan = r.confidence < RESCAN_THRESHOLD,
                    onVariantSelected = { variant -> scannerVm.recordCard(r.card, variant) },
                    onRescan = scannerVm::reset,
                )
                is ScanResult.Ambiguous -> AmbiguousContent(
                    candidates = r.candidates,
                    onPickCandidate = scannerVm::pickCandidate,
                    onRescan = scannerVm::reset,
                )
                is ScanResult.Failed -> FailedContent(
                    reason = r.reason,
                    onRescan = scannerVm::reset,
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun IdentifiedContent(
    card: RiftboundCard,
    showRescan: Boolean,
    onVariantSelected: (Variant) -> Unit,
    onRescan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(card.name, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${card.collectorNumber} - " +
                card.rarity.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onVariantSelected(Variant.STANDARD) },
                modifier = Modifier.weight(1f),
            ) { Text("Standard") }
            Button(
                onClick = { onVariantSelected(Variant.FOIL) },
                modifier = Modifier.weight(1f),
            ) { Text("Foil") }
            Button(
                onClick = { onVariantSelected(Variant.SIGNATURE) },
                modifier = Modifier.weight(1f),
            ) { Text("Signature") }
        }

        if (showRescan) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
                Text("Re-scan")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AmbiguousContent(
    candidates: List<RiftboundCard>,
    onPickCandidate: (RiftboundCard) -> Unit,
    onRescan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text("Multiple possible matches", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        candidates.forEach { card ->
            TextButton(
                onClick = { onPickCandidate(card) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(card.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        card.collectorNumber,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
            Text("Re-scan")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FailedContent(
    reason: String,
    onRescan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Could not identify card", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Retry with the collector number inside the guide, reduce glare, or add the card manually from Current.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
            Text("Retry")
        }
        Spacer(Modifier.height(16.dp))
    }
}
