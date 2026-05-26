package com.riftbound.packtally.feature.quickscan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.feature.scanner.CameraScreen
import com.riftbound.packtally.feature.scanner.Variant
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.ui.currency.LocalCurrencyFormatter
import kotlinx.coroutines.delay

private const val SEARCH_DEBOUNCE_MS = 300L
private const val MANUAL_ENTRY_SUGGEST_AFTER = 3

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickScanScreen() {
    val vm: QuickScanViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val ocrFailureCount by vm.ocrFailureCount.collectAsStateWithLifecycle()

    var showManualEntry by remember { mutableStateOf(false) }

    // Reset session stats whenever the screen leaves composition.
    // "Session" = from when I opened Quick Scan to when I navigated away.
    LaunchedEffect(Unit) {
        // no-op on enter; the DisposableEffect below handles exit
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { vm.clearSessionStats() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraScreen(onCardCaptured = vm::onCardCaptured)

        // Session tally + Type-instead button overlaid at the top.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            SessionTallyChip(stats)
            if (ocrFailureCount >= MANUAL_ENTRY_SUGGEST_AFTER || state is QuickScanState.Failed) {
                Spacer(Modifier.height(8.dp))
                TypeInsteadButton(onClick = { showManualEntry = true })
            }
        }

        // Sheet content keyed off ScanState
        val showSheet = state !is QuickScanState.CameraReady && state !is QuickScanState.Scanning
        if (showSheet) {
            ModalBottomSheet(onDismissRequest = vm::reset) {
                when (val s = state) {
                    is QuickScanState.Identified -> IdentifiedContent(
                        card = s.card,
                        confidence = s.confidence,
                        onVariantSelected = { variant -> vm.confirmVariant(s.card, variant) },
                        onRescan = vm::reset,
                    )
                    is QuickScanState.Ambiguous -> AmbiguousContent(
                        candidates = s.candidates,
                        onPick = vm::pickCandidate,
                        onRescan = vm::reset,
                    )
                    is QuickScanState.Pricing -> PricingContent(s.card, s.variant)
                    is QuickScanState.Saved -> SavedContent(
                        card = s.card,
                        variant = s.variant,
                        marketPrice = s.marketPrice,
                        onScanAnother = vm::scanAnother,
                        onDone = vm::reset,
                    )
                    is QuickScanState.Failed -> FailedContent(
                        reason = s.reason,
                        onRetry = vm::reset,
                        onManualEntry = { showManualEntry = true },
                    )
                    else -> Unit
                }
            }
        }
    }

    if (showManualEntry) {
        ManualEntrySheet(
            onPickCard = { card ->
                vm.chooseManually(card)
                showManualEntry = false
            },
            onDismiss = { showManualEntry = false },
        )
    }
}

@Composable
private fun SessionTallyChip(stats: SessionStats) {
    val cards = stats.cardsAdded
    val total = stats.totalValue
    if (cards == 0) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val label = "$cards " + (if (cards == 1) "card" else "cards") + " added"
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                LocalCurrencyFormatter.current.format(total),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TypeInsteadButton(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text("OCR struggling? Type the card name instead →")
        }
    }
}

@Composable
private fun IdentifiedContent(
    card: RiftboundCard,
    confidence: Float,
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
            "${card.setCode}-${card.collectorNumber} • " +
                card.rarity.name.lowercase().replaceFirstChar { it.uppercase() } +
                " • ${(confidence * 100).toInt()}%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
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
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
            Text("Re-scan")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AmbiguousContent(
    candidates: List<RiftboundCard>,
    onPick: (RiftboundCard) -> Unit,
    onRescan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text("Multiple possible matches — tap one", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        candidates.forEach { card ->
            TextButton(
                onClick = { onPick(card) },
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
        OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) { Text("Re-scan") }
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
            "${card.setCode}-${card.collectorNumber}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Fetching ${variant.name.lowercase()} price…")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SavedContent(
    card: RiftboundCard,
    variant: Variant,
    marketPrice: Double,
    onScanAnother: () -> Unit,
    onDone: () -> Unit,
) {
    val formatter = LocalCurrencyFormatter.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Added to your collection", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(card.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${card.setCode}-${card.collectorNumber} • " +
                        variant.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    formatter.format(marketPrice),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f)) { Text("Done") }
            Button(onClick = onScanAnother, modifier = Modifier.weight(1f)) { Text("Scan Another") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FailedContent(
    reason: String,
    onRetry: () -> Unit,
    onManualEntry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text("Could not identify card", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onManualEntry,
                modifier = Modifier.weight(1f),
            ) { Text("Type instead") }
            Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("Re-scan") }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualEntrySheet(
    onPickCard: (RiftboundCard) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        if (query == debounced) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        debounced = query
    }

    val results = remember(debounced) {
        if (debounced.isBlank()) emptyList()
        else CardDatabase.lookupByNameFuzzy(debounced, limit = 8)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text("Find a card", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Card name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            if (debounced.isBlank()) {
                Text(
                    "Type a card name — top 8 matches will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (results.isEmpty()) {
                Text(
                    "No matches.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(results, key = { it.id }) { card ->
                        ManualCandidateRow(card = card, onClick = { onPickCard(card) })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualCandidateRow(card: RiftboundCard, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(card.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${card.setCode}-${card.collectorNumber} • " +
                    card.rarity.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
