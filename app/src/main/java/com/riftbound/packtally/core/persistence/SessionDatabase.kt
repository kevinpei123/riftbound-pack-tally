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
        ScanSessionEntity::class,
        ScanSessionEntryEntity::class,
        CardEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class SessionDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun looseScanDao(): LooseScanDao
    abstract fun cardDao(): CardDao

    companion object {
        const val SESSION_DB_VERSION: Int = 5

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

        // v3 -> v4 introduces scan-session lists. Legacy Pack/Box/loose tables
        // remain in place for recovery and backup; loose scans are copied into
        // one completed session so the new Collection can aggregate them.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scan_sessions (
                        id TEXT PRIMARY KEY NOT NULL,
                        createdAt INTEGER NOT NULL,
                        completedAt INTEGER,
                        name TEXT,
                        status TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scan_session_entries (
                        id TEXT PRIMARY KEY NOT NULL,
                        sessionId TEXT NOT NULL,
                        cardId TEXT NOT NULL,
                        tcgplayerId TEXT,
                        variant TEXT NOT NULL,
                        priceJson TEXT NOT NULL,
                        pricingStatus TEXT NOT NULL,
                        pricingError TEXT,
                        scannedAt INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        manuallyCorrected INTEGER NOT NULL,
                        notes TEXT,
                        FOREIGN KEY(sessionId) REFERENCES scan_sessions(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scan_session_entries_sessionId ON scan_session_entries(sessionId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scan_session_entries_cardId ON scan_session_entries(cardId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scan_session_entries_tcgplayerId ON scan_session_entries(tcgplayerId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scan_session_entries_pricingStatus ON scan_session_entries(pricingStatus)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_scan_session_entries_scannedAt ON scan_session_entries(scannedAt)",
                )
                db.execSQL("ALTER TABLE cards ADD COLUMN domains TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO scan_sessions(id, createdAt, completedAt, name, status)
                    SELECT
                        'legacy-loose',
                        MIN(scannedAt),
                        MAX(scannedAt),
                        'Migrated loose scans',
                        'COMPLETED'
                    FROM loose_scans
                    HAVING COUNT(*) > 0
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO scan_session_entries(
                        id,
                        sessionId,
                        cardId,
                        tcgplayerId,
                        variant,
                        priceJson,
                        pricingStatus,
                        pricingError,
                        scannedAt,
                        source,
                        confidence,
                        manuallyCorrected,
                        notes
                    )
                    SELECT
                        'legacy-loose-' || id,
                        'legacy-loose',
                        cardId,
                        tcgplayerId,
                        variant,
                        COALESCE(priceJson, ''),
                        CASE
                            WHEN tcgplayerId IS NULL OR TRIM(tcgplayerId) = '' THEN 'UNPRICEABLE'
                            WHEN priceJson IS NOT NULL AND TRIM(priceJson) <> '' THEN 'PRICED'
                            ELSE 'PENDING'
                        END,
                        NULL,
                        scannedAt,
                        'MIGRATED_LOOSE',
                        1.0,
                        0,
                        notes
                    FROM loose_scans
                    """.trimIndent(),
                )
            }
        }

        // v4 -> v5 adds card-browser filter columns sourced from Riftcodex's
        // attributes/classification blocks (see CardDbSync). Existing rows get
        // the default/NULL below until the next full Riftcodex sync, which
        // already does a delete+upsert of every row — no backfill needed here.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN type TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cards ADD COLUMN energy INTEGER")
                db.execSQL("ALTER TABLE cards ADD COLUMN might INTEGER")
                db.execSQL("ALTER TABLE cards ADD COLUMN power INTEGER")
            }
        }

        fun create(context: Context): SessionDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SessionDatabase::class.java,
                "session.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // No fallbackToDestructiveMigration: an unexpected schema bump
                // shouldn't lose a user's scan list. Future bumps require a real
                // Migration.
                .build()
    }
}
