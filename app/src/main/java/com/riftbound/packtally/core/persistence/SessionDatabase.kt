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
    ],
    version = 2,
    exportSchema = false,
)
abstract class SessionDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun looseScanDao(): LooseScanDao

    companion object {
        /** Surfaced for BackupRepository so the manifest can record compatibility. */
        const val SESSION_DB_VERSION: Int = 2

        // CHOICE: provide a real v1 → v2 migration that adds the loose_scans table
        // (rather than falling back to destructive migration on update), so an
        // in-progress box survives the app upgrade that introduced Quick Scan.
        // fallbackToDestructiveMigration still acts as a safety net for v0→v2 or
        // any unexpected version skew.
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

        fun create(context: Context): SessionDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SessionDatabase::class.java,
                "session.db",
            )
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
    }
}
