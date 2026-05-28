package com.riftbound.packtally.core.persistence

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.core.pricing.PriceRequest
import com.riftbound.packtally.core.pricing.PricingRepository
import com.riftbound.packtally.model.BoxSession
import com.riftbound.packtally.model.CardPrice
import com.riftbound.packtally.model.PackSession
import com.riftbound.packtally.model.PricingStatus
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.ScanEntrySource
import com.riftbound.packtally.model.ScanSession
import com.riftbound.packtally.model.ScanSessionEntry
import com.riftbound.packtally.model.ScanSessionStatus
import com.riftbound.packtally.model.ScannedEntry
import com.riftbound.packtally.model.Variant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

private const val TAG = "SessionRepository"

class SessionRepository(
    private val dao: SessionDao,
    private val dataStore: DataStore<Preferences>? = null,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val entryListSerializer = ListSerializer(ScannedEntry.serializer())

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeActiveSession(): Flow<ScanSession?> =
        dao.observeActiveScanSession().flatMapLatest { active ->
            if (active == null) {
                flowOf(null)
            } else {
                dao.observeScanSessionEntries(active.id).map { entries ->
                    active.toDomain(entries.mapNotNull { it.toDomainOrNull() })
                }
            }
        }

    fun observeAllEntries(): Flow<List<ScanSessionEntry>> =
        dao.observeAllScanSessionEntries().map { rows -> rows.mapNotNull { it.toDomainOrNull() } }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSessions(): Flow<List<ScanSession>> =
        dao.observeScanSessions().flatMapLatest { sessions ->
            dao.observeAllScanSessionEntries().map { entries ->
                val bySession = entries.mapNotNull { it.toDomainOrNull() }.groupBy { it.sessionId }
                sessions.map { it.toDomain(bySession[it.id].orEmpty()) }
            }
        }

    suspend fun loadActiveSession(): ScanSession? = withContext(Dispatchers.IO) {
        val active = dao.activeScanSession() ?: return@withContext null
        active.toDomain(dao.scanSessionEntries(active.id).mapNotNull { it.toDomainOrNull() })
    }

    suspend fun loadRecentSessions(): List<ScanSession> = withContext(Dispatchers.IO) {
        val entries = dao.allScanSessionEntries().mapNotNull { it.toDomainOrNull() }.groupBy { it.sessionId }
        dao.scanSessions().map { it.toDomain(entries[it.id].orEmpty()) }
    }

    suspend fun getOrCreateActiveSession(name: String? = null): ScanSession =
        withContext(Dispatchers.IO) {
            dao.activeScanSession()?.let { active ->
                return@withContext active.toDomain(
                    dao.scanSessionEntries(active.id).mapNotNull { it.toDomainOrNull() },
                )
            }
            createSession(name)
        }

    suspend fun startNewSession(name: String? = null): ScanSession = withContext(Dispatchers.IO) {
        dao.activeScanSession()?.let {
            dao.updateScanSessionStatus(it.id, ScanSessionStatus.COMPLETED.name, Instant.now().toEpochMilli())
        }
        createSession(name)
    }

    suspend fun addEntry(
        card: RiftboundCard,
        variant: Variant,
        source: ScanEntrySource = ScanEntrySource.OCR,
        confidence: Float = 1.0f,
        notes: String? = null,
    ): ScanSessionEntry = withContext(Dispatchers.IO) {
        val session = dao.activeScanSession() ?: createSession().toEntity()
        val entity = ScanSessionEntryEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            cardId = card.id,
            tcgplayerId = card.tcgplayerId.takeIf { it.isNotBlank() },
            variant = variant.name,
            priceJson = "",
            pricingStatus = if (card.tcgplayerId.isBlank()) {
                PricingStatus.UNPRICEABLE.name
            } else {
                PricingStatus.PENDING.name
            },
            pricingError = if (card.tcgplayerId.isBlank()) "Missing tcgplayer_id" else null,
            scannedAt = Instant.now().toEpochMilli(),
            source = source.name,
            confidence = confidence,
            manuallyCorrected = source == ScanEntrySource.MANUAL,
            notes = notes,
        )
        dao.insertScanSessionEntry(entity)
        entity.toDomain(card)
    }

    suspend fun undoLastScan(): Boolean = withContext(Dispatchers.IO) {
        val session = dao.activeScanSession() ?: return@withContext false
        val latest = dao.latestEntryForSession(session.id) ?: return@withContext false
        dao.deleteScanSessionEntryById(latest.id)
        true
    }

    suspend fun removeEntry(entryId: String): Boolean = withContext(Dispatchers.IO) {
        dao.scanSessionEntryById(entryId) ?: return@withContext false
        dao.deleteScanSessionEntryById(entryId)
        true
    }

    suspend fun removeLatestEntryByCardVariant(cardId: String, variant: Variant): Boolean =
        withContext(Dispatchers.IO) {
            val target = dao.latestEntryForCardVariant(cardId, variant.name) ?: return@withContext false
            dao.deleteScanSessionEntryById(target.id)
            true
        }

    suspend fun clearActiveSession(): Boolean = withContext(Dispatchers.IO) {
        val session = dao.activeScanSession() ?: return@withContext false
        dao.deleteEntriesForScanSession(session.id)
        true
    }

    suspend fun completeActiveSession(): Boolean = withContext(Dispatchers.IO) {
        val session = dao.activeScanSession() ?: return@withContext false
        dao.updateScanSessionStatus(
            session.id,
            ScanSessionStatus.COMPLETED.name,
            Instant.now().toEpochMilli(),
        )
        true
    }

    suspend fun renameSession(sessionId: String, name: String?) = withContext(Dispatchers.IO) {
        dao.renameScanSession(sessionId, name?.takeIf { it.isNotBlank() })
    }

    suspend fun replaceEntry(
        entryId: String,
        newCard: RiftboundCard,
        newVariant: Variant,
    ): Boolean = withContext(Dispatchers.IO) {
        val existing = dao.scanSessionEntryById(entryId) ?: return@withContext false
        dao.updateScanSessionEntry(
            existing.copy(
                cardId = newCard.id,
                tcgplayerId = newCard.tcgplayerId.takeIf { it.isNotBlank() },
                variant = newVariant.name,
                priceJson = "",
                pricingStatus = if (newCard.tcgplayerId.isBlank()) {
                    PricingStatus.UNPRICEABLE.name
                } else {
                    PricingStatus.PENDING.name
                },
                pricingError = if (newCard.tcgplayerId.isBlank()) "Missing tcgplayer_id" else null,
                manuallyCorrected = true,
            ),
        )
        true
    }

    suspend fun changeVariant(entryId: String, newVariant: Variant): Boolean = withContext(Dispatchers.IO) {
        val existing = dao.scanSessionEntryById(entryId) ?: return@withContext false
        dao.updateScanSessionEntry(
            existing.copy(
                variant = newVariant.name,
                priceJson = "",
                pricingStatus = if (existing.tcgplayerId.isNullOrBlank()) {
                    PricingStatus.UNPRICEABLE.name
                } else {
                    PricingStatus.PENDING.name
                },
                pricingError = if (existing.tcgplayerId.isNullOrBlank()) "Missing tcgplayer_id" else null,
                manuallyCorrected = true,
            ),
        )
        true
    }

    suspend fun submitPendingPrices(
        pricing: PricingRepository,
        sessionId: String? = null,
        onProgress: suspend (BatchProgress) -> Unit = {},
    ): SubmitResult = withContext(Dispatchers.IO) {
        val pending = if (sessionId == null) dao.allPendingEntries() else dao.pendingEntriesForSession(sessionId)
        if (pending.isEmpty()) return@withContext SubmitResult.Empty

        val (unpriceable, priceable) = pending.partition { it.tcgplayerId.isNullOrBlank() }
        unpriceable.forEach {
            dao.updateScanSessionEntry(
                it.copy(
                    pricingStatus = PricingStatus.UNPRICEABLE.name,
                    pricingError = "Missing tcgplayer_id",
                ),
            )
        }

        val chunks = priceable.chunked(MAX_PRICING_BATCH)
        var priced = 0
        var failed = 0
        var totalValue = 0.0

        chunks.forEachIndexed { index, chunk ->
            onProgress(BatchProgress(currentBatch = index + 1, totalBatches = chunks.size))
            val requestsByRow = chunk.associateWith { row ->
                PriceRequest(
                    tcgplayerId = row.tcgplayerId.orEmpty(),
                    variant = row.variant.toVariant(),
                )
            }
            val results = runCatching { pricing.priceMany(requestsByRow.values.toList()) }
                .getOrElse { exc ->
                    chunk.forEach { row ->
                        dao.updateScanSessionEntry(row.markFailed(exc.message ?: "Pricing request failed"))
                    }
                    failed += chunk.size
                    return@forEachIndexed
                }
            requestsByRow.forEach { (row, request) ->
                val result = results[request]
                if (result == null) {
                    dao.updateScanSessionEntry(row.markFailed("No price returned"))
                    failed += 1
                    return@forEach
                }
                result.fold(
                    onSuccess = { price ->
                        dao.updateScanSessionEntry(
                            row.copy(
                                priceJson = json.encodeToString(CardPrice.serializer(), price),
                                pricingStatus = PricingStatus.PRICED.name,
                                pricingError = null,
                            ),
                        )
                        priced += 1
                        totalValue += price.marketPrice
                    },
                    onFailure = { exc ->
                        dao.updateScanSessionEntry(row.markFailed(exc.message ?: "Pricing failed"))
                        failed += 1
                    },
                )
            }
        }

        SubmitResult.Done(
            priced = priced,
            failed = failed,
            unpriceable = unpriceable.size,
            totalValue = totalValue,
            batches = chunks.size,
        )
    }

    suspend fun migrateLegacyPacksIfNeeded(): LegacyMigrationResult = withContext(Dispatchers.IO) {
        val store = dataStore ?: return@withContext LegacyMigrationResult(alreadyRan = true)
        if (store.data.first()[KEY_LEGACY_PACK_MIGRATION_DONE] == true) {
            return@withContext LegacyMigrationResult(alreadyRan = true)
        }

        var sessionsCreated = 0
        var entriesCreated = 0
        var failedPacks = 0

        dao.allBoxes().forEach { box ->
            val packs = dao.packsForBox(box.id)
            val migratedEntries = mutableListOf<ScanSessionEntryEntity>()
            packs.forEach { pack ->
                val entries: List<ScannedEntry> = runCatching {
                    json.decodeFromString(entryListSerializer, pack.entriesJson)
                }.getOrElse { exc ->
                    Log.e(TAG, "Failed to migrate legacy pack ${pack.id}", exc)
                    failedPacks += 1
                    emptyList()
                }
                entries.forEach { entry ->
                    migratedEntries += entry.toMigratedEntity(
                        sessionId = "legacy-box-${box.id}",
                        rowId = "legacy-pack-${pack.id}-${entry.id}",
                    )
                }
            }
            if (migratedEntries.isNotEmpty()) {
                val session = ScanSessionEntity(
                    id = "legacy-box-${box.id}",
                    createdAt = box.startedAt,
                    completedAt = migratedEntries.maxOfOrNull { it.scannedAt } ?: box.startedAt,
                    name = "Migrated ${box.mode.lowercase()}",
                    status = ScanSessionStatus.COMPLETED.name,
                )
                dao.upsertScanSession(session)
                dao.insertScanSessionEntries(migratedEntries)
                sessionsCreated += 1
                entriesCreated += migratedEntries.size
            }
        }

        store.edit { it[KEY_LEGACY_PACK_MIGRATION_DONE] = true }
        LegacyMigrationResult(
            sessionsCreated = sessionsCreated,
            entriesCreated = entriesCreated,
            failedPacks = failedPacks,
        )
    }

    // Legacy Pack/Box API kept only so old data can still be read and migrated.
    suspend fun loadMostRecentBox(): BoxSession? = withContext(Dispatchers.IO) {
        val boxEntity = dao.mostRecentBox() ?: return@withContext null
        hydrateLegacyBox(boxEntity)
    }

    suspend fun loadAllBoxes(): List<BoxSession> = withContext(Dispatchers.IO) {
        dao.allBoxes().map { hydrateLegacyBox(it) }
    }

    suspend fun save(box: BoxSession) = withContext(Dispatchers.IO) {
        val boxEntity = BoxSessionEntity(
            id = box.id,
            startedAt = box.startedAt.toEpochMilli(),
            mode = box.mode.name,
        )
        val packEntities = box.packs.value.mapIndexed { index, pack ->
            PackSessionEntity(
                id = pack.id,
                boxId = box.id,
                position = index,
                startedAt = pack.startedAt.toEpochMilli(),
                entriesJson = json.encodeToString(entryListSerializer, pack.entries.value),
            )
        }
        dao.saveBoxWithPacks(boxEntity, packEntities)
    }

    suspend fun removeOneByCardVariant(cardId: String, variantName: String): Boolean =
        removeLatestEntryByCardVariant(cardId, variantName.toVariant())

    private suspend fun createSession(name: String? = null): ScanSession {
        val entity = ScanSessionEntity(
            id = UUID.randomUUID().toString(),
            createdAt = Instant.now().toEpochMilli(),
            completedAt = null,
            name = name?.takeIf { it.isNotBlank() },
            status = ScanSessionStatus.ACTIVE.name,
        )
        dao.upsertScanSession(entity)
        return entity.toDomain(emptyList())
    }

    private suspend fun hydrateLegacyBox(boxEntity: BoxSessionEntity): BoxSession {
        val mode = runCatching { BoxSession.Mode.valueOf(boxEntity.mode) }
            .getOrDefault(BoxSession.Mode.BOX)

        val packEntities = dao.packsForBox(boxEntity.id)
        val packs = packEntities.map { packEntity ->
            val entries: List<ScannedEntry> = runCatching {
                json.decodeFromString(entryListSerializer, packEntity.entriesJson)
            }.getOrElse { exc ->
                Log.e(TAG, "Failed to parse entries for pack ${packEntity.id}", exc)
                emptyList()
            }
            PackSession(
                id = packEntity.id,
                startedAt = Instant.ofEpochMilli(packEntity.startedAt),
                initialEntries = entries,
            )
        }

        return BoxSession(
            id = boxEntity.id,
            startedAt = Instant.ofEpochMilli(boxEntity.startedAt),
            mode = mode,
            initialPacks = packs,
        )
    }

    private fun ScanSessionEntity.toDomain(entries: List<ScanSessionEntry>): ScanSession =
        ScanSession(
            id = id,
            createdAt = Instant.ofEpochMilli(createdAt),
            completedAt = completedAt?.let(Instant::ofEpochMilli),
            name = name,
            status = status.toSessionStatus(),
            entries = entries,
        )

    private fun ScanSession.toEntity(): ScanSessionEntity =
        ScanSessionEntity(
            id = id,
            createdAt = createdAt.toEpochMilli(),
            completedAt = completedAt?.toEpochMilli(),
            name = name,
            status = status.name,
        )

    private fun ScanSessionEntryEntity.toDomainOrNull(): ScanSessionEntry? {
        val card = CardDatabase.lookupByNumber(cardId)
            ?: tcgplayerId?.let { CardDatabase.lookupByTcgplayerId(it) }
            ?: run {
                Log.w(TAG, "Scan entry $id references unknown card $cardId")
                return null
            }
        return toDomain(card)
    }

    private fun ScanSessionEntryEntity.toDomain(card: RiftboundCard): ScanSessionEntry =
        ScanSessionEntry(
            id = id,
            sessionId = sessionId,
            card = card,
            variant = variant.toVariant(),
            price = priceJson.takeIf { it.isNotBlank() }?.let { raw ->
                runCatching { json.decodeFromString<CardPrice>(raw) }
                    .onFailure { Log.w(TAG, "Failed to parse price for scan entry $id", it) }
                    .getOrNull()
            },
            pricingStatus = pricingStatus.toPricingStatus(),
            pricingError = pricingError,
            scannedAt = Instant.ofEpochMilli(scannedAt),
            source = source.toScanSource(),
            confidence = confidence,
            manuallyCorrected = manuallyCorrected,
            notes = notes,
        )

    private fun ScannedEntry.toMigratedEntity(sessionId: String, rowId: String): ScanSessionEntryEntity =
        ScanSessionEntryEntity(
            id = rowId,
            sessionId = sessionId,
            cardId = card.id,
            tcgplayerId = card.tcgplayerId.takeIf { it.isNotBlank() },
            variant = variant.name,
            priceJson = price?.let { json.encodeToString(CardPrice.serializer(), it) }.orEmpty(),
            pricingStatus = when {
                card.tcgplayerId.isBlank() -> PricingStatus.UNPRICEABLE.name
                price != null -> PricingStatus.PRICED.name
                else -> PricingStatus.PENDING.name
            },
            pricingError = if (card.tcgplayerId.isBlank()) "Missing tcgplayer_id" else null,
            scannedAt = scannedAt.toEpochMilli(),
            source = ScanEntrySource.MIGRATED_PACK.name,
            confidence = confidence,
            manuallyCorrected = false,
        )

    private fun ScanSessionEntryEntity.markFailed(reason: String): ScanSessionEntryEntity =
        copy(pricingStatus = PricingStatus.FAILED.name, pricingError = reason)

    private fun String.toVariant(): Variant =
        runCatching { Variant.valueOf(this) }.getOrDefault(Variant.STANDARD)

    private fun String.toSessionStatus(): ScanSessionStatus =
        runCatching { ScanSessionStatus.valueOf(this) }.getOrDefault(ScanSessionStatus.ACTIVE)

    private fun String.toPricingStatus(): PricingStatus =
        runCatching { PricingStatus.valueOf(this) }.getOrDefault(PricingStatus.PENDING)

    private fun String.toScanSource(): ScanEntrySource =
        runCatching { ScanEntrySource.valueOf(this) }.getOrDefault(ScanEntrySource.OCR)

    sealed interface SubmitResult {
        data object Empty : SubmitResult
        data class Done(
            val priced: Int,
            val failed: Int,
            val unpriceable: Int,
            val totalValue: Double,
            val batches: Int,
        ) : SubmitResult
    }

    data class BatchProgress(
        val currentBatch: Int,
        val totalBatches: Int,
    )

    data class LegacyMigrationResult(
        val sessionsCreated: Int = 0,
        val entriesCreated: Int = 0,
        val failedPacks: Int = 0,
        val alreadyRan: Boolean = false,
    )

    companion object {
        const val MAX_PRICING_BATCH = 20

        fun batchCountFor(cardCount: Int): Int =
            if (cardCount <= 0) 0 else ((cardCount - 1) / MAX_PRICING_BATCH) + 1

        fun submitLabelFor(cardCount: Int): String = when {
            cardCount <= 0 -> "No pending prices"
            cardCount == 1 -> "Submit 1 card"
            cardCount <= MAX_PRICING_BATCH -> "Submit $cardCount cards"
            else -> "Submit $cardCount cards in ${batchCountFor(cardCount)} batches"
        }

        private val KEY_LEGACY_PACK_MIGRATION_DONE =
            booleanPreferencesKey("legacy_pack_to_scan_session_migration_done")
    }
}
