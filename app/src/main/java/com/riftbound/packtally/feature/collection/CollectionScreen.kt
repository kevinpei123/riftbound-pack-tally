package com.riftbound.packtally.feature.collection

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.model.Rarity
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.Variant
import com.riftbound.packtally.ui.currency.LocalCurrencyFormatter
import kotlinx.coroutines.delay

private const val SEARCH_DEBOUNCE_MS = 300L

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun CollectionScreen() {
    val vm: CollectionViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val pendingPriceCount by vm.pendingPriceCount.collectAsStateWithLifecycle()
    val submitInFlight by vm.submitInFlight.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val formatter = LocalCurrencyFormatter.current

    var showAddSheet by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<CollectionEntry?>(null) }

    // Refresh on every entry to pick up newly-completed packs.
    LaunchedEffect(Unit) { vm.refresh() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is CollectionEvent.ExportSucceeded ->
                    Toast.makeText(context, "Exported to ${event.path}", Toast.LENGTH_LONG).show()
                is CollectionEvent.ExportFailed ->
                    Toast.makeText(context, "Export failed: ${event.reason}", Toast.LENGTH_LONG).show()
                is CollectionEvent.ManualAddSucceeded ->
                    Toast.makeText(context, "Added ${event.cardName}", Toast.LENGTH_SHORT).show()
                is CollectionEvent.RemoveSucceeded ->
                    Toast.makeText(context, "Removed one ${event.cardName}", Toast.LENGTH_SHORT).show()
                is CollectionEvent.RemoveNotFound ->
                    Toast.makeText(
                        context,
                        "Couldn't find ${event.cardName} to remove — the list may be out of date",
                        Toast.LENGTH_LONG,
                    ).show()
                is CollectionEvent.SubmitCompleted -> {
                    val cardsTxt = "${event.priced} card" + if (event.priced == 1) "" else "s"
                    val tail = if (event.failed > 0) " (${event.failed} couldn't be priced)" else ""
                    Toast.makeText(
                        context,
                        "Priced $cardsTxt — ${formatter.format(event.totalValue)}$tail",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                is CollectionEvent.SubmitFailed ->
                    Toast.makeText(context, "Pricing failed — ${event.reason}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            CollectionHeader(
                totalValue = state.totalValue,
                totalCards = state.totalCards,
                pendingPriceCount = pendingPriceCount,
                submitInFlight = submitInFlight,
                onSubmitPending = vm::submitPendingPrices,
                onExport = vm::exportToJson,
                exportEnabled = !state.isLoading && state.groups.isNotEmpty(),
                onAddCard = { showAddSheet = true },
            )
        }
        item {
            SearchBar(
                query = state.filter.nameQuery,
                onQueryChange = vm::setNameQuery,
            )
        }
        item {
            FilterChipsBar(
                filter = state.filter,
                onToggleFoil = vm::toggleFoilFilter,
                onToggleSignature = vm::toggleSignatureFilter,
                onToggleRarity = vm::toggleRarityFilter,
                onToggleLooseOnly = vm::toggleLooseOnlyFilter,
                onClear = vm::clearFilters,
            )
        }
        item { HorizontalDivider() }

        when {
            state.isLoading -> item { LoadingRow() }
            !state.hasAnyCompletedPacks -> item {
                EmptyState(text = "Nothing scanned yet.\nScan a card on the Pack or Quick Scan tabs to see it here.")
            }
            state.groups.isEmpty() -> item {
                EmptyState(text = "No cards match the current filters.")
            }
            else -> {
                state.groups.forEach { group ->
                    stickyHeader(key = "set-${group.setCode}") {
                        SetHeader(setCode = group.setCode, totalValue = group.totalValue)
                    }
                    items(group.entries, key = { "${it.card.id}-${it.variant}" }) { entry ->
                        EntryRow(entry, onRemoveClick = { pendingRemoval = entry })
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddCardSheet(
            onPicked = { card, variant ->
                vm.addManualEntry(card, variant)
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false },
        )
    }

    pendingRemoval?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove one ${entry.card.name}?") },
            text = {
                Text(
                    "Removes the most recent manually-added or quick-scanned copy. " +
                        "If no loose copy exists, removes the newest matching pack copy.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeOne(entry.card, entry.variant)
                    pendingRemoval = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CollectionHeader(
    totalValue: Double,
    totalCards: Int,
    pendingPriceCount: Int,
    submitInFlight: Boolean,
    onSubmitPending: () -> Unit,
    onExport: () -> Unit,
    exportEnabled: Boolean,
    onAddCard: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text("Collection", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            LocalCurrencyFormatter.current.format(totalValue),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "$totalCards card" + if (totalCards == 1) "" else "s",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onAddCard,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add card")
            }
            OutlinedButton(
                onClick = onExport,
                enabled = exportEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text("Export JSON")
            }
        }
        if (pendingPriceCount > 0) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSubmitPending,
                enabled = !submitInFlight,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (submitInFlight) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Fetching prices…")
                } else {
                    Text("Submit $pendingPriceCount card${if (pendingPriceCount == 1) "" else "s"} for pricing")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterChipsBar(
    filter: CollectionFilter,
    onToggleFoil: () -> Unit,
    onToggleSignature: () -> Unit,
    onToggleRarity: (Rarity) -> Unit,
    onToggleLooseOnly: () -> Unit,
    onClear: () -> Unit,
) {
    val anySelected = filter.foilOnly || filter.signatureOnly ||
        filter.looseOnly || filter.selectedRarities.isNotEmpty() ||
        filter.nameQuery.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = filter.foilOnly,
                onClick = onToggleFoil,
                label = { Text("Foil") },
            )
            FilterChip(
                selected = filter.signatureOnly,
                onClick = onToggleSignature,
                label = { Text("Signature") },
            )
            FilterChip(
                selected = filter.looseOnly,
                onClick = onToggleLooseOnly,
                label = { Text("Manual / Quick only") },
            )
            Rarity.entries.forEach { rarity ->
                FilterChip(
                    selected = rarity in filter.selectedRarities,
                    onClick = { onToggleRarity(rarity) },
                    label = { Text(rarity.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        if (anySelected) {
            TextButton(
                onClick = onClear,
                modifier = Modifier.padding(top = 4.dp),
            ) { Text("Clear filters") }
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Search by name") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SetHeader(setCode: String, totalValue: Double) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                setCode,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                LocalCurrencyFormatter.current.format(totalValue),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryRow(entry: CollectionEntry, onRemoveClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardThumbnail(imageUrl = entry.card.imageUrl, contentDescription = entry.card.name)
        Text(
            "×${entry.quantity}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(36.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.card.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "${entry.card.collectorNumber} • " +
                    entry.card.rarity.name.lowercase().replaceFirstChar { it.uppercase() } +
                    " • " +
                    entry.variant.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Source breakdown helps the user understand where the count comes
            // from when they ask "wait, why can't I edit this from Pack?".
            val sourceLabel = when {
                entry.packQuantity > 0 && entry.looseQuantity > 0 ->
                    "${entry.packQuantity} pack + ${entry.looseQuantity} manual"
                entry.packQuantity > 0 -> "from pack"
                else -> "manual / quick"
            }
            Text(
                sourceLabel + if (entry.hasPendingPrice) " · price pending" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val formatter = LocalCurrencyFormatter.current
            Text(
                formatter.format(entry.totalMarketValue),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (entry.quantity > 1) {
                Text(
                    "${formatter.format(entry.unitPrice)} ea",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onRemoveClick) {
            Icon(
                Icons.Filled.RemoveCircleOutline,
                contentDescription = "Remove one ${entry.card.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCardSheet(
    onPicked: (RiftboundCard, Variant) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var pickedCard by remember { mutableStateOf<RiftboundCard?>(null) }

    LaunchedEffect(query) {
        if (query == debounced) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        debounced = query
    }

    val results = remember(debounced) {
        if (debounced.isBlank()) emptyList()
        else CardDatabase.lookupByNameFuzzy(debounced, limit = 10)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            val current = pickedCard
            if (current == null) {
                Text("Add a card", style = MaterialTheme.typography.titleLarge)
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
                when {
                    debounced.isBlank() -> Text(
                        "Type a card name — top 10 matches will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    results.isEmpty() -> Text(
                        "No matches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(results, key = { it.id }) { card ->
                            CandidateRow(card = card, onClick = { pickedCard = card })
                        }
                    }
                }
            } else {
                Text("Pick a variant", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    current.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${current.setCode}-${current.collectorNumber} • " +
                        current.rarity.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onPicked(current, Variant.STANDARD) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Standard") }
                    Button(
                        onClick = { onPicked(current, Variant.FOIL) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Foil") }
                    Button(
                        onClick = { onPicked(current, Variant.SIGNATURE) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Signature") }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { pickedCard = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Pick a different card") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Added cards have no price until you tap Submit on the Quick Scan tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandidateRow(card: RiftboundCard, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CardThumbnail(imageUrl = card.imageUrl, contentDescription = card.name)
            Column(modifier = Modifier.weight(1f)) {
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
}

/**
 * Small clipped thumbnail used in Collection rows and search results. Falls
 * back to a neutral placeholder when the card has no [imageUrl] (older sync
 * data, custom prints).
 */
@Composable
private fun CardThumbnail(imageUrl: String?, contentDescription: String) {
    val shape = RoundedCornerShape(4.dp)
    if (imageUrl.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 44.dp)
                .clip(shape),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {}
        }
    } else {
        val context = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            modifier = Modifier
                .size(width = 32.dp, height = 44.dp)
                .clip(shape),
        )
    }
}

@Composable
private fun LoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator() }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
