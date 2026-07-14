package com.riftbound.packtally.feature.cardbrowser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.riftbound.packtally.core.carddb.CardSortOrder
import com.riftbound.packtally.model.Rarity
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.ui.common.LoadingIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardBrowserScreen(
    onNavigateBack: () -> Unit,
    onCardSelected: (String) -> Unit,
) {
    val vm: CardBrowserViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Card database") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (!state.catalogueReady) {
            LoadingIndicator(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                label = "Loading card database",
            )
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            SearchAndSortRow(
                search = state.query.search,
                onSearchChange = vm::setSearch,
                sortOrder = state.query.sortOrder,
                onSortOrderChange = vm::setSortOrder,
            )
            FilterChipsRow(
                availableSetCodes = state.availableSetCodes,
                selectedSetCodes = state.query.setCodes,
                onToggleSetCode = vm::toggleSetCode,
                selectedRarities = state.query.rarities,
                onToggleRarity = vm::toggleRarity,
                availableDomains = state.availableDomains,
                selectedDomains = state.query.domains,
                onToggleDomain = vm::toggleDomain,
                availableTypes = state.availableTypes,
                selectedTypes = state.query.types,
                onToggleType = vm::toggleType,
                availableEnergyValues = state.availableEnergyValues,
                selectedEnergyValues = state.query.energyValues,
                onToggleEnergyValue = vm::toggleEnergyValue,
                availableMightValues = state.availableMightValues,
                selectedMightValues = state.query.mightValues,
                onToggleMightValue = vm::toggleMightValue,
                availablePowerValues = state.availablePowerValues,
                selectedPowerValues = state.query.powerValues,
                onTogglePowerValue = vm::togglePowerValue,
                onClear = vm::clearFilters,
            )
            Text(
                "${state.cards.size} card${if (state.cards.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 4.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.cards, key = { it.id }) { card ->
                    CardRow(card = card, onClick = { onCardSelected(card.id) })
                }
            }
        }
    }
}

@Composable
private fun SearchAndSortRow(
    search: String,
    onSearchChange: (String) -> Unit,
    sortOrder: CardSortOrder,
    onSortOrderChange: (CardSortOrder) -> Unit,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            label = { Text("Search by name or number") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Box {
            OutlinedButton(onClick = { sortMenuExpanded = true }) {
                Text(sortOrder.label(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                CardSortOrder.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label()) },
                        onClick = {
                            sortMenuExpanded = false
                            onSortOrderChange(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChipsRow(
    availableSetCodes: List<String>,
    selectedSetCodes: Set<String>,
    onToggleSetCode: (String) -> Unit,
    selectedRarities: Set<Rarity>,
    onToggleRarity: (Rarity) -> Unit,
    availableDomains: List<String>,
    selectedDomains: Set<String>,
    onToggleDomain: (String) -> Unit,
    availableTypes: List<String>,
    selectedTypes: Set<String>,
    onToggleType: (String) -> Unit,
    availableEnergyValues: List<Int>,
    selectedEnergyValues: Set<Int>,
    onToggleEnergyValue: (Int) -> Unit,
    availableMightValues: List<Int>,
    selectedMightValues: Set<Int>,
    onToggleMightValue: (Int) -> Unit,
    availablePowerValues: List<Int>,
    selectedPowerValues: Set<Int>,
    onTogglePowerValue: (Int) -> Unit,
    onClear: () -> Unit,
) {
    val hasFilters = selectedSetCodes.isNotEmpty() || selectedRarities.isNotEmpty() ||
        selectedDomains.isNotEmpty() || selectedTypes.isNotEmpty() ||
        selectedEnergyValues.isNotEmpty() || selectedMightValues.isNotEmpty() ||
        selectedPowerValues.isNotEmpty()
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(Rarity.entries) { rarity ->
            FilterChip(
                selected = rarity in selectedRarities,
                onClick = { onToggleRarity(rarity) },
                label = { Text(rarity.name.lowercase().replaceFirstChar { it.uppercase() }) },
            )
        }
        items(availableDomains) { domain ->
            FilterChip(
                selected = domain in selectedDomains,
                onClick = { onToggleDomain(domain) },
                label = { Text(domain) },
            )
        }
        items(availableTypes) { type ->
            FilterChip(
                selected = type in selectedTypes,
                onClick = { onToggleType(type) },
                label = { Text(type.replaceFirstChar { it.uppercase() }) },
            )
        }
        items(availableSetCodes) { setCode ->
            FilterChip(
                selected = setCode in selectedSetCodes,
                onClick = { onToggleSetCode(setCode) },
                label = { Text(setCode) },
            )
        }
        items(availableEnergyValues) { value ->
            FilterChip(
                selected = value in selectedEnergyValues,
                onClick = { onToggleEnergyValue(value) },
                label = { Text("Energy $value") },
            )
        }
        items(availableMightValues) { value ->
            FilterChip(
                selected = value in selectedMightValues,
                onClick = { onToggleMightValue(value) },
                label = { Text("Might $value") },
            )
        }
        items(availablePowerValues) { value ->
            FilterChip(
                selected = value in selectedPowerValues,
                onClick = { onTogglePowerValue(value) },
                label = { Text("Power $value") },
            )
        }
        if (hasFilters) {
            item {
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun CardRow(card: RiftboundCard, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp, 64.dp)
                    .clip(RoundedCornerShape(6.dp)),
            ) {
                AsyncImage(
                    model = card.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    card.name,
                    style = MaterialTheme.typography.bodyLarge,
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
}

private fun CardSortOrder.label(): String = when (this) {
    CardSortOrder.NAME_ASC -> "Name A-Z"
    CardSortOrder.NAME_DESC -> "Name Z-A"
    CardSortOrder.SET_NUMBER_ASC -> "Set / number"
    CardSortOrder.RARITY_ASC -> "Rarity"
}
