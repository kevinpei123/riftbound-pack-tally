package com.riftbound.packtally.core.persistence

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Riftcodex-sourced card catalogue. Each row is one card the user might scan.
 *
 * Indexed columns: [tcgplayerId] (the JustTCG join key, looked up on every
 * pricing call) and [collectorNumber] (the OCR target — `OGN-011` etc).
 */
@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(index = true) val collectorNumber: String,
    val setCode: String,
    val rarity: String,
    val isFoilByDefault: Boolean,
    val hasSignatureVariant: Boolean,
    @ColumnInfo(index = true) val tcgplayerId: String,
    val hasAlternateArt: Boolean = false,
    val imageUrl: String? = null,
)

@Dao
interface CardDao {

    @Query("SELECT * FROM cards")
    suspend fun getAll(): List<CardEntity>

    @Query("SELECT COUNT(*) FROM cards")
    suspend fun count(): Int

    @Query("SELECT * FROM cards WHERE collectorNumber = :collectorNumber LIMIT 1")
    suspend fun lookupByCollectorNumber(collectorNumber: String): CardEntity?

    @Query("SELECT * FROM cards WHERE tcgplayerId = :tcgplayerId LIMIT 1")
    suspend fun lookupByTcgplayerId(tcgplayerId: String): CardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(cards: List<CardEntity>)

    @Query("DELETE FROM cards")
    suspend fun deleteAll()
}
