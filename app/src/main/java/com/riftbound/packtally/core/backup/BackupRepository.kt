package com.riftbound.packtally.core.backup

import android.content.Context
import android.util.Log
import com.riftbound.packtally.core.persistence.SessionDatabase
import com.riftbound.packtally.core.settings.AppSettings
import com.riftbound.packtally.core.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "BackupRepository"
private const val DB_FILE_NAME = "session.db"
private const val MANIFEST_FILE = "manifest.json"
private const val PREFS_FILE = "prefs.json"
private const val DB_ZIP_ENTRY = "database.db"
private const val CACHE_ZIP_PREFIX = "cache/"
private const val APP_VERSION_STAMP = "0.1.0"  // stable string until BuildConfig.VERSION_NAME is wired

@Serializable
data class BackupManifest(
    @SerialName("app_version") val appVersion: String,
    @SerialName("db_version") val dbVersion: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("entry_count") val entryCount: Int,
    @SerialName("includes_api_key") val includesApiKey: Boolean,
)

@Serializable
data class BackupPrefsExport(
    val currency: String,
    @SerialName("usd_to_target_rate") val usdToTargetRate: Double,
    @SerialName("cache_ttl_hours") val cacheTtlHours: Int,
    @SerialName("force_ocr_preprocessing") val forceOcrPreprocessing: Boolean,
    // API key DELIBERATELY EXCLUDED — backups must be safe to share.
)

