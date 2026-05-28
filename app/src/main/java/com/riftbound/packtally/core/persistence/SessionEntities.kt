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

@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val completedAt: Long? = null,
    val name: String? = null,
    val status: String,
)

@Entity(
    tableName = "scan_session_entries",
    foreignKeys = [
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index("cardId"),
        Index("tcgplayerId"),
        Index("pricingStatus"),
        Index("scannedAt"),
    ],
)
data class ScanSessionEntryEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val cardId: String,
    val tcgplayerId: String?,
    val variant: String,
    val priceJson: String = "",
    val pricingStatus: String,
    val pricingError: String? = null,
    val scannedAt: Long,
    val source: String,
    val confidence: Float = 1.0f,
    val manuallyCorrected: Boolean = false,
    val notes: String? = null,
)
