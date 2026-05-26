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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.stickyHeader
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.model.Rarity

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun CollectionScreen() {
    val vm: CollectionViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Refresh on every entry to pick up newly-completed packs.
    LaunchedEffect(Unit) { vm.refresh() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is CollectionEvent.ExportSucceeded ->
                    Toast.makeText(context, "Exported to ${event.path}", Toast.LENGTH_LONG).show()
                is CollectionEvent.ExportFailed ->
                    Toast.makeText(context, "Export failed: ${event.reason}", Toast.LENGTH_LONG).show()
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
                onExport = vm::exportToJson,
                exportEnabled = !state.isLoading && state.groups.isNotEmpty(),
            )
        }
        item {
            FilterChipsBar(
                filter = state.filter,
                onToggleFoil = vm::toggleFoilFilter,
                onToggleSignature = vm::toggleSignatureFilter,
                onToggleRarity = vm::toggleRarityFilter,
                onClear = vm::clearFilters,
            )
        }
        item { HorizontalDivider() }

        when {
            state.isLoading -> item { LoadingRow() }
            !state.hasAnyCompletedPacks -> item {
                EmptyState(text = "No completed packs yet.\nFinish a pack on the Pack tab to see it here.")
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
                        EntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionHeader(
    totalValue: Double,
    totalCards: Int,
    onExport: () -> Unit,
    exportEnabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text("Collection", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            "$${"%.2f".format(totalValue)}",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$totalCards card" + if (totalCards == 1) "" else "s",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onExport, enabled = exportEnabled) {
                Text("Export JSON")
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
    onClear: () -> Unit,
) {
    val anySelected = filter.foilOnly || filter.signatureOnly || filter.selectedRarities.isNotEmpty()
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
                "$${"%.2f".format(totalValue)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryRow(entry: CollectionEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "$${"%.2f".format(entry.totalMarketValue)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (entry.quantity > 1) {
                Text(
                    "$${"%.2f".format(entry.unitPrice)} ea",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