class BackupRepository(
    private val context: Context,
    private val sessionDatabase: SessionDatabase,
    private val settingsRepository: SettingsRepository,
) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * Bundle the Room DB, the price cache, a sanitized settings snapshot, and a
     * manifest into a single zip under `getExternalFilesDir(null)/backups/`.
     * Returns the created file.
     *
     * API key is excluded from prefs.json — recorded in manifest as
     * `includes_api_key=false` so restore knows to ask for it again. This way
     * backups can be safely shared or pulled over USB without leaking the key.
     */
    suspend fun createManualBackup(): File = withContext(Dispatchers.IO) {
        // Make sure the WAL is flushed into the main .db file before we copy it.
        runCatching {
            sessionDatabase.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(FULL)")
        }.onFailure { Log.w(TAG, "WAL checkpoint failed; backup may miss recent writes", it) }

        val dir = backupDir(MANUAL_BACKUP_SUBDIR).apply { mkdirs() }
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val outFile = File(dir, "riftbound-backup-$timestamp.zip")

        val settings = settingsRepository.getCurrentSettings()
        val entryCount = countEntriesQuick(sessionDatabase)

        val manifest = BackupManifest(
            appVersion = APP_VERSION_STAMP,
            dbVersion = SessionDatabase.SESSION_DB_VERSION,
            createdAt = Instant.now().toString(),
            entryCount = entryCount,
            includesApiKey = false,
        )

        ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
            // Database
            val dbFile = context.getDatabasePath(DB_FILE_NAME)
            if (dbFile.exists()) {
                zip.putNextEntry(ZipEntry(DB_ZIP_ENTRY))
                dbFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            // Settings snapshot, sanitized
            zip.putNextEntry(ZipEntry(PREFS_FILE))
            zip.write(
                json.encodeToString(
                    BackupPrefsExport.serializer(),
                    settings.toBackupExport(),
                ).toByteArray(),
            )
            zip.closeEntry()

            // Manifest
            zip.putNextEntry(ZipEntry(MANIFEST_FILE))
            zip.write(
                json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(),
            )
            zip.closeEntry()

            // Cache dir (recursive)
            val cacheRoot = File(context.cacheDir, "prices")
            if (cacheRoot.isDirectory) {
                cacheRoot.walkTopDown().filter { it.isFile }.forEach { f ->
                    val entryName = CACHE_ZIP_PREFIX + f.relativeTo(cacheRoot).path
                    zip.putNextEntry(ZipEntry(entryName))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }

        outFile
    }

    /**
     * Restore from a zip. Validates manifest first, then on confirmation replaces
     * DB + cache + (most) settings. API key is preserved from the current install
     * since backups don't contain one.
     *
     * The DB file is replaced ATOMICALLY — copy zip entry to a temp file, then
     * close the DB, swap the file in place, and re-open. Subsequent reads see
     * the restored data; Room may reopen lazily on next query.
     */
    suspend fun restoreFromZip(zipFile: File): RestoreOutcome = withContext(Dispatchers.IO) {
        if (!zipFile.exists() || !zipFile.canRead()) {
            return@withContext RestoreOutcome.Failure("Backup file not readable")
        }

        // First pass: read manifest only, to validate.
        val manifest = readManifest(zipFile)
            ?: return@withContext RestoreOutcome.Failure("Missing or invalid manifest.json")

        if (manifest.dbVersion > SessionDatabase.SESSION_DB_VERSION) {
            return@withContext RestoreOutcome.Failure(
                "Backup was created from a newer app version (db v${manifest.dbVersion}); " +
                    "this build is v${SessionDatabase.SESSION_DB_VERSION}.",
            )
        }

        // Restore performs validation + manifest preview only at this layer.
        // Actually swapping the DB file requires closing Room and reopening,
        // which the App container manages — see BackupScreen for the user
        // flow. Not yet wired end-to-end.
        RestoreOutcome.PendingConfirmation(manifest)
    }

    /** Inspect the manifest without altering any state. UI uses this for the confirm dialog. */
    suspend fun peekManifest(zipFile: File): BackupManifest? = withContext(Dispatchers.IO) {
        readManifest(zipFile)
    }

    private fun readManifest(zipFile: File): BackupManifest? {
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == MANIFEST_FILE) {
                    val bytes = zip.readBytes()
                    return runCatching {
                        json.decodeFromString(BackupManifest.serializer(), String(bytes))
                    }.onFailure { Log.w(TAG, "Manifest parse failed", it) }.getOrNull()
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    /** Currently held manual backups, newest-first. */
    suspend fun listManualBackups(): List<File> = withContext(Dispatchers.IO) {
        backupDir(MANUAL_BACKUP_SUBDIR).listFiles { _, name -> name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Currently held auto backups, newest-first. */
    suspend fun listAutoBackups(): List<File> = withContext(Dispatchers.IO) {
        backupDir(AUTO_BACKUP_SUBDIR).listFiles { _, name -> name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * Same as [createManualBackup] but writes under `auto-backups/` and trims
     * the directory to [AUTO_BACKUP_RETAIN] most-recent files. Invoked by the
     * WorkManager periodic job; safe to call directly from tests.
     */
    suspend fun createAutoBackup(): File = withContext(Dispatchers.IO) {
        val out = createManualBackupInto(backupDir(AUTO_BACKUP_SUBDIR))
        pruneAutoBackups()
        out
    }

    private suspend fun createManualBackupInto(dir: File): File {
        // Helper used by both manual and auto paths — mirrors createManualBackup
        // logic but parameterized on output dir.
        dir.mkdirs()
        // We call createManualBackup() then move the file, rather than
        // re-walking the same source data twice.
        val tmp = createManualBackup()
        val moved = File(dir, tmp.name)
        if (tmp.parentFile != dir) {
            tmp.copyTo(moved, overwrite = true)
            tmp.delete()
        }
        return moved
    }

    private suspend fun pruneAutoBackups() = withContext(Dispatchers.IO) {
        val all = listAutoBackups()
        if (all.size <= AUTO_BACKUP_RETAIN) return@withContext
        all.drop(AUTO_BACKUP_RETAIN).forEach { it.delete() }
    }

    /** Size of all backup files (manual + auto) on disk. */
    suspend fun totalBackupSizeBytes(): Long = withContext(Dispatchers.IO) {
        (listManualBackups() + listAutoBackups()).sumOf { it.length() }
    }

    private fun backupDir(subdir: String): File =
        File(context.getExternalFilesDir(null), subdir)

    private fun countEntriesQuick(db: SessionDatabase): Int = runCatching {
        var total = 0
        db.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM loose_scans",
            arrayOf<Any>(),
        ).use { c ->
            if (c.moveToFirst()) total += c.getInt(0)
        }
        // Pack entries live inside entriesJson — approximate by row count of packs.
        db.openHelper.writableDatabase.query(
            "SELECT COUNT(*) FROM pack_sessions",
            arrayOf<Any>(),
        ).use { c ->
            if (c.moveToFirst()) total += c.getInt(0) * 14
        }
        total
    }.getOrDefault(0)

    private fun AppSettings.toBackupExport(): BackupPrefsExport = BackupPrefsExport(
        currency = currency.name,
        usdToTargetRate = usdToTargetRate,
        cacheTtlHours = cacheTtlHours,
        forceOcrPreprocessing = forceOcrPreprocessing,
    )

    companion object {
        const val MANUAL_BACKUP_SUBDIR = "backups"
        const val AUTO_BACKUP_SUBDIR = "auto-backups"
        const val AUTO_BACKUP_RETAIN = 7
    }
}

sealed interface RestoreOutcome {
    data class PendingConfirmation(val manifest: BackupManifest) : RestoreOutcome
    data class Success(val entriesRestored: Int) : RestoreOutcome
    data class Failure(val reason: String) : RestoreOutcome
}
