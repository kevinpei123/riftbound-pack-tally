package com.riftbound.packtally.core.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM box_sessions ORDER BY startedAt DESC LIMIT 1")
    suspend fun mostRecentBox(): BoxSessionEntity?

    @Query("SELECT * FROM box_sessions ORDER BY startedAt DESC")
    suspend fun allBoxes(): List<BoxSessionEntity>

    @Query("SELECT * FROM pack_sessions WHERE boxId = :boxId ORDER BY position ASC")
    suspend fun packsForBox(boxId: String): List<PackSessionEntity>

    @Upsert
    suspend fun upsertBox(box: BoxSessionEntity)

    @Query("DELETE FROM pack_sessions WHERE boxId = :boxId")
    suspend fun deletePacksForBox(boxId: String)

    @Insert
    suspend fun insertPacks(packs: List<PackSessionEntity>)

    /** Atomic snapshot: upsert the box, then replace its packs in one transaction. */
    @Transaction
    suspend fun saveBoxWithPacks(box: BoxSessionEntity, packs: List<PackSessionEntity>) {
        upsertBox(box)
        deletePacksForBox(box.id)
        if (packs.isNotEmpty()) insertPacks(packs)
    }

    @Query("SELECT * FROM scan_sessions WHERE status = 'ACTIVE' ORDER BY createdAt DESC LIMIT 1")
    fun observeActiveScanSession(): Flow<ScanSessionEntity?>

    @Query("SELECT * FROM scan_sessions WHERE status = 'ACTIVE' ORDER BY createdAt DESC LIMIT 1")
    suspend fun activeScanSession(): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions WHERE id = :id LIMIT 1")
    suspend fun scanSessionById(id: String): ScanSessionEntity?

    @Query("SELECT * FROM scan_sessions ORDER BY createdAt DESC")
    fun observeScanSessions(): Flow<List<ScanSessionEntity>>

    @Query("SELECT * FROM scan_sessions ORDER BY createdAt DESC")
    suspend fun scanSessions(): List<ScanSessionEntity>

    @Upsert
    suspend fun upsertScanSession(session: ScanSessionEntity)

    @Query("UPDATE scan_sessions SET status = :status, completedAt = :completedAt WHERE id = :sessionId")
    suspend fun updateScanSessionStatus(sessionId: String, status: String, completedAt: Long?)

    @Query("UPDATE scan_sessions SET name = :name WHERE id = :sessionId")
    suspend fun renameScanSession(sessionId: String, name: String?)

    @Query("SELECT * FROM scan_session_entries WHERE sessionId = :sessionId ORDER BY scannedAt ASC")
    fun observeScanSessionEntries(sessionId: String): Flow<List<ScanSessionEntryEntity>>

    @Query("SELECT * FROM scan_session_entries ORDER BY scannedAt DESC")
    fun observeAllScanSessionEntries(): Flow<List<ScanSessionEntryEntity>>

    @Query("SELECT * FROM scan_session_entries WHERE sessionId = :sessionId ORDER BY scannedAt ASC")
    suspend fun scanSessionEntries(sessionId: String): List<ScanSessionEntryEntity>

    @Query("SELECT * FROM scan_session_entries ORDER BY scannedAt DESC")
    suspend fun allScanSessionEntries(): List<ScanSessionEntryEntity>

    @Query("SELECT * FROM scan_session_entries WHERE id = :id LIMIT 1")
    suspend fun scanSessionEntryById(id: String): ScanSessionEntryEntity?

    @Query(
        """
        SELECT * FROM scan_session_entries
        WHERE sessionId = :sessionId AND pricingStatus IN ('PENDING', 'FAILED')
        ORDER BY scannedAt ASC
        """,
    )
    suspend fun pendingEntriesForSession(sessionId: String): List<ScanSessionEntryEntity>

    @Query(
        """
        SELECT * FROM scan_session_entries
        WHERE pricingStatus IN ('PENDING', 'FAILED')
        ORDER BY scannedAt ASC
        """,
    )
    suspend fun allPendingEntries(): List<ScanSessionEntryEntity>

    @Query("SELECT * FROM scan_session_entries WHERE sessionId = :sessionId ORDER BY scannedAt DESC LIMIT 1")
    suspend fun latestEntryForSession(sessionId: String): ScanSessionEntryEntity?

    @Query(
        """
        SELECT * FROM scan_session_entries
        WHERE cardId = :cardId AND variant = :variantName
        ORDER BY scannedAt DESC
        LIMIT 1
        """,
    )
    suspend fun latestEntryForCardVariant(cardId: String, variantName: String): ScanSessionEntryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertScanSessionEntry(entry: ScanSessionEntryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertScanSessionEntries(entries: List<ScanSessionEntryEntity>)

    @Update
    suspend fun updateScanSessionEntry(entry: ScanSessionEntryEntity)

    @Query("DELETE FROM scan_session_entries WHERE id = :entryId")
    suspend fun deleteScanSessionEntryById(entryId: String)

    @Query("DELETE FROM scan_session_entries WHERE sessionId = :sessionId")
    suspend fun deleteEntriesForScanSession(sessionId: String)
}
