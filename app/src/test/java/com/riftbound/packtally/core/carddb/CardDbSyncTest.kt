package com.riftbound.packtally.core.carddb

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.riftbound.packtally.core.persistence.CardDao
import com.riftbound.packtally.core.persistence.CardEntity
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CardDbSyncTest {

    @Test
    fun `list response accepts items data and cards envelopes`() {
        val item = dto(id = "item")
        val data = dto(id = "data")
        val cards = dto(id = "cards")

        assertEquals(listOf(item), RiftcodexListResponse(items = listOf(item)).cardsFromKnownEnvelope())
        assertEquals(listOf(data), RiftcodexListResponse(data = listOf(data)).cardsFromKnownEnvelope())
        assertEquals(listOf(cards), RiftcodexListResponse(cards = listOf(cards)).cardsFromKnownEnvelope())
    }

    @Test
    fun `empty sync refuses to wipe existing database`() = runBlocking {
        val dao = FakeCardDao(mutableListOf(entity(id = "kept")))
        val sync = CardDbSync(FakeSource(emptyList()), dao, freshDataStore())

        val failed = runCatching { sync.runFullSync() }.isFailure

        assertTrue(failed)
        assertEquals(listOf("kept"), dao.rows.map { it.id })
    }

    @Test
    fun `missing tcgplayer id is dropped but valid cards commit`() = runBlocking {
        val dao = FakeCardDao(mutableListOf(entity(id = "old")))
        val sync = CardDbSync(
            FakeSource(
                listOf(
                    dto(id = "valid", tcgplayerId = "123", riftboundId = "unl-060a-219"),
                    dto(id = "missing", tcgplayerId = null, riftboundId = "unl-061-219"),
                ),
            ),
            dao,
            freshDataStore(),
        )

        val count = sync.runFullSync()

        assertEquals(1, count)
        assertEquals(listOf("valid"), dao.rows.map { it.id })
        assertEquals("UNL-060a", dao.rows.single().collectorNumber)
        assertEquals("UNL", dao.rows.single().setCode)
        assertEquals("", dao.rows.single().domains)
    }

    @Test
    fun `domain classification is persisted for collection filters`() = runBlocking {
        val dao = FakeCardDao(mutableListOf())
        val sync = CardDbSync(
            FakeSource(
                listOf(
                    dto(id = "domain", tcgplayerId = "123", riftboundId = "unl-060-219").copy(
                        classification = RiftcodexClassification(
                            rarity = "rare",
                            domain = listOf("Fury", "Mind"),
                        ),
                    ),
                ),
            ),
            dao,
            freshDataStore(),
        )

        sync.runFullSync()

        assertEquals("Fury|Mind", dao.rows.single().domains)
    }

    @Test
    fun `type and attributes are persisted for the card browser filters`() = runBlocking {
        val dao = FakeCardDao(mutableListOf())
        val sync = CardDbSync(
            FakeSource(
                listOf(
                    dto(id = "stats", tcgplayerId = "123", riftboundId = "unl-060-219").copy(
                        classification = RiftcodexClassification(rarity = "rare", type = "Unit"),
                        attributes = RiftcodexAttributes(energy = 3, might = 5, power = 2),
                    ),
                ),
            ),
            dao,
            freshDataStore(),
        )

        sync.runFullSync()

        val row = dao.rows.single()
        assertEquals("unit", row.type)
        assertEquals(3, row.energy)
        assertEquals(5, row.might)
        assertEquals(2, row.power)
    }

    @Test
    fun `missing attributes leave nullable stat columns null`() = runBlocking {
        val dao = FakeCardDao(mutableListOf())
        val sync = CardDbSync(
            FakeSource(listOf(dto(id = "no-stats", tcgplayerId = "123", riftboundId = "unl-060-219"))),
            dao,
            freshDataStore(),
        )

        sync.runFullSync()

        val row = dao.rows.single()
        assertEquals("", row.type)
        assertEquals(null, row.energy)
        assertEquals(null, row.might)
        assertEquals(null, row.power)
    }

    @Test
    fun `network failure leaves existing database safe`() = runBlocking {
        val dao = FakeCardDao(mutableListOf(entity(id = "kept")))
        val sync = CardDbSync(FailingSource, dao, freshDataStore())

        val failed = runCatching { sync.runFullSync() }.isFailure

        assertTrue(failed)
        assertFalse(dao.deleted)
        assertEquals(listOf("kept"), dao.rows.map { it.id })
    }

    @TempDir
    lateinit var tempDir: Path

    private fun freshDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { tempDir.resolve("sync-${System.nanoTime()}.preferences_pb").toFile() },
        )

    private class FakeSource(private val cards: List<RiftcodexCardDto>) : RiftcodexCardSource {
        override suspend fun fetchAllCards(): List<RiftcodexCardDto> = cards
        override suspend fun lookupByRiftboundId(id: String): RiftcodexCardDto? = null
        override suspend fun lookupByTcgplayerId(tcgplayerId: String): RiftcodexCardDto? = null
    }

    private object FailingSource : RiftcodexCardSource {
        override suspend fun fetchAllCards(): List<RiftcodexCardDto> = error("network down")
        override suspend fun lookupByRiftboundId(id: String): RiftcodexCardDto? = null
        override suspend fun lookupByTcgplayerId(tcgplayerId: String): RiftcodexCardDto? = null
    }

    private class FakeCardDao(initial: MutableList<CardEntity>) : CardDao {
        val rows = initial
        var deleted = false
        override suspend fun getAll(): List<CardEntity> = rows.toList()
        override suspend fun count(): Int = rows.size
        override suspend fun lookupByCollectorNumber(collectorNumber: String): CardEntity? =
            rows.firstOrNull { it.collectorNumber == collectorNumber }
        override suspend fun lookupByTcgplayerId(tcgplayerId: String): CardEntity? =
            rows.firstOrNull { it.tcgplayerId == tcgplayerId }
        override suspend fun upsertAll(cards: List<CardEntity>) {
            rows += cards
        }
        override suspend fun deleteAll() {
            deleted = true
            rows.clear()
        }
    }

    private fun dto(
        id: String = "id",
        tcgplayerId: String? = "tcg-$id",
        riftboundId: String? = "ogn-001-298",
    ): RiftcodexCardDto = RiftcodexCardDto(
        id = id,
        name = "Card $id",
        riftboundId = riftboundId,
        tcgplayerId = tcgplayerId,
        classification = RiftcodexClassification(rarity = "Common"),
        set = RiftcodexSet(id = riftboundId?.substringBefore('-')?.uppercase()),
    )

    private fun entity(id: String): CardEntity = CardEntity(
        id = id,
        name = "Existing",
        collectorNumber = "OGN-001",
        setCode = "OGN",
        rarity = "common",
        isFoilByDefault = false,
        hasSignatureVariant = false,
        tcgplayerId = "tcg-$id",
    )
}
