package com.riftbound.packtally.feature.collection

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.core.persistence.SessionRepository
import com.riftbound.packtally.feature.common.ManualAddSheet
import com.riftbound.packtally.model.Rarity
import com.riftbound.packtally.model.Variant
import com.riftbound.packtally.ui.currency.LocalCurrencyFormatter
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CollectionScreen() {
    val vm: CollectionViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val submitInFlight by vm.submitInFlight.collectAsStateWithLifecycle()
    val pricingProgress by vm.pricingProgress.collectAsStateWithLifecycle()
    val recallInFlight by vm.recallInFlight.collectAsStateWithLifecycle()
    val recallProgress by vm.recallProgress.collectAsStateWithLifecycle()
    val recallPrompt by vm.recallPrompt.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showAddSheet by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<CollectionEntry?>(null) }
    var showSortSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            // Export opens a share sheet so the file is actually retrievable;
            // everything else surfaces as a short toast.
            val message: String? = when (event) {
                is CollectionEvent.ExportSucceeded -> {
                    shareExportFile(context, event.path)
                    null
                }
                is CollectionEvent.ExportFailed -> "Export failed: ${event.reason}"
                is CollectionEvent.ManualAddSucceeded -> "Added ${event.cardName}"
                is CollectionEvent.ManualAddFailed -> "Couldn't add ${event.cardName}"
                is CollectionEvent.RemoveSucceeded -> "Removed one ${event.cardName}"
                is CollectionEvent.RemoveNotFound -> "Could not find ${event.cardName}"
                is CollectionEvent.SubmitCompleted ->
                    "Priced ${event.priced}, failed ${event.failed}, unpriceable ${event.unpriceable}"
                is CollectionEvent.SubmitFailed -> "Pricing failed: ${event.reason}"
                is CollectionEvent.RecallCompleted -> when {
                    event.stoppedReason != null ->
                        "${event.stoppedReason} (refreshed ${event.priced} so far)"
                    event.priced == 0 && event.failed == 0 -> "No prices to refresh"
                    else -> "Refreshed ${event.priced}, failed ${event.failed}, unpriceable ${event.unpriceable}"
                }
                is CollectionEvent.RecallFailed -> "Refresh failed: ${event.reason}"
            }
            if (message != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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
                recallInFlight = recallInFlight,
                recallProgress = recallProgress,
                onAdd = { showAddSheet = true },
                onSubmit = vm::submitPendingPrices,
                onRecall = vm::requestRecall,
                onExport = vm::exportToJson,
            )
        }
        item {
            CollectionSearchAndActions(
                query = state.filter.query,
                onQuery = vm::setQuery,
                onSort = { showSortSheet = true },
            )
        }
        item { HorizontalDivider() }

        when {
            state.isLoading -> item { LoadingRow() }
            state.totalCards == 0 -> item { EmptyState("No cards yet. Start a scan session or add a card manually.") }
            state.groups.all { it.entries.isEmpty() } -> item { EmptyState("No cards match these filters.") }
            else -> state.groups.forEach { group ->
                stickyHeader(key = "group-${group.title}") {
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

    if (showSortSheet) {
        ModalBottomSheet(onDismissRequest = { showSortSheet = false }) {
            CollectionSortSheet(
                state = state,
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
    }

    recallPrompt?.let { prompt ->
        val waitNote = if (prompt.waits > 0) {
            " It will pause about 1 minute ${prompt.waits} time${if (prompt.waits == 1) "" else "s"} " +
                "to respect the 10-requests-per-minute limit."
        } else {
            ""
        }
        AlertDialog(
            onDismissRequest = vm::dismissRecallPrompt,
            title = { Text("Refresh all prices?") },
            text = {
                Text(
                    "Re-fetches the latest market price for every card from JustTCG in " +
                        "${prompt.calls} request${if (prompt.calls == 1) "" else "s"} of up to 20 cards each, " +
                        "using your monthly quota.$waitNote",
                )
            },
            confirmButton = {
                TextButton(onClick = vm::recallAllPrices) { Text("Refresh") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissRecallPrompt) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CollectionHeader(
    state: CollectionState,
    submitInFlight: Boolean,
    pricingProgress: String?,
    recallInFlight: Boolean,
    recallProgress: String?,
    onAdd: () -> Unit,
    onSubmit: () -> Unit,
    onRecall: () -> Unit,
    onExport: () -> Unit,
) {
    val formatter = LocalCurrencyFormatter.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text("Collection", style = MaterialTheme.typography.headlineSmall)
            Text(
                formatter.format(state.totalValue),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip("${state.totalCards} cards")
            StatChip("${state.uniqueCards} unique")
            StatChip("${state.pendingPriceCount} to price")
        }
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
            enabled = state.pendingPriceCount > 0 && !submitInFlight && !recallInFlight,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (submitInFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    pricingProgress ?: "Pricing",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                Text(SessionRepository.submitLabelFor(state.pendingPriceCount))
            }
        }
        OutlinedButton(
            onClick = onRecall,
            enabled = state.totalCards > 0 && !recallInFlight && !submitInFlight,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (recallInFlight) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    recallProgress ?: "Refreshing prices",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Refresh all prices")
            }
        }
    }
}

@Composable
private fun CollectionSearchAndActions(
    query: String,
    onQuery: (String) -> Unit,
    onSort: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Search name or collector number") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = onSort, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sort and filter")
        }
    }
}

@Composable
private fun CollectionSortSheet(
    state: CollectionState,
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Sort and filter", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Controls apply immediately",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onClear) { Text("Reset") }
        }
        SheetSectionTitle("Order")
        EnumMenu(
            label = "Sort",
            value = filter.sort.name.toLabel(),
            values = CollectionSort.entries,
            onPick = onSort,
            modifier = Modifier.fillMaxWidth(),
        )
        EnumMenu(
            label = "Group",
            value = filter.group.name.toLabel(),
            values = CollectionGroupMode.entries,
            onPick = onGroup,
            modifier = Modifier.fillMaxWidth(),
        )
        SheetSectionTitle("Filters")
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
                modifier = Modifier.weight(1f),
            )
        }
        NullableMenu(
            label = "Set",
            value = filter.setCode ?: "All sets",
            values = listOf(null) + state.options.sets,
            labelFor = { it ?: "All sets" },
            onPick = onSet,
            modifier = Modifier.fillMaxWidth(),
        )
        NullableMenu(
            label = "Rarity",
            value = filter.rarity?.name?.toLabel() ?: "All rarities",
            values = listOf(null) + state.options.rarities,
            labelFor = { it?.name?.toLabel() ?: "All rarities" },
            onPick = onRarity,
            modifier = Modifier.fillMaxWidth(),
        )
        NullableMenu(
            label = "Domain",
            value = filter.domain ?: "All domains",
            values = listOf(null) + state.options.domains,
            labelFor = { it ?: "All domains" },
            onPick = onDomain,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StatChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun SheetSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    modifier: Modifier = Modifier,
) {
    OptionMenu(
        label = label,
        value = value,
        values = values,
        labelFor = { it.name.toLabel() },
        onPick = onPick,
        modifier = modifier,
    )
}

@Composable
private fun <T> NullableMenu(
    label: String,
    value: String,
    values: List<T>,
    labelFor: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    OptionMenu(label = label, value = value, values = values, labelFor = labelFor, onPick = onPick, modifier = modifier)
}

@Composable
private fun <T> OptionMenu(
    label: String,
    value: String,
    values: List<T>,
    labelFor: (T) -> String,
    onPick: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $value", maxLines = 1, overflow = TextOverflow.Ellipsis)
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

/** Open a share sheet for the exported JSON so the user can save/send it anywhere. */
private fun shareExportFile(context: Context, path: String) {
    val file = File(path)
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share collection export"))
    }.onFailure {
        Toast.makeText(context, "Saved ${file.name} (couldn't open share sheet)", Toast.LENGTH_LONG).show()
    }
}
