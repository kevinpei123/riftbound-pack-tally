package com.riftbound.packtally.feature.backup

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riftbound.packtally.App
import com.riftbound.packtally.core.backup.BackupRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

private const val TAG = "BackupViewModel"

data class BackupSummary(
    val lastManualBackup: BackupFile? = null,
    val lastAutoBackup: BackupFile? = null,
    val totalSizeBytes: Long = 0,
    val manualCount: Int = 0,
    val autoCount: Int = 0,
    val isBusy: Boolean = false,
)

data class BackupFile(
    val path: String,
    val sizeBytes: Long,
    val createdAt: Instant,
)

sealed interface BackupEvent {
    data class BackupSucceeded(val path: String) : BackupEvent
    data class BackupFailed(val reason: String) : BackupEvent
}

class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val backups: BackupRepository = (application as App).backupRepository

    private val _summary = MutableStateFlow(BackupSummary())
    val summary: StateFlow<BackupSummary> = _summary.asStateFlow()

    private val _events = MutableSharedFlow<BackupEvent>()
    val events: SharedFlow<BackupEvent> = _events.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val manual = runCatching { backups.listManualBackups() }
                .onFailure { Log.e(TAG, "list manual backups failed", it) }
                .getOrDefault(emptyList())
            val auto = runCatching { backups.listAutoBackups() }
                .onFailure { Log.e(TAG, "list auto backups failed", it) }
                .getOrDefault(emptyList())
            val total = runCatching { backups.totalBackupSizeBytes() }
                .getOrDefault(0L)
            _summary.value = BackupSummary(
                lastManualBackup = manual.firstOrNull()?.toBackupFile(),
                lastAutoBackup = auto.firstOrNull()?.toBackupFile(),
                totalSizeBytes = total,
                manualCount = manual.size,
                autoCount = auto.size,
                isBusy = false,
            )
        }
    }

    fun backupNow() {
        viewModelScope.launch {
            _summary.update { it.copy(isBusy = true) }
            val result = runCatching { backups.createManualBackup() }
            result.onSuccess { file ->
                _events.emit(BackupEvent.BackupSucceeded(file.absolutePath))
                refresh()
            }.onFailure { exc ->
                Log.e(TAG, "Backup failed", exc)
                _events.emit(BackupEvent.BackupFailed(friendlyBackupError(exc)))
                _summary.update { it.copy(isBusy = false) }
            }
        }
    }
}

private fun File.toBackupFile(): BackupFile = BackupFile(
    path = absolutePath,
    sizeBytes = length(),
    createdAt = Instant.ofEpochMilli(lastModified()),
)

/** Map raw exception text to user-friendly copy instead of surfacing stack-trace messages. */
private fun friendlyBackupError(exc: Throwable): String = when (exc) {
    is java.io.IOException ->
        "Couldn't write the backup — check that there's free storage space and try again."
    is SecurityException ->
        "Couldn't access storage to save the backup."
    else -> "Backup failed. Please try again."
}
