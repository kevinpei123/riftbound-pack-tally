package com.riftbound.packtally.feature.collection

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.core.persistence.SessionRepository
import com.riftbound.packtally.feature.common.ManualAddSheet
import com.riftbound.packtally.model.Rarity
import com.riftbound.packtally.model.Variant
import com.riftbound.packtally.ui.currency.LocalCurrencyFormatter

@Composable
fun CollectionScreen() {
    val vm: CollectionViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val submitInFlight by vm.submitInFlight.collectAsStateWithLifecycle()
    val pricingProgress by vm.pricingProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showAddSheet by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<CollectionEntry?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            val message = when (event) {
                is CollectionEvent.ExportSucceeded -> "Exported to ${event.path}"
                is CollectionEvent.ExportFailed -> "Export failed: ${event.reason}"
                is CollectionEvent.ManualAddSucceeded -> "Added ${event.cardName}"
                is CollectionEvent.RemoveSucceeded -> "Removed one ${event.cardName}"
                is CollectionEvent.RemoveNotFound -> "Could not find ${event.cardName}"
                is CollectionEvent.SubmitCompleted ->
                    "Priced ${event.priced}, failed ${event.failed}, unpriceable ${event.unpriceable}"
                is CollectionEvent.SubmitFailed -> "Pricing failed: ${event.reason}"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            CollectionHeader(
                state = state,
                submitInFlight = submitInFlight,
                pricingProgress = pricingProgress,
                onAdd = { showAddSheet = true },
                onSubmit = vm::submitPendingPrices,
                onExport = vm::exportToJson,
            )
        }
        item {
            CollectionControls(
                state = state,
                onQuery = vm::setQuery,
                onSort = vm::setSort,
                onGroup = vm::setGroup,
                onPending = vm::togglePendingOnly,
                onVariant = vm::setVariant,
                onSet = vm::setSetCode,
                onRarity = vm::setRarity,
                onDomain = vm::setDomain,
                onClear = vm::clearFilters,
            )
        }
        item { HorizontalDivider() }

        when {
            state.isLoading -> item { LoadingRow() }
            state.totalCards == 0 -> item { EmptyState("No cards yet. Start a scan session or add a card manually.") }
            state.groups.all { it.entries.isEmpty() } -> item { EmptyState("No cards match these filters.") }
            else -> state.groups.forEach { group ->
                item(key = "group-${group.title}") {
                    GroupHeader(group)
                }
                items(group.entries, key = { "${it.card.id}-${it.variant}" }) { entry ->
                    CollectionEntryRow(
                        entry = entry,
                        onRemove = { pendingRemoval = entry },
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        ManualAddSheet(
            title = "Add to collection",
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
            text = { Text("Removes the most recent matching session entry.") },
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
    state: CollectionState,
    submitInFlight: Boolean,
    pricingProgress: String?,
    onAdd: () -> Unit,
    onSubmit: () -> Unit,
    onExport: () -> Unit,
) {
    val formatter = LocalCurrencyFormatter.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Collection", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text("${state.totalCards} cards") })
            AssistChip(onClick = {}, label = { Text("${state.uniqueCards} unique") })
            AssistChip(onClick = {}, label = { Text("${state.pendingPriceCount} pending") })
        }
        Text(
            formatter.format(state.totalValue),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
            OutlinedButton(
                onClick = onExport,
                enabled = state.totalCards > 0,
                modifier = Modifier.weight(1f),
            ) { Text("Export") }
        }
        Button(
            onClick = onSubmit,
            enabled = state.pendingPriceCount > 0 && !submitInFlight,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (submitInFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(pricingProgress ?: "Pricing")
            } else {
                Text(SessionRepository.submitLabelFor(state.pendingPriceCount))
            }
        }
    }
}

@Composable
private fun CollectionControls(
    state: CollectionState,
    onQuery: (String) -> Unit,
    onSort: (CollectionSort) -> Unit,
    onGroup: (CollectionGroupMode) -> Unit,
    onPending: () -> Unit,
    onVariant: (Variant?) -> Unit,
    onSet: (String?) -> Unit,
    onRarity: (Rarity?) -> Unit,
    onDomain: (String?) -> Unit,
    onClear: () -> Unit,
) {
    val filter = state.filter
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = onQuery,
            label = { Text("Search name or collector number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EnumMenu(
                label = "Sort",
                value = filter.sort.name.toLabel(),
                values = CollectionSort.entries,
                onPick = onSort,
            )
            EnumMenu(
                label = "Group",
                value = filter.group.name.toLabel(),
                values = CollectionGroupMode.entries,
                onPick = onGroup,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter.pendingOnly,
                onClick = onPending,
                label = { Text("Pending") },
            )
            NullableMenu(
                label = "Variant",
                value = filter.variant?.name?.toLabel() ?: "All",
                values = listOf(null) + Variant.entries,
                labelFor = { it?.name?.toLabel() ?: "All" },
                onPick = onVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NullableMenu(
                label = "Set",
                value = filter.setCode ?: "All sets",
                values = listOf(null) + state.options.sets,
                labelFor = { it ?: "All sets" },
                onPick = onSet,
            )
            NullableMenu(
                label = "Rarity",
                value = filter.rarity?.name?.toLabel() ?: "All rarities",
                values = listOf(null) + state.options.rarities,
                labelFor = { it?.name?.toLabel() ?: "All rarities" },
                onPick = onRarity,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NullableMenu(
                label = "Domain",
                value = filter.domain ?: "All domains",
                values = listOf(null) + state.options.domains,
                labelFor = { it ?: "All domains" },
                onPick = onDomain,
            )
            OutlinedButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
private fun GroupHeader(group: CollectionGroup) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(group.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${group.totalCards} cards", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                LocalCurrencyFormatter.current.format(group.totalValue),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun CollectionEntryRow(entry: CollectionEntry, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "x${entry.quantity}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(32.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.card.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${entry.card.collectorNumber} - ${entry.variant.name.toLabel()} - ${entry.card.rarity.name.toLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val stateLabel = when {
                entry.unpriceableCount > 0 -> "${entry.unpriceableCount} no TCG ID"
                entry.failedCount > 0 -> "${entry.failedCount} failed"
                entry.pendingCount > 0 -> "${entry.pendingCount} pending"
                else -> "priced"
            }
            Text(
                (entry.domains.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "No domain") +
                    " - $stateLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            val formatter = LocalCurrencyFormatter.current
            Text(formatter.format(entry.totalMarketValue), style = MaterialTheme.typography.titleMedium)
            if (entry.quantity > 1) {
                Text(
                    "${formatter.format(entry.unitPrice)} ea",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.DeleteOutline, contentDescription = "Remove ${entry.card.name}")
        }
    }
}

@Composable
private fun <T : Enum<T>> EnumMenu(
    label: String,
    value: String,
    values: List<T>,
    onPick: (T) -> Unit,
) {
    OptionMenu(label = label, value = value, values = values, labelFor = { it.name.toLabel() }, onPick = onPick)
}

@Composable
private fun <T> NullableMenu(
    label: String,
    value: String,
    values: List<T>,
    labelFor: (T) -> String,
    onPick: (T) -> Unit,
) {
    OptionMenu(label = label, value = value, values = values, labelFor = labelFor, onPick = onPick)
}

@Composable
private fun <T> OptionMenu(
    label: String,
    value: String,
    values: List<T>,
    labelFor: (T) -> String,
    onPick: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text("$label: $value")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { item ->
                DropdownMenuItem(
                    text = { Text(labelFor(item)) },
                    onClick = {
                        expanded = false
                        onPick(item)
                    },
                )
            }
        }
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
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun String.toLabel(): String =
    lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
