package com.riftbound.packtally.feature.session

import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.core.persistence.SessionRepository
import com.riftbound.packtally.feature.common.ManualAddSheet
import com.riftbound.packtally.model.PricingStatus
import com.riftbound.packtally.model.ScanSession
import com.riftbound.packtally.model.ScanSessionEntry
import com.riftbound.packtally.model.Variant
import com.riftbound.packtally.ui.currency.LocalCurrencyFormatter
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CurrentSessionScreen(onNavigateToScan: () -> Unit = {}) {
    val vm: CurrentSessionViewModel = viewModel()
    val session by vm.activeSession.collectAsStateWithLifecycle()
    val submitInFlight by vm.submitInFlight.collectAsStateWithLifecycle()
    val pricingProgress by vm.pricingProgress.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showAddSheet by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }
    var completeConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            val message = when (event) {
                CurrentSessionEvent.Started -> "Started session"
                is CurrentSessionEvent.Added -> "Added ${event.name}"
                CurrentSessionEvent.Removed -> "Removed card"
                CurrentSessionEvent.Undone -> "Undid last scan"
                CurrentSessionEvent.Cleared -> "Session cleared"
                CurrentSessionEvent.Completed -> "Session completed"
                is CurrentSessionEvent.PricingDone ->
                    "Priced ${event.priced}, failed ${event.failed}, unpriceable ${event.unpriceable}"
                is CurrentSessionEvent.Error -> event.message
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val current = session
    if (current == null) {
        EmptyCurrentSession(
            onStart = {
                vm.startNewSession()
                onNavigateToScan()
            },
        )
    } else {
        CurrentSessionContent(
            session = current,
            submitInFlight = submitInFlight,
            pricingProgress = pricingProgress,
            onScan = onNavigateToScan,
            onAddManual = { showAddSheet = true },
            onUndo = vm::undoLastScan,
            onRemove = vm::remove,
            onChangeVariant = vm::changeVariant,
            onSubmit = vm::submitPendingPrices,
            onClear = { clearConfirm = true },
            onComplete = { completeConfirm = true },
        )
    }

    if (showAddSheet) {
        ManualAddSheet(
            title = "Add to current session",
            onPicked = { card, variant ->
                vm.addManual(card, variant)
                showAddSheet = false
            },
            onDismiss = { showAddSheet = false },
        )
    }

    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("Clear session?") },
            text = { Text("Removes every card from this active session.") },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirm = false
                    vm.clearSession()
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (completeConfirm) {
        AlertDialog(
            onDismissRequest = { completeConfirm = false },
            title = { Text("End session?") },
            text = { Text("The cards stay in Collection. Pending prices can be submitted later.") },
            confirmButton = {
                TextButton(onClick = {
                    completeConfirm = false
                    vm.completeSession()
                }) { Text("End") }
            },
            dismissButton = {
                TextButton(onClick = { completeConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyCurrentSession(onStart: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("No active session", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Start a scan session to build one running list.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onStart) { Text("Start scanning") }
        }
    }
}

@Composable
private fun CurrentSessionContent(
    session: ScanSession,
    submitInFlight: Boolean,
    pricingProgress: String?,
    onScan: () -> Unit,
    onAddManual: () -> Unit,
    onUndo: () -> Unit,
    onRemove: (String) -> Unit,
    onChangeVariant: (String, Variant) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onComplete: () -> Unit,
) {
    val entries = session.entries.sortedByDescending { it.scannedAt }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            SessionHeader(
                session = session,
                submitInFlight = submitInFlight,
                pricingProgress = pricingProgress,
                onScan = onScan,
                onAddManual = onAddManual,
                onUndo = onUndo,
                onSubmit = onSubmit,
                onClear = onClear,
                onComplete = onComplete,
            )
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    "No cards in this session yet.",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(entries, key = { it.id }) { entry ->
                SessionEntryRow(
                    entry = entry,
                    onRemove = { onRemove(entry.id) },
                    onChangeVariant = { variant -> onChangeVariant(entry.id, variant) },
                )
            }
        }
    }
}

@Composable
private fun SessionHeader(
    session: ScanSession,
    submitInFlight: Boolean,
    pricingProgress: String?,
    onScan: () -> Unit,
    onAddManual: () -> Unit,
    onUndo: () -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onComplete: () -> Unit,
) {
    val formatter = LocalCurrencyFormatter.current
    val pending = session.pendingPriceCount
    val duplicateCount = session.entries.groupBy { it.card.id to it.variant }.count { it.value.size > 1 }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(session.displayName, style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = {}, label = { Text("${session.totalCards} cards") })
            AssistChip(onClick = {}, label = { Text("${session.entries.map { it.card.id }.distinct().size} unique") })
            AssistChip(onClick = {}, label = { Text("${formatter.format(session.totalValueUsd)} value") })
        }
        if (duplicateCount > 0) {
            Text(
                "$duplicateCount duplicate group${if (duplicateCount == 1) "" else "s"} in this session",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onScan, modifier = Modifier.weight(1f)) {
                Text("Scan")
            }
            OutlinedButton(onClick = onAddManual, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onUndo,
                enabled = session.entries.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Undo")
            }
            OutlinedButton(
                onClick = onComplete,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Done, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("End")
            }
        }
        Button(
            onClick = onSubmit,
            enabled = pending > 0 && !submitInFlight,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (submitInFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(pricingProgress ?: "Pricing")
            } else {
                Text(SessionRepository.submitLabelFor(pending))
            }
        }
        OutlinedButton(
            onClick = onClear,
            enabled = session.entries.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Clear session") }
    }
}

@Composable
private fun SessionEntryRow(
    entry: ScanSessionEntry,
    onRemove: () -> Unit,
    onChangeVariant: (Variant) -> Unit,
) {
    var variantMenuOpen by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.card.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${entry.card.collectorNumber} - ${entry.card.rarity.name.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = false,
                        onClick = { variantMenuOpen = true },
                        label = { Text(entry.variant.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        leadingIcon = {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                    PricingChip(entry)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    LocalCurrencyFormatter.current.format(entry.marketPrice),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    entry.scannedAt.atZone(ZoneId.systemDefault()).format(TIME_FORMAT),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                DropdownMenu(
                    expanded = variantMenuOpen,
                    onDismissRequest = { variantMenuOpen = false },
                ) {
                    Variant.entries.forEach { variant ->
                        DropdownMenuItem(
                            text = { Text(variant.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            onClick = {
                                variantMenuOpen = false
                                onChangeVariant(variant)
                            },
                        )
                    }
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = "Remove ${entry.card.name}")
            }
        }
    }
}

@Composable
private fun PricingChip(entry: ScanSessionEntry) {
    val label = when (entry.pricingStatus) {
        PricingStatus.PENDING -> "Pending"
        PricingStatus.PRICED -> "Priced"
        PricingStatus.FAILED -> "Failed"
        PricingStatus.UNPRICEABLE -> "No TCG ID"
    }
    AssistChip(onClick = {}, label = { Text(label) })
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
