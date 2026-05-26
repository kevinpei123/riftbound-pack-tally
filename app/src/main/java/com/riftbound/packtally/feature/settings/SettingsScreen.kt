package com.riftbound.packtally.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.core.settings.AppSettings
import com.riftbound.packtally.core.pricing.QuotaState
import com.riftbound.packtally.core.settings.Currency
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateToBackup: () -> Unit = {}) {
    val vm: SettingsViewModel = viewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val cacheSize by vm.cacheSizeBytes.collectAsStateWithLifecycle()
    val quota by vm.quota.collectAsStateWithLifecycle()
    val useCachedOnly by vm.useCachedOnly.collectAsStateWithLifecycle()
    val cardDbLastSyncedAt by vm.cardDbLastSyncedAt.collectAsStateWithLifecycle()
    val cardCount by vm.cardCount.collectAsStateWithLifecycle()
    val cardDbSyncing by vm.cardDbSyncing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshCacheSize() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            val msg = when (event) {
                SettingsEvent.CacheCleared -> "Cache cleared"
                SettingsEvent.ResetComplete -> "All data reset"
                is SettingsEvent.CardDbResynced -> "Synced ${event.cardCount} cards"
                is SettingsEvent.CardDbResyncFailed -> "Re-sync failed — ${event.reason}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        ApiKeySection(
            settings = settings,
            onChange = vm::setApiKey,
        )

        CurrencySection(
            settings = settings,
            onCurrencyChange = vm::setCurrency,
            onRateChange = vm::setConversionRate,
        )

        CardDatabaseSection(
            cardCount = cardCount,
            lastSyncedAt = cardDbLastSyncedAt,
            isSyncing = cardDbSyncing,
            onResync = vm::resyncCardDatabase,
        )

        QuotaCard(
            quota = quota,
            useCachedOnly = useCachedOnly,
            onToggleCachedOnly = vm::setUseCachedOnly,
            onResetCounter = vm::resetQuotaCounter,
        )

        CacheTtlSection(
            ttlHours = settings.cacheTtlHours,
            onChange = vm::setCacheTtlHours,
        )

        OcrPreprocessingSection(
            enabled = settings.forceOcrPreprocessing,
            onChange = vm::setForceOcrPreprocessing,
        )

        ClearCacheRow(
            cacheSizeBytes = cacheSize,
            onClearCache = vm::clearCache,
        )

        OutlinedButton(
            onClick = onNavigateToBackup,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Backups & restore →") }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        DangerZone(onResetTap = { showResetDialog = true })
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset all data?") },
            text = {
                Text(
                    "Permanently deletes all scanned sessions, the price cache, " +
                        "and saved settings.\n\nThis cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        vm.resetAll()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ApiKeySection(
    settings: AppSettings,
    onChange: (String?) -> Unit,
) {
    val currentKey = settings.apiKey ?: ""
    var input by remember(currentKey) { mutableStateOf(currentKey) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("JustTCG API key")
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                onChange(it)
            },
            placeholder = { Text("tcg_…") },
            singleLine = true,
            isError = input.isNotBlank() && !input.startsWith("tcg_"),
            supportingText = {
                if (input.isNotBlank() && !input.startsWith("tcg_")) {
                    Text("JustTCG keys start with tcg_")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Get a free key at justtcg.com — 1,000 requests/month, 100/day, no credit card. " +
                "Saved in DataStore as plain text. Personal-use only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencySection(
    settings: AppSettings,
    onCurrencyChange: (Currency) -> Unit,
    onRateChange: (Double) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Currency")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Currency.entries.forEachIndexed { index, currency ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = Currency.entries.size,
                    ),
                    selected = settings.currency == currency,
                    onClick = { onCurrencyChange(currency) },
                ) { Text(currency.code) }
            }
        }
        if (settings.currency != Currency.USD) {
            val currentRate = settings.usdToTargetRate
            var rateInput by remember(currentRate) {
                mutableStateOf("%.4f".format(currentRate))
            }
            OutlinedTextField(
                value = rateInput,
                onValueChange = {
                    rateInput = it
                    it.toDoubleOrNull()?.let(onRateChange)
                },
                label = { Text("USD → ${settings.currency.code} rate") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Hard-coded rate — update manually when it drifts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CacheTtlSection(
    ttlHours: Int,
    onChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("Cache TTL — $ttlHours hour" + if (ttlHours == 1) "" else "s")
        Slider(
            value = ttlHours.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            // 1..24h range — JustTCG refreshes ~4h, so anything past 24h
            // shows stale prices. Default 6h.
            valueRange = 1f..24f,
            steps = 22,
        )
        Text(
            "Prices cached longer than this expire and refetch from tcgapi.dev.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CardDatabaseSection(
    cardCount: Int,
    lastSyncedAt: Instant?,
    isSyncing: Boolean,
    onResync: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Card database", style = MaterialTheme.typography.titleMedium)
            val syncedLabel = lastSyncedAt
                ?.let { "last synced ${formatTimestamp(it)}" }
                ?: "not yet synced"
            Text(
                "$cardCount cards · $syncedLabel",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onResync,
                enabled = !isSyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSyncing) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.height(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.height(0.dp).padding(start = 8.dp))
                    Text("Re-syncing…")
                } else {
                    Text("Re-sync from Riftcodex")
                }
            }
            Text(
                "Riftcodex auto-resyncs weekly in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTimestamp(t: Instant): String {
    val zoned = t.atZone(java.time.ZoneId.systemDefault())
    return zoned.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}

@Composable
private fun QuotaCard(
    quota: QuotaState,
    useCachedOnly: Boolean,
    onToggleCachedOnly: (Boolean) -> Unit,
    onResetCounter: () -> Unit,
) {
    val tone = when {
        quota.monthlyPercentUsed >= 0.95f -> MaterialTheme.colorScheme.errorContainer
        quota.monthlyPercentUsed >= 0.80f -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tone),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("JustTCG quota", style = MaterialTheme.typography.titleMedium)
            QuotaRow(
                label = "Monthly",
                used = quota.monthlyUsed,
                limit = quota.monthlyLimit,
                resetsAt = quota.resetsAt,
                resetLabel = "next billing reset",
            )
            QuotaRow(
                label = "Today",
                used = quota.dailyUsed,
                limit = quota.dailyLimit,
                resetsAt = quota.dailyResetsAt,
                resetLabel = "UTC midnight",
            )
            QuotaRow(
                label = "This minute",
                used = quota.minuteUsed,
                limit = quota.minuteLimit,
                resetsAt = quota.minuteResetsAt,
                resetLabel = "next minute",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cache-only mode", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Block all network calls for this session. Cached prices " +
                            "still load. Auto-clears on app restart or UTC midnight.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = useCachedOnly, onCheckedChange = onToggleCachedOnly)
            }

            OutlinedButton(
                onClick = onResetCounter,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Reset counter (debug)") }
        }
    }
}

@Composable
private fun QuotaRow(
    label: String,
    used: Int,
    limit: Int,
    resetsAt: Instant,
    resetLabel: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("$label: $used / $limit", style = MaterialTheme.typography.bodyMedium)
            Text(
                "in ${formatDurationUntil(resetsAt)} ($resetLabel)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val pct = if (limit == 0) 0f else (used.toFloat() / limit).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
    }
}

private fun formatDurationUntil(target: Instant): String {
    val now = Instant.now()
    if (!now.isBefore(target)) return "00:00"
    val d = Duration.between(now, target)
    val hours = d.toHours()
    val minutes = d.toMinutes() % 60
    return "%02d:%02d".format(hours, minutes)
}

@Composable
private fun OcrPreprocessingSection(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SectionLabel("Force OCR preprocessing")
            Text(
                "Always apply grayscale + 1.5× contrast before scanning. Helps " +
                    "with foil / signature glare. Otsu binarization still kicks in " +
                    "automatically when a scan returns low-confidence blocks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun ClearCacheRow(
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
) {
    OutlinedButton(
        onClick = onClearCache,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Clear cache (${formatBytes(cacheSizeBytes)})")
    }
}

@Composable
private fun DangerZone(onResetTap: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Danger zone",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold,
        )
        Button(
            onClick = onResetTap,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reset all data") }
        Text(
            "Wipes scanned sessions, the price cache, and saved settings.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
