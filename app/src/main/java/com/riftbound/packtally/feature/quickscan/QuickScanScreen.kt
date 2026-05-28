package com.riftbound.packtally.feature.quickscan

import android.widget.Toast
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.feature.scanner.CameraScreen
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.Variant
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
    val submitState by vm.submit.collectAsStateWithLifecycle()
    val ocrFailureCount by vm.ocrFailureCount.collectAsStateWithLifecycle()
    val rapidMode by vm.rapidMode.collectAsStateWithLifecycle()
    val lastAdded by vm.lastAdded.collectAsStateWithLifecycle()

    var showManualEntry by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val formatter = LocalCurrencyFormatter.current

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            val msg = when (event) {
                is QuickScanEvent.SubmitCompleted -> {
                    val cardsTxt = "${event.priced} card" + if (event.priced == 1) "" else "s"
                    val tail = if (event.failed > 0) " (${event.failed} couldn't be priced)" else ""
                    "Priced $cardsTxt — ${formatter.format(event.totalValue)}$tail"
                }
                is QuickScanEvent.SubmitFailed -> "Pricing failed — ${event.reason}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { vm.clearSessionStats() }
    }

    val isSubmitting = submitState is QuickScanSubmitState.InFlight

    Box(modifier = Modifier.fillMaxSize()) {
        CameraScreen(onCardCaptured = vm::onCardCaptured)

        // Session tally + Submit + Type-instead overlay at the top.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            RapidModeChip(
                enabled = rapidMode,
                onToggle = vm::setRapidMode,
                lastAddedName = lastAdded.takeIf { rapidMode },
            )
            Spacer(Modifier.height(8.dp))
            SessionTallyChip(stats)
            if (stats.pendingCount > 0) {
                Spacer(Modifier.height(8.dp))
                SubmitPendingButton(
                    pendingCount = stats.pendingCount,
                    isSubmitting = isSubmitting,
                    onSubmit = vm::submitPending,
                )
            }
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
                    is QuickScanState.Saved -> SavedContent(
                        card = s.card,
                        variant = s.variant,
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
private fun RapidModeChip(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    lastAddedName: String?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (enabled) {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.95f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
        },
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (enabled) "Rapid mode ON — auto-adds as Standard" else "Rapid mode",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val sub = if (enabled) {
                    lastAddedName?.let { "Last: $it" }
                        ?: "Skip the variant chooser; tap card → done"
                } else {
                    "Switch on to bulk-scan without confirming variants"
                }
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun SubmitPendingButton(
    pendingCount: Int,
    isSubmitting: Boolean,
    onSubmit: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
    ) {
        Button(
            onClick = onSubmit,
            enabled = !isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("Fetching prices…")
            } else {
                Text("Submit $pendingCount card${if (pendingCount == 1) "" else "s"} for pricing")
            }
        }
    }
}

@Composable
private fun SessionTallyChip(stats: SessionStats) {
    val cards = stats.cardsAdded
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
            val cardsLabel = "$cards " + (if (cards == 1) "card" else "cards") + " added"
            val subline = if (stats.pendingCount > 0) {
                "${stats.pendingCount} pending price"
            } else null
            Column(modifier = Modifier.weight(1f)) {
                Text(cardsLabel, style = MaterialTheme.typography.bodyMedium)
                if (subline != null) {
                    Text(
                        subline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                LocalCurrencyFormatter.current.format(stats.totalValue),
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
        Text(
            card.name,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
                    Text(
                        card.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
private fun SavedContent(
    card: RiftboundCard,
    variant: Variant,
    onScanAnother: () -> Unit,
    onDone: () -> Unit,
) {
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
                Text(
                    card.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${card.setCode}-${card.collectorNumber} • " +
                        variant.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Price pending — tap Submit to fetch with the rest of the batch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Text(
                card.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${card.setCode}-${card.collectorNumber} • " +
                    card.rarity.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
