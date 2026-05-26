package com.riftbound.packtally.feature.pack

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.stickyHeader
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.model.PackSession
import com.riftbound.packtally.model.ScannedEntry

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PackScreen(onNavigateToScanner: () -> Unit) {
    val activity = LocalContext.current as ComponentActivity
    val packVm: PackViewModel = viewModel(viewModelStoreOwner = activity)
    val box by packVm.box.collectAsStateWithLifecycle()
    val packs by box.packs.collectAsStateWithLifecycle()
    val grandTotal by box.grandTotal.collectAsStateWithLifecycle()
    val correction by packVm.correction.collectAsStateWithLifecycle()
    val activePack = packs.lastOrNull()

    val entries: List<ScannedEntry>
    val runningTotal: Double
    if (activePack != null) {
        entries = activePack.entries.collectAsStateWithLifecycle().value
        runningTotal = activePack.runningTotal.collectAsStateWithLifecycle().value
    } else {
        entries = emptyList()
        runningTotal = 0.0
    }

    val packIsFull = activePack?.isFull == true
    val canStartNextPack = packs.size < box.capacity

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToScanner,
                icon = { Icon(Icons.Filled.PhotoCamera, contentDescription = null) },
                text = { Text("Scan") },
            )
        },
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            stickyHeader {
                PackHeader(
                    packNumber = packs.size.coerceAtLeast(1),
                    boxCapacity = box.capacity,
                    filledCount = entries.size,
                    runningTotal = runningTotal,
                    grandTotal = grandTotal,
                    showCompleteButton = packIsFull,
                    completeButtonLabel = if (canStartNextPack) "Complete pack →" else "Finish",
                    onCompletePack = packVm::completePack,
                )
            }
            items(PackSession.CAPACITY) { index ->
                val entry = entries.getOrNull(index)
                if (entry != null) {
                    FilledCell(
                        entry = entry,
                        onClick = { packVm.beginCorrection(entry) },
                        modifier = Modifier.aspectRatio(0.72f),
                    )
                } else {
                    EmptyCell(onClick = onNavigateToScanner, modifier = Modifier.aspectRatio(0.72f))
                }
            }
        }
    }

    correction?.let { state ->
        CorrectionSheet(
            state = state,
            onDismiss = packVm::cancelCorrection,
            onDelete = packVm::deleteEntry,
            onApply = { newCard, newVariant ->
                packVm.applyReplacement(state.entry, newCard, newVariant)
            },
        )
    }
}

@Composable
private fun PackHeader(
    packNumber: Int,
    boxCapacity: Int,
    filledCount: Int,
    runningTotal: Double,
    grandTotal: Double,
    showCompleteButton: Boolean,
    completeButtonLabel: String,
    onCompletePack: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = if (boxCapacity == 1) "Single pack" else "Pack $packNumber / $boxCapacity",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "$filledCount / ${PackSession.CAPACITY} cards",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "$${"%.2f".format(runningTotal)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (boxCapacity > 1) {
                        Text(
                            "Box $${"%.2f".format(grandTotal)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (showCompleteButton) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onCompletePack,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(completeButtonLabel)
                }
            }
        }
    }
}

@Composable
private fun FilledCell(
    entry: ScannedEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                entry.card.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$${"%.2f".format(entry.price.marketPrice)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun EmptyCell(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Tap to scan",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
