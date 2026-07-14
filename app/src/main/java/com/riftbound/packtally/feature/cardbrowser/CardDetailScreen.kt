package com.riftbound.packtally.feature.cardbrowser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.riftbound.packtally.App
import com.riftbound.packtally.model.CardPrice
import com.riftbound.packtally.model.Variant
import com.riftbound.packtally.ui.common.LoadingIndicator
import com.riftbound.packtally.ui.currency.LocalCurrencyFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(
    cardId: String,
    onNavigateBack: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as App
    val vm: CardDetailViewModel = viewModel(
        factory = remember(cardId) { CardDetailViewModel.factory(app, cardId) },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.card?.name ?: "Card") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> LoadingIndicator(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
            state.card == null -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            ) {
                Text("Card not found.", style = MaterialTheme.typography.bodyLarge)
            }
            else -> CardDetailContent(
                card = state.card!!,
                prices = state.prices,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
        }
    }
}

@Composable
private fun CardDetailContent(
    card: com.riftbound.packtally.model.RiftboundCard,
    prices: Map<Variant, CardPrice?>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AsyncImage(
            model = card.imageUrl,
            contentDescription = card.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(12.dp)),
        )

        Column {
            Text(card.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${card.setCode}-${card.collectorNumber} • " +
                    card.rarity.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (card.domains.isNotEmpty()) {
                Text(
                    card.domains.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val statsLine = buildList {
                if (card.type.isNotBlank()) add(card.type.replaceFirstChar { it.uppercase() })
                card.energy?.let { add("Energy $it") }
                card.might?.let { add("Might $it") }
                card.power?.let { add("Power $it") }
            }.joinToString(" • ")
            if (statsLine.isNotEmpty()) {
                Text(
                    statsLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Variants and cached price", style = MaterialTheme.typography.titleMedium)
            prices.forEach { (variant, price) ->
                VariantPriceRow(variant = variant, price = price)
            }
        }
    }
}

@Composable
private fun VariantPriceRow(variant: Variant, price: CardPrice?) {
    val formatter = LocalCurrencyFormatter.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                variant.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                price?.let { formatter.format(it.marketPrice) } ?: "Not cached",
                style = MaterialTheme.typography.bodyLarge,
                color = if (price == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}
