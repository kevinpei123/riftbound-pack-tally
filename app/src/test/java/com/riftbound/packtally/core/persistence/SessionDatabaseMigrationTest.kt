package com.riftbound.packtally.core.persistence

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises MIGRATION_4_5's real SQL against a real (Robolectric-shadowed)
 * SQLite database, rather than Room's MigrationTestHelper — this project
 * doesn't export historical schema JSON (`exportSchema = false`), so there's
 * no bundled v4 schema for MigrationTestHelper to build from. Hand-rolling
 * the pre-migration `cards` table here matches MIGRATION_3_4's actual DDL.
 */
@RunWith(RobolectricTestRunner::class)
class SessionDatabaseMigrationTest {

    @Test
    fun `MIGRATION_4_5 adds nullable filter columns and preserves existing rows`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null) // in-memory
            .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE cards (
                            id TEXT PRIMARY KEY NOT NULL,
                            name TEXT NOT NULL,
                            collectorNumber TEXT NOT NULL,
                            setCode TEXT NOT NULL,
                            rarity TEXT NOT NULL,
                            isFoilByDefault INTEGER NOT NULL,
                            hasSignatureVariant INTEGER NOT NULL,
                            tcgplayerId TEXT NOT NULL,
                            hasAlternateArt INTEGER NOT NULL DEFAULT 0,
                            imageUrl TEXT,
                            domains TEXT NOT NULL DEFAULT ''
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT INTO cards(
                            id, name, collectorNumber, setCode, rarity,
                            isFoilByDefault, hasSignatureVariant, tcgplayerId,
                            hasAlternateArt, imageUrl, domains
                        ) VALUES(
                            'ogn-001-298', 'Annie, Fiery', 'OGN-001', 'OGN', 'common',
                            0, 0, 'tcg-1', 0, NULL, 'Fury'
                        )
                        """.trimIndent(),
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()

        val helper = FrameworkSQLiteOpenHelperFactory().create(configuration)
        val db = helper.writableDatabase // runs onCreate, landing at the pre-migration v4 shape

        SessionDatabase.MIGRATION_4_5.migrate(db)

        val cursor = db.query(
            "SELECT id, name, domains, type, energy, might, power FROM cards WHERE id = 'ogn-001-298'",
        )
        cursor.use {
            assertEquals(1, it.count)
            it.moveToFirst()
            assertEquals("ogn-001-298", it.getString(0))
            assertEquals("Annie, Fiery", it.getString(1))
            assertEquals("Fury", it.getString(2))
            assertEquals("", it.getString(3))
            assertTrue(it.isNull(4))
            assertTrue(it.isNull(5))
            assertTrue(it.isNull(6))
        }
        db.close()
    }
}
