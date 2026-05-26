package com.riftbound.packtally.feature.box

import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.feature.pack.PackViewModel
import com.riftbound.packtally.model.BoxSession
import com.riftbound.packtally.model.PackSession
import com.riftbound.packtally.ui.currency.LocalCurrencyFormatter

@Composable
fun BoxScreen() {
    val activity = LocalContext.current as ComponentActivity
    val packVm: PackViewModel = viewModel(viewModelStoreOwner = activity)
    val box by packVm.box.collectAsStateWithLifecycle()
    val packs by box.packs.collectAsStateWithLifecycle()
    val grandTotal by box.grandTotal.collectAsStateWithLifecycle()

    val lastPack = packs.lastOrNull()
    // Subscribe to last pack's entries so canStartNewPack reacts to entry adds.
    val lastEntriesSize: Int = if (lastPack != null) {
        val entries by lastPack.entries.collectAsStateWithLifecycle()
        entries.size
    } else 0
    val canStartNewPack = lastEntriesSize >= PackSession.CAPACITY && packs.size < box.capacity

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        BoxHeader(
            mode = box.mode,
            packCount = packs.size,
            boxCapacity = box.capacity,
            grandTotal = grandTotal,
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        if (packs.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(packs) { index, pack ->
                    PackSubtotalRow(packNumber = index + 1, pack = pack)
                }
                if (canStartNewPack) {
                    item { NewPackButton(onClick = packVm::completePack) }
                }
            }
        }
    }
}

@Composable
private fun BoxHeader(
    mode: BoxSession.Mode,
    packCount: Int,
    boxCapacity: Int,
    grandTotal: Double,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = when (mode) {
                BoxSession.Mode.SINGLE_PACK -> "Single pack"
                BoxSession.Mode.BOX -> "Box"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            LocalCurrencyFormatter.current.format(grandTotal),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "$packCount / $boxCapacity packs",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PackSubtotalRow(packNumber: Int, pack: PackSession) {
    val total by pack.runningTotal.collectAsStateWithLifecycle()
    val entries by pack.entries.collectAsStateWithLifecycle()
    val isFull = entries.size >= PackSession.CAPACITY

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Pack $packNumber",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${entries.size} / ${PackSession.CAPACITY} cards" +
                        if (isFull) " • complete" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                LocalCurrencyFormatter.current.format(total),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun NewPackButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("+ New Pack")
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "No packs yet — scan a card to start.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
