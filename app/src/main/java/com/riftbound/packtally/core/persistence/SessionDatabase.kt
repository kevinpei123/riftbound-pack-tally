package com.riftbound.packtally.core.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BoxSessionEntity::class,
        PackSessionEntity::class,
        LooseScanEntity::class,
        CardEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class SessionDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun looseScanDao(): LooseScanDao
    abstract fun cardDao(): CardDao

    companion object {
        const val SESSION_DB_VERSION: Int = 3

        // v1 → v2 introduced loose_scans.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS loose_scans (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        cardId TEXT NOT NULL,
                        variant TEXT NOT NULL,
                        priceJson TEXT NOT NULL,
                        scannedAt INTEGER NOT NULL,
                        notes TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_loose_scans_cardId ON loose_scans(cardId)",
                )
            }
        }

        // v2 → v3 adds the Riftcodex-sourced `cards` table and a nullable
        // `tcgplayerId` column to loose_scans. Pack entries live in JSON so
        // they don't need a column — they're backfilled in place by BackfillJob.
        // Real migration, not destructive — the user may be mid-box.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS cards (
                        id TEXT PRIMARY KEY NOT NULL,
                        name TEXT NOT NULL,
                        collectorNumber TEXT NOT NULL,
                        setCode TEXT NOT NULL,
                        rarity TEXT NOT NULL,
                        isFoilByDefault INTEGER NOT NULL,
                        hasSignatureVariant INTEGER NOT NULL,
                        tcgplayerId TEXT NOT NULL,
                        hasAlternateArt INTEGER NOT NULL DEFAULT 0,
                        imageUrl TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cards_collectorNumber ON cards(collectorNumber)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cards_tcgplayerId ON cards(tcgplayerId)",
                )

                // Add tcgplayerId column to loose_scans (nullable; BackfillJob populates it).
                db.execSQL(
                    "ALTER TABLE loose_scans ADD COLUMN tcgplayerId TEXT",
                )
            }
        }

        fun create(context: Context): SessionDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SessionDatabase::class.java,
                "session.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                // No fallbackToDestructiveMigration: an unexpected schema bump
                // shouldn't lose a user mid-box. Future bumps require a real
                // Migration.
                .build()
    }
}
