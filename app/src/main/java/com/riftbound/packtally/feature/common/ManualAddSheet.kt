package com.riftbound.packtally.feature.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.model.Rarity
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.Variant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val SEARCH_DEBOUNCE_MS = 250L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAddSheet(
    title: String,
    onPicked: (RiftboundCard, Variant) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var debounced by remember { mutableStateOf("") }
    var pickedCard by remember { mutableStateOf<RiftboundCard?>(null) }
    val searchFocusRequester = remember { FocusRequester() }

    // Search is the primary action of this sheet, so open the keyboard on the
    // name field as soon as the sheet appears (and again after backing out of
    // the variant picker).
    LaunchedEffect(pickedCard) {
        if (pickedCard == null) searchFocusRequester.requestFocus()
    }

    LaunchedEffect(query) {
        if (query == debounced) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        debounced = query
    }

    val results by produceState(initialValue = emptyList<RiftboundCard>(), debounced) {
        value = if (debounced.isBlank()) emptyList()
                else withContext(Dispatchers.Default) { CardDatabase.lookupByNameFuzzy(debounced, limit = 12) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val card = pickedCard
            if (card == null) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Card name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Search,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester),
                )
                when {
                    debounced.isBlank() -> Text(
                        "Type a card name.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    results.isEmpty() -> Text(
                        "No matches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(results, key = { it.id }) { result ->
                            CardCandidateRow(result) { pickedCard = result }
                        }
                    }
                }
            } else {
                Text("Pick a variant", style = MaterialTheme.typography.titleLarge)
                Column {
                    Text(
                        card.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${card.collectorNumber} - ${card.rarity.displayName()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onPicked(card, Variant.STANDARD) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Standard") }
                    Button(
                        onClick = { onPicked(card, Variant.FOIL) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Foil") }
                    if (card.hasSignatureVariant) {
                        Button(
                            onClick = { onPicked(card, Variant.SIGNATURE) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Signature") }
                    }
                }
                OutlinedButton(
                    onClick = { pickedCard = null },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Pick a different card") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardCandidateRow(card: RiftboundCard, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                card.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${card.collectorNumber} - ${card.setCode} - ${card.rarity.displayName()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Title-cased rarity label (e.g. "Common"). Centralizes the formatting that was
 * previously inlined at each call site in this sheet. */
private fun Rarity.displayName(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }
