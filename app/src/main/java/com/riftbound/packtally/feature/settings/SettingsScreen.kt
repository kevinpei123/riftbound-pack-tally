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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.BuildConfig
import com.riftbound.packtally.core.pricing.QuotaState
import com.riftbound.packtally.core.settings.AppSettings
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
    val exchangeRateRefreshing by vm.exchangeRateRefreshing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshCacheSize() }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            val msg = when (event) {
                SettingsEvent.CacheCleared -> "Cache cleared"
                SettingsEvent.ResetComplete -> "All data reset"
                is SettingsEvent.CardDbResynced -> "Synced ${event.cardCount} cards"
                is SettingsEvent.CardDbResyncFailed -> "Re-sync failed - ${event.reason}"
                is SettingsEvent.ExchangeRateUpdated -> "Exchange rate updated for ${event.target}"
                is SettingsEvent.ExchangeRateFailed -> "Exchange rate refresh failed - ${event.reason}"
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

        ApiKeySection(settings = settings, onChange = vm::setApiKey)

        CurrencySection(
            settings = settings,
            refreshing = exchangeRateRefreshing,
            onCurrencyChange = vm::setCurrency,
            onRefresh = vm::refreshExchangeRate,
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

        CacheTtlSection(ttlHours = settings.cacheTtlHours, onChange = vm::setCacheTtlHours)

        OcrPreprocessingSection(
            enabled = settings.forceOcrPreprocessing,
            onChange = vm::setForceOcrPreprocessing,
        )

        if (BuildConfig.DEBUG) {
            OcrDebugLoggingSection(
                enabled = settings.ocrDebugLogging,
                onChange = vm::setOcrDebugLogging,
            )
        }

        OutlinedButton(onClick = vm::clearCache, modifier = Modifier.fillMaxWidth()) {
            Text("Clear price cache (${formatBytes(cacheSize)})")
        }

        OutlinedButton(onClick = onNavigateToBackup, modifier = Modifier.fillMaxWidth()) {
            Text("Backups and restore")
        }

        HorizontalDivider()
        DangerZone(onResetTap = { showResetDialog = true })
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset all data?") },
            text = {
                Text(
                    "Permanently deletes scan sessions, synced cards, price cache, and saved settings. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        vm.resetAll()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { showResetDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ApiKeySection(settings: AppSettings, onChange: (String?) -> Unit) {
    val currentKey = settings.apiKey ?: ""
    var input by remember(currentKey) { mutableStateOf(currentKey) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel("JustTCG API key")
        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                onChange(it)
            },
            placeholder = { Text("tcg_...") },
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
            "Used only when you explicitly submit prices. The key is not exported in backups.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencySection(
    settings: AppSettings,
    refreshing: Boolean,
    onCurrencyChange: (Currency) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Currency")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            Currency.entries.forEachIndexed { index, currency ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = Currency.entries.size),
                    selected = settings.currency == currency,
                    onClick = { onCurrencyChange(currency) },
                ) { Text(currency.code) }
            }
        }
        val fetched = settings.exchangeRateFetchedAt?.let(::formatTimestamp) ?: "never"
        Text(
            "USD to ${settings.currency.code}: ${"%.4f".format(settings.usdToTargetRate)}. " +
                "Last updated $fetched from ${settings.exchangeRateSource ?: "no source"}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        settings.exchangeRateWarning?.let {
            Text(
                "Using cached rate. Last refresh failed: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(
            onClick = onRefresh,
            enabled = !refreshing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Refreshing")
            } else {
                Text("Refresh exchange rate")
            }
        }
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
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Card database", style = MaterialTheme.typography.titleMedium)
            Text(
                "$cardCount cards - ${lastSyncedAt?.let(::formatTimestamp) ?: "not synced"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onResync,
                enabled = !isSyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Re-syncing")
                } else {
                    Text("Re-sync from Riftcodex")
                }
            }
        }
    }
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
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = tone)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("JustTCG quota", style = MaterialTheme.typography.titleMedium)
            QuotaRow("Monthly", quota.monthlyUsed, quota.monthlyLimit, quota.resetsAt)
            QuotaRow("Today", quota.dailyUsed, quota.dailyLimit, quota.dailyResetsAt)
            QuotaRow("This minute", quota.minuteUsed, quota.minuteLimit, quota.minuteResetsAt)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cache-only mode", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Blocks pricing network calls; cached prices still display.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = useCachedOnly, onCheckedChange = onToggleCachedOnly)
            }
            if (BuildConfig.DEBUG) {
                OutlinedButton(onClick = onResetCounter, modifier = Modifier.fillMaxWidth()) {
                    Text("Reset quota counter")
                }
            }
        }
    }
}

@Composable
private fun QuotaRow(label: String, used: Int, limit: Int, resetsAt: Instant) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("$label: $used / $limit", style = MaterialTheme.typography.bodyMedium)
            Text(
                "resets in ${formatDurationUntil(resetsAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { if (limit == 0) 0f else (used.toFloat() / limit).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CacheTtlSection(ttlHours: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("Price cache TTL - $ttlHours hour${if (ttlHours == 1) "" else "s"}")
        Slider(
            value = ttlHours.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = 1f..24f,
            steps = 22,
        )
        Text(
            "Cache hits do not burn JustTCG quota.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OcrPreprocessingSection(enabled: Boolean, onChange: (Boolean) -> Unit) {
    ToggleRow(
        title = "Force OCR preprocessing",
        description = "Always apply grayscale and contrast before ML Kit.",
        checked = enabled,
        onChange = onChange,
    )
}

@Composable
private fun OcrDebugLoggingSection(enabled: Boolean, onChange: (Boolean) -> Unit) {
    ToggleRow(
        title = "OCR debug logging",
        description = "Debug builds only. Logs raw OCR and parser decisions.",
        checked = enabled,
        onChange = onChange,
    )
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            SectionLabel(title)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
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
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

private fun formatTimestamp(t: Instant): String =
    t.atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

private fun formatDurationUntil(target: Instant): String {
    val now = Instant.now()
    if (!now.isBefore(target)) return "00:00"
    val d = Duration.between(now, target)
    val hours = d.toHours()
    val minutes = d.toMinutes() % 60
    return "%02d:%02d".format(hours, minutes)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
