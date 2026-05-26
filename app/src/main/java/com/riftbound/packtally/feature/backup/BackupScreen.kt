package com.riftbound.packtally.feature.backup

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Duration
import java.time.Instant

@Composable
fun BackupScreen() {
    val vm: BackupViewModel = viewModel()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            val msg = when (event) {
                is BackupEvent.BackupSucceeded -> "Backup saved to ${event.path}"
                is BackupEvent.BackupFailed -> "Backup failed: ${event.reason}"
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
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.height(8.dp))
                Text("Backing up…")
            } else {
                Text("Back up now")
            }
        }

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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun describeAge(createdAt: Instant): String {
    val now = Instant.now()
    val d = Duration.between(createdAt, now)
    return when {
        d.toMinutes() < 1 -> "Just now"
        d.toMinutes() < 60 -> "${d.toMinutes()} min ago"
        d.toHours() < 48 -> "${d.toHours()} h ago"
        else -> "${d.toDays()} d ago"
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
