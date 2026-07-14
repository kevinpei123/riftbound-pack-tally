package com.riftbound.packtally.feature.backup

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.riftbound.packtally.BuildConfig
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onNavigateBack: () -> Unit = {}) {
    val vm: BackupViewModel = viewModel()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            val msg = when (event) {
                is BackupEvent.BackupSucceeded ->
                    "Backup saved: ${event.path.substringAfterLast('/').substringAfterLast('\\')}"
                is BackupEvent.BackupFailed -> "Backup failed: ${event.reason}"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Backups and restore") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
        Text("Backups", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Bundles the session database, price cache, and (sanitized) settings " +
                "into a zip under your app's external files directory. Pull via " +
                "USB to keep an offline copy. API key is NOT included — re-enter " +
                "it after restore.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SummaryCard(summary = summary)

        Button(
            onClick = vm::backupNow,
            modifier = Modifier.fillMaxWidth(),
            enabled = !summary.isBusy,
        ) {
            if (summary.isBusy) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Backing up…")
                }
            } else {
                Text("Back up now")
            }
        }

        if (BuildConfig.DEBUG) {
            HorizontalDivider()

            Text(
                "Restore from file",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Picking a backup file and applying it requires closing and " +
                    "re-opening the database, which I haven't wired into the " +
                    "app yet — for now the easiest restore path is via adb push:\n\n" +
                    "  adb push backup.zip /sdcard/Android/data/com.riftbound.packtally/files/backups/\n" +
                    "  adb shell am force-stop com.riftbound.packtally\n" +
                    "  (manually extract database.db and overwrite via Files app)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = {
                    Toast.makeText(
                        context,
                        "Restore UI not wired yet — see the adb path above.",
                        Toast.LENGTH_LONG,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Restore from file (placeholder)") }
        }

        if (BuildConfig.DEBUG) {
            HorizontalDivider()

            Text(
                "Auto-backup",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "WorkManager scaffolding is in place but the periodic job isn't " +
                    "registered yet. The plan: daily PeriodicWorkRequest, 1h flex " +
                    "window, keep the 7 newest in auto-backups/, skip when battery " +
                    "is critical.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        }
    }
}

@Composable
private fun SummaryCard(summary: BackupSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SummaryRow(
                label = "Last manual backup",
                value = summary.lastManualBackup?.let { describeAge(it.createdAt) } ?: "Never",
            )
            SummaryRow(
                label = "Last auto backup",
                value = summary.lastAutoBackup?.let { describeAge(it.createdAt) } ?: "Never",
            )
            SummaryRow(
                label = "Total on disk",
                value = formatBytes(summary.totalSizeBytes),
            )
            SummaryRow(
                label = "Manual / auto files",
                value = "${summary.manualCount} / ${summary.autoCount}",
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private val ABSOLUTE_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

private fun describeAge(createdAt: Instant): String {
    val now = Instant.now()
    val d = Duration.between(createdAt, now)
    return when {
        // Clock skew / future mtime — don't render a negative "ago".
        d.isNegative -> "Just now"
        d.toMinutes() < 1 -> "Just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 24 -> "${d.toHours()} h ago"
        d.toDays() < 7 -> "${d.toDays()} d ago"
        else -> createdAt.atZone(ZoneId.systemDefault()).format(ABSOLUTE_DATE_FORMATTER)
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
}
