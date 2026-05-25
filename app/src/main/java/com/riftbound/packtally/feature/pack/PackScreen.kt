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
import com.riftbound.packtally.model.ScannedEntry

@Composable
fun PackScreen() {
    val activity = LocalContext.current as ComponentActivity
    val packVm: PackViewModel = viewModel(viewModelStoreOwner = activity)
    val session by packVm.session.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "Pack — ${session.entries.size} card" + if (session.entries.size == 1) "" else "s",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(16.dp))

        if (session.entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No cards yet. Scan one!",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(session.entries) { entry -> EntryRow(entry) }
            }
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
