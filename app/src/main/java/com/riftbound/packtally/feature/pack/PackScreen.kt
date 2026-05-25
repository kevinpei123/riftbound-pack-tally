package com.riftbound.packtally.feature.pack

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.model.BoxSession
import com.riftbound.packtally.model.PackSession
import com.riftbound.packtally.model.ScannedEntry

@Composable
fun PackScreen() {
    val activity = LocalContext.current as ComponentActivity
    val packVm: PackViewModel = viewModel(viewModelStoreOwner = activity)
    val box by packVm.box.collectAsStateWithLifecycle()
    val packs by box.packs.collectAsStateWithLifecycle()
    val grandTotal by box.grandTotal.collectAsStateWithLifecycle()

    val activePack = packs.lastOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        BoxHeader(
            packCount = packs.size,
            grandTotal = grandTotal,
        )
        Spacer(Modifier.height(12.dp))

        if (activePack == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No cards yet. Scan one!",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            ActivePackBlock(pack = activePack)
        }
    }
}

@Composable
private fun BoxHeader(packCount: Int, grandTotal: Double) {
    Column {
        Text(
            "Box — $packCount / ${BoxSession.CAPACITY} packs",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Grand total $${"%.2f".format(grandTotal)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActivePackBlock(pack: PackSession) {
    val entries by pack.entries.collectAsStateWithLifecycle()
    val runningTotal by pack.runningTotal.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxWidth()) {
        Text(
            "Current pack — ${entries.size} / ${PackSession.CAPACITY}",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "Pack total $${"%.2f".format(runningTotal)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(entries) { entry -> EntryRow(entry) }
        }
    }
}

@Composable
private fun EntryRow(entry: ScannedEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.card.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${entry.card.setCode}-${entry.card.collectorNumber} • " +
                    entry.variant.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "$${"%.2f".format(entry.price.marketPrice)}",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
