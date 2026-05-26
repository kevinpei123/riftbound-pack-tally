package com.riftbound.packtally.core.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BackfillJobTest {

    @Test
    fun `fills tcgplayerId from CardEntity by direct id match`(@TempDir tempDir: Path) = runBlocking {
        val cardDao = FakeCardDao(
            listOf(
                cardEntity("abc-1", "OGN-001", "1001"),
                cardEntity("abc-2", "OGN-002", "1002"),
                cardEntity("abc-3", "UNL-099", "2099"),
            ),
        )
        val looseDao = FakeLooseScanDao(
            mutableListOf(
                looseRow(id = 1, cardId = "abc-1"),
                looseRow(id = 2, cardId = "abc-2"),
                looseRow(id = 3, cardId = "abc-3"),
            ),
        )
        val store = freshDataStore(tempDir)
        val job = BackfillJob(looseDao, cardDao, store)

        val result = job.runIfNeeded()

        assertEquals(3, result.filled)
        assertEquals(0, result.skipped)
        assertEquals(false, result.alreadyRan)
        assertEquals("1001", looseDao.rows.first { it.id == 1L }.tcgplayerId)
        assertEquals("1002", looseDao.rows.first { it.id == 2L }.tcgplayerId)
        assertEquals("2099", looseDao.rows.first { it.id == 3L }.tcgplayerId)
    }

    @Test
    fun `fills tcgplayerId from legacy set-NNN-TOT cardId format`(@TempDir tempDir: Path) = runBlocking {
        // Old scraper format: "ogn-011-298" — collector number 11, padded to 011.
        val cardDao = FakeCardDao(
            listOf(cardEntity("riftcodex-id-xyz", "OGN-011", "9999")),
        )
        val looseDao = FakeLooseScanDao(
            mutableListOf(looseRow(id = 7, cardId = "ogn-011-298")),
        )
        val store = freshDataStore(tempDir)
        val job = BackfillJob(looseDao, cardDao, store)

        val result = job.runIfNeeded()

        assertEquals(1, result.filled)
        assertEquals("9999", looseDao.rows.first().tcgplayerId)
    }

    @Test
    fun `leaves unresolvable rows null and counts them as skipped`(@TempDir tempDir: Path) = runBlocking {
        val cardDao = FakeCardDao(listOf(cardEntity("kept", "OGN-001", "1001")))
        val looseDao = FakeLooseScanDao(
            mutableListOf(
                looseRow(id = 1, cardId = "abc-totally-unknown"),
                looseRow(id = 2, cardId = "kept"),
            ),
        )
        val store = freshDataStore(tempDir)
        val job = BackfillJob(looseDao, cardDao, store)

        val result = job.runIfNeeded()

        assertEquals(1, result.filled)
        assertEquals(1, result.skipped)
        assertNull(looseDao.rows.first { it.id == 1L }.tcgplayerId)
        assertEquals("1001", looseDao.rows.first { it.id == 2L }.tcgplayerId)
    }

    @Test
    fun `second run is a no-op after first marks completion`(@TempDir tempDir: Path) = runBlocking {
        val cardDao = FakeCardDao(listOf(cardEntity("a", "OGN-001", "1001")))
        val looseDao = FakeLooseScanDao(mutableListOf(looseRow(id = 1, cardId = "a")))
        val store = freshDataStore(tempDir)
        val job = BackfillJob(looseDao, cardDao, store)

        job.runIfNeeded()
        // Imagine a new row got added between the first run and the second; the
        // second run should NOT touch it.
        looseDao.rows.add(looseRow(id = 2, cardId = "a"))

        val secondResult = job.runIfNeeded()

        assertTrue(secondResult.alreadyRan)
        assertEquals(0, secondResult.filled)
        assertEquals(0, secondResult.skipped)
        assertNull(
            looseDao.rows.first { it.id == 2L }.tcgplayerId,
            "Second run should NOT have backfilled the new row",
        )
    }

    @Test
    fun `ignores rows that already have tcgplayerId`(@TempDir tempDir: Path) = runBlocking {
        val cardDao = FakeCardDao(listOf(cardEntity("a", "OGN-001", "1001")))
        val looseDao = FakeLooseScanDao(
            mutableListOf(
                looseRow(id = 1, cardId = "a", tcgplayerId = "pre-existing"),
                looseRow(id = 2, cardId = "a"),
            ),
        )
        val store = freshDataStore(tempDir)
        val job = BackfillJob(looseDao, cardDao, store)

        val result = job.runIfNeeded()

        assertEquals(1, result.filled)
        assertEquals("pre-existing", looseDao.rows.first { it.id == 1L }.tcgplayerId)
        assertEquals("1001", looseDao.rows.first { it.id == 2L }.tcgplayerId)
    }

    // ---- Helpers ----

    private fun cardEntity(id: String, collectorNumber: String, tcgplayerId: String) =
        CardEntity(
            id = id,
            name = "Card $collectorNumber",
            collectorNumber = collectorNumber,
            setCode = collectorNumber.substringBefore('-', "UNK"),
            rarity = "common",
            isFoilByDefault = false,
            hasSignatureVariant = false,
            tcgplayerId = tcgplayerId,
            hasAlternateArt = false,
            imageUrl = null,
        )

    private fun looseRow(id: Long, cardId: String, tcgplayerId: String? = null) =
        LooseScanEntity(
            id = id,
            cardId = cardId,
            variant = "STANDARD",
            priceJson = "{}",
            scannedAt = 0L,
            notes = null,
            tcgplayerId = tcgplayerId,
        )

    private fun freshDataStore(tempDir: Path): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { tempDir.resolve("test-${System.nanoTime()}.preferences_pb").toFile() },
        )
}

// --- Fakes ---

private class FakeCardDao(initial: List<CardEntity>) : CardDao {
    private val rows = initial.toMutableList()
    override suspend fun getAll(): List<CardEntity> = rows.toList()
    override suspend fun count(): Int = rows.size
    override suspend fun lookupByCollectorNumber(collectorNumber: String): CardEntity? =
        rows.firstOrNull { it.collectorNumber == collectorNumber }
    override suspend fun lookupByTcgplayerId(tcgplayerId: String): CardEntity? =
        rows.firstOrNull { it.tcgplayerId == tcgplayerId }
    override suspend fun upsertAll(cards: List<CardEntity>) {
        for (c in cards) {
            val idx = rows.indexOfFirst { it.id == c.id }
            if (idx >= 0) rows[idx] = c else rows.add(c)
        }
    }
    override suspend fun deleteAll() { rows.clear() }
}

private class FakeLooseScanDao(val rows: MutableList<LooseScanEntity>) : LooseScanDao {
    override fun observeAll(): Flow<List<LooseScanEntity>> = MutableSharedFlow()
    override suspend fun getAll(): List<LooseScanEntity> = rows.toList()
    override suspend fun getById(id: Long): LooseScanEntity? = rows.firstOrNull { it.id == id }
    override suspend fun insert(entity: LooseScanEntity): Long {
        rows.add(entity); return entity.id
    }
    override suspend fun update(entity: LooseScanEntity) {
        val idx = rows.indexOfFirst { it.id == entity.id }
        if (idx >= 0) rows[idx] = entity
    }
    override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
    override suspend fun delete(entity: LooseScanEntity) { rows.remove(entity) }
}
