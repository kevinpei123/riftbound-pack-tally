package com.riftbound.packtally.feature.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.App
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.Variant

private const val RESCAN_THRESHOLD = 0.7f
private val SCANNER_GUIDE_RECT = Rect(left = 0.09f, top = 0.055f, right = 0.91f, bottom = 0.68f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(onNavigateToCurrent: () -> Unit = {}) {
    val app = LocalContext.current.applicationContext as App
    val factory = remember(app) { ScannerViewModel.factory(app) }
    val scannerVm: ScannerViewModel = viewModel(factory = factory)

    val result by scannerVm.scanResult.collectAsStateWithLifecycle()
    val rapidMode by scannerVm.rapidMode.collectAsStateWithLifecycle()
    val activeSession by scannerVm.activeSession.collectAsStateWithLifecycle()
    val lastAdded by scannerVm.lastAdded.collectAsStateWithLifecycle()

    val lastAddedText = lastAdded?.let {
        "${it.card.name} (${it.variant.name.lowercase().replaceFirstChar { c -> c.uppercase() }})"
    }
    val captureBottomPadding = if (lastAddedText == null) 138.dp else 152.dp

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val haptics = LocalHapticFeedback.current
    LaunchedEffect(scannerVm) {
        scannerVm.rapidAddEvents.collect {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        CameraScreen(
            onCardCaptured = scannerVm::onCardCaptured,
            guideRect = SCANNER_GUIDE_RECT,
            captureButtonBottomPadding = captureBottomPadding,
            onError = {
                scope.launch { snackbarHostState.showSnackbar("Capture failed, try again") }
            },
            modifier = Modifier.fillMaxSize(),
        )

        ScannerControlsDock(
            totalCards = activeSession?.totalCards ?: 0,
            pendingCount = activeSession?.pendingPriceCount ?: 0,
            rapidMode = rapidMode,
            lastAdded = lastAddedText,
            onRapidMode = scannerVm::setRapidMode,
            onNavigateToCurrent = onNavigateToCurrent,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    val showSheet = result !is ScanResult.Idle && result !is ScanResult.Scanning
    if (showSheet) {
        ModalBottomSheet(onDismissRequest = scannerVm::reset) {
            when (val r = result) {
                is ScanResult.Identified -> IdentifiedContent(
                    card = r.card,
                    showRescan = r.confidence < RESCAN_THRESHOLD,
                    onVariantSelected = { variant -> scannerVm.recordCard(r.card, variant) },
                    onRescan = scannerVm::reset,
                )
                is ScanResult.Ambiguous -> AmbiguousContent(
                    candidates = r.candidates,
                    onPickCandidate = scannerVm::pickCandidate,
                    onRescan = scannerVm::reset,
                )
                is ScanResult.Failed -> FailedContent(
                    reason = r.reason,
                    onRescan = scannerVm::reset,
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun ScannerControlsDock(
    totalCards: Int,
    pendingCount: Int,
    rapidMode: Boolean,
    lastAdded: String?,
    onRapidMode: (Boolean) -> Unit,
    onNavigateToCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        shape = RoundedCornerShape(22.dp),
        color = Color.Black.copy(alpha = 0.94f),
        contentColor = Color.White,
        shadowElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Scan session",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = onNavigateToCurrent,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ListAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("List")
                    }
                    Row(
                        modifier = Modifier
                            .toggleable(
                                value = rapidMode,
                                role = Role.Switch,
                                onValueChange = onRapidMode,
                            )
                            .semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Rapid",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                        Switch(
                            checked = rapidMode,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = Color.White,
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color.Transparent,
                                uncheckedBorderColor = Color.White.copy(alpha = 0.72f),
                            ),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScannerMetricChip("$totalCards cards")
                ScannerMetricChip("$pendingCount pending")
            }

            Text(
                text = lastAdded?.let { "Last added: $it" } ?: "Align the card inside the frame",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ScannerMetricChip(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.1f),
        contentColor = Color.White,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun IdentifiedContent(
    card: RiftboundCard,
    showRescan: Boolean,
    onVariantSelected: (Variant) -> Unit,
    onRescan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text(
            card.name,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${card.collectorNumber} - " +
                card.rarity.name.lowercase().replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onVariantSelected(Variant.STANDARD) },
                modifier = Modifier.weight(1f),
            ) { Text("Standard") }
            Button(
                onClick = { onVariantSelected(Variant.FOIL) },
                modifier = Modifier.weight(1f),
            ) { Text("Foil") }
            if (card.hasSignatureVariant) {
                Button(
                    onClick = { onVariantSelected(Variant.SIGNATURE) },
                    modifier = Modifier.weight(1f),
                ) { Text("Signature") }
            }
        }

        if (showRescan) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
                Text("Re-scan")
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AmbiguousContent(
    candidates: List<RiftboundCard>,
    onPickCandidate: (RiftboundCard) -> Unit,
    onRescan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text("Multiple possible matches", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        candidates.forEach { card ->
            TextButton(
                onClick = { onPickCandidate(card) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        card.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        card.collectorNumber,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
            Text("Re-scan")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FailedContent(
    reason: String,
    onRescan: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Could not identify card", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Retry with the collector number inside the guide, reduce glare, or add the card manually from Current.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
            Text("Retry")
        }
        Spacer(Modifier.height(16.dp))
    }
}
