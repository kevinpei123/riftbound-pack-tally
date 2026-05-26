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
