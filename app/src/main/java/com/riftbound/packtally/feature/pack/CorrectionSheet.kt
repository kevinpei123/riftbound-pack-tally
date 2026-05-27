package com.riftbound.packtally.feature.pack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.ScannedEntry
import com.riftbound.packtally.model.Variant
import kotlinx.coroutines.delay

private const val SEARCH_DEBOUNCE_MS = 300L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrectionSheet(
    state: CorrectionState,
    onDismiss: () -> Unit,
    onDelete: (ScannedEntry) -> Unit,
    onApply: (newCard: RiftboundCard, newVariant: Variant) -> Unit,
) {
    val entry = state.entry

    var selectedCard by remember(entry.id) { mutableStateOf<RiftboundCard?>(null) }
    var foilOn by remember(entry.id) { mutableStateOf(entry.variant == Variant.FOIL) }
    var signatureOn by remember(entry.id) { mutableStateOf(entry.variant == Variant.SIGNATURE) }
    var searchQuery by remember(entry.id) { mutableStateOf("") }
    var debouncedQuery by remember(entry.id) { mutableStateOf("") }

    LaunchedEffect(searchQuery) {
        if (searchQuery == debouncedQuery) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        debouncedQuery = searchQuery
    }

    val topCandidates = remember(entry.id) {
        CardDatabase.lookupByNameFuzzy(entry.card.name, limit = 4)
            .filter { it.id != entry.card.id }
            .take(3)
    }

    val searchResults = remember(debouncedQuery) {
        if (debouncedQuery.isBlank()) emptyList()
        else CardDatabase.lookupByNameFuzzy(debouncedQuery, limit = 10)
    }

    val showingSearch = searchQuery.isNotBlank()
    val displayedList = if (showingSearch) searchResults else topCandidates

    val newVariant = when {
        signatureOn -> Variant.SIGNATURE
        foilOn -> Variant.FOIL
        else -> Variant.STANDARD
    }
    val variantChanged = newVariant != entry.variant
    val canSwap = selectedCard != null
    val canUpdateVariant = variantChanged

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text("Edit card", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            CurrentCardChip(entry = entry)

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search cards") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (showingSearch) "Search results" else "Top matches",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            if (displayedList.isEmpty()) {
                Text(
                    if (showingSearch) "No matches." else "No similar cards.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    displayedList.forEach { card ->
                        CandidateRow(
                            card = card,
                            selected = card.id == selectedCard?.id,
                            onClick = { selectedCard = card },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text(
                "Variant",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                VariantToggleRow(
                    label = "Foil",
                    checked = foilOn,
                    onCheckedChange = { on ->
                        foilOn = on
                        if (on) signatureOn = false
                    },
                    enabled = true,
                    modifier = Modifier.weight(1f),
                )
                VariantToggleRow(
                    label = "Signature",
                    checked = signatureOn,
                    onCheckedChange = { on ->
                        signatureOn = on
                        if (on) foilOn = false
                    },
                    enabled = true,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onDelete(entry) },
                    modifier = Modifier.weight(1f),
                ) { Text("Delete") }
                Button(
                    onClick = { onApply(entry.card, newVariant) },
                    enabled = canUpdateVariant,
                    modifier = Modifier.weight(1f),
                ) { Text("Variant") }
                Button(
                    onClick = { selectedCard?.let { onApply(it, newVariant) } },
                    enabled = canSwap,
                    modifier = Modifier.weight(1f),
                ) { Text("Swap") }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CurrentCardChip(entry: ScannedEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.card.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${entry.card.setCode}-${entry.card.collectorNumber} • " +
                        entry.variant.name.lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${(entry.confidence * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandidateRow(
    card: RiftboundCard,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                card.name,
                style = MaterialTheme.typography.bodyLarge,
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

@Composable
private fun VariantToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
