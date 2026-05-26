package com.riftbound.packtally.core.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

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
}
