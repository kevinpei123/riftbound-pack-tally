package com.riftbound.packtally.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.BuildConfig
import com.riftbound.packtally.core.pricing.QuotaState
import com.riftbound.packtally.core.settings.AppSettings
import com.riftbound.packtally.core.settings.Currency
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
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
                is SettingsEvent.CardDbResyncFailed -> "Re-sync failed - ${friendlyError(event.reason)}"
                is SettingsEvent.ExchangeRateUpdated -> "Exchange rate updated for ${event.target}"
                is SettingsEvent.ExchangeRateFailed ->
                    "Exchange rate refresh failed - ${friendlyError(event.reason)}"
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
                val v = it.trim()
                if (v.isBlank() || v.startsWith("tcg_")) onChange(v)
            },
            placeholder = { Text("tcg_...") },
            singleLine = true,
            isError = input.isNotBlank() && !input.startsWith("tcg_"),
            supportingText = {
                if (input.isNotBlank() && !input.startsWith("tcg_")) {
                    Text("JustTCG keys start with tcg_")
                }
            },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Used only when you explicitly submit prices. The key is not exported in backups.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CurrencySection(
    settings: AppSettings,
    refreshing: Boolean,
    onCurrencyChange: (Currency) -> Unit,
    onRefresh: () -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Currency")
        OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(settings.currency.code, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Choose display currency",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(settings.currency.symbol.trim().ifBlank { settings.currency.code })
            }
        }
        val fetched = settings.exchangeRateFetchedAt?.let(::formatTimestamp) ?: "never"
        Text(
            "USD to ${settings.currency.code}: ${String.format(Locale.US, "%.4f", settings.usdToTargetRate)}. " +
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
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Refreshing")
            } else {
                Text("Refresh exchange rate")
            }
        }
    }

    if (showPicker) {
        CurrencyPickerDialog(
            selected = settings.currency,
            onDismiss = { showPicker = false },
            onPick = {
                showPicker = false
                onCurrencyChange(it)
            },
        )
    }
}

@Composable
private fun CurrencyPickerDialog(
    selected: Currency,
    onDismiss: () -> Unit,
    onPick: (Currency) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        val q = query.trim().uppercase()
        Currency.entries.filter {
            q.isBlank() || it.code.contains(q) || it.name.contains(q)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose currency") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search currency code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(filtered, key = { it.code }) { currency ->
                        TextButton(
                            onClick = { onPick(currency) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(currency.code, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        currency.symbol.trim().ifBlank { currency.code },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (currency == selected) Text("Selected")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
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
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
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
    var sliderValue by remember(ttlHours) { mutableStateOf(ttlHours.toFloat()) }
    val draftHours = sliderValue.roundToInt()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel("Price cache TTL - $draftHours hour${if (draftHours == 1) "" else "s"}")
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onChange(sliderValue.roundToInt()) },
            valueRange = 1f..24f,
            steps = 22,
            modifier = Modifier.semantics {
                contentDescription = "Price cache time to live"
                stateDescription = "$draftHours hour${if (draftHours == 1) "" else "s"}"
            },
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

/** Maps raw exception text to friendly, non-technical copy for toasts. */
private fun friendlyError(reason: String): String {
    val r = reason.lowercase(Locale.US)
    return when {
        "unknownhost" in r || "no address" in r || "unable to resolve" in r ->
            "no internet connection"
        "timeout" in r || "timed out" in r -> "the request timed out"
        "connect" in r || "network" in r -> "could not reach the server"
        "401" in r || "403" in r || "unauthorized" in r || "forbidden" in r ->
            "check your API key"
        "429" in r || "rate limit" in r -> "rate limit reached, try again later"
        "500" in r || "502" in r || "503" in r || "504" in r -> "the server had a problem"
        else -> "please try again"
    }
}

private val TIMESTAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())

private fun formatTimestamp(t: Instant): String = TIMESTAMP_FORMATTER.format(t)

private fun formatDurationUntil(target: Instant): String {
    val now = Instant.now()
    if (!now.isBefore(target)) return "00:00"
    val d = Duration.between(now, target)
    val hours = d.toHours()
    val minutes = d.toMinutes() % 60
    return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
