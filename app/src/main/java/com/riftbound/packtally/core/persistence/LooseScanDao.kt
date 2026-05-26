package com.riftbound.packtally.core.persistence

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LooseScanDao {

    @Query("SELECT * FROM loose_scans ORDER BY scannedAt DESC")
    fun observeAll(): Flow<List<LooseScanEntity>>

    @Query("SELECT * FROM loose_scans ORDER BY scannedAt DESC")
    suspend fun getAll(): List<LooseScanEntity>

    @Query("SELECT * FROM loose_scans WHERE id = :id")
    suspend fun getById(id: Long): LooseScanEntity?

    @Insert
    suspend fun insert(entity: LooseScanEntity): Long

    @Update
    suspend fun update(entity: LooseScanEntity)

    @Query("DELETE FROM loose_scans WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Delete
    suspend fun delete(entity: LooseScanEntity)
}
