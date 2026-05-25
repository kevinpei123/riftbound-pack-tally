package com.riftbound.packtally.feature.scanner

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import com.riftbound.packtally.feature.pack.PackViewModel
import com.riftbound.packtally.model.RiftboundCard

private const val RESCAN_THRESHOLD = 0.7f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen() {
    val activity = LocalContext.current as ComponentActivity
    val app = activity.application as App

    val packVm: PackViewModel = viewModel(viewModelStoreOwner = activity)
    val factory = remember(app, packVm) { ScannerViewModel.factory(app, packVm) }
    val scannerVm: ScannerViewModel = viewModel(factory = factory)

    val result by scannerVm.scanResult.collectAsStateWithLifecycle()

    CameraScreen(onCardCaptured = scannerVm::onCardCaptured)

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
                is ScanResult.Pricing -> PricingContent(
                    card = r.card,
                    variant = r.variant,
                )
                is ScanResult.PricingFailed -> PricingFailedContent(
                    card = r.card,
                    variant = r.variant,
                    reason = r.reason,
                    onRetry = scannerVm::retryPricing,
                    onSkip = scannerVm::reset,
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
            text = "${card.setCode}-${card.collectorNumber} • " +
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
        Text(
            "Multiple possible matches — tap one",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        candidates.forEach { card ->
            TextButton(
                onClick = { onPickCandidate(card) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(card.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${card.setCode}-${card.collectorNumber}",
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
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
            Text("Re-scan")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PricingContent(card: RiftboundCard, variant: Variant) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(card.name, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${card.setCode}-${card.collectorNumber}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                "Fetching ${variant.name.lowercase()} price…",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun PricingFailedContent(
    card: RiftboundCard,
    variant: Variant,
    reason: String,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            "Couldn't fetch ${variant.name.lowercase()} price",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${card.name} • ${card.setCode}-${card.collectorNumber}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) { Text("Skip") }
            Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("Retry") }
        }
        Spacer(Modifier.height(16.dp))
    }
}
