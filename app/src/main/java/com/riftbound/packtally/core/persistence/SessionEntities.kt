package com.riftbound.packtally.core.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "box_sessions")
data class BoxSessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val mode: String,
)

@Entity(
    tableName = "pack_sessions",
    foreignKeys = [
        ForeignKey(
            entity = BoxSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["boxId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("boxId")],
)
data class PackSessionEntity(
    @PrimaryKey val id: String,
    val boxId: String,
    val position: Int,
    val startedAt: Long,
    val entriesJson: String,
)

/**
 * One scan recorded outside any pack/box session — used by Quick Scan for cards
 * a friend hands you, single-card valuations, etc. Stored separately from
 * pack/box entries because they aggregate independently in Collection.
 */
@Entity(tableName = "loose_scans")
data class LooseScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(index = true) val cardId: String,
    val variant: String,
    val priceJson: String,
    val scannedAt: Long,
    val notes: String? = null,
    /**
     * JustTCG join key. Nullable for rows brought forward from v2 of the schema;
     * [BackfillJob] populates them at app start where possible.
     */
    val tcgplayerId: String? = null,
)
