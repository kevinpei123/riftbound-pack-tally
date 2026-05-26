package com.riftbound.packtally.core.persistence

import android.util.Log
import com.riftbound.packtally.model.BoxSession
import com.riftbound.packtally.model.PackSession
import com.riftbound.packtally.model.ScannedEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.Instant

private const val TAG = "SessionRepository"

class SessionRepository(private val dao: SessionDao) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val entryListSerializer = ListSerializer(ScannedEntry.serializer())

    suspend fun loadMostRecentBox(): BoxSession? = withContext(Dispatchers.IO) {
        val boxEntity = dao.mostRecentBox() ?: return@withContext null
        hydrate(boxEntity)
    }

    suspend fun loadAllBoxes(): List<BoxSession> = withContext(Dispatchers.IO) {
        dao.allBoxes().map { hydrate(it) }
    }

    private suspend fun hydrate(boxEntity: BoxSessionEntity): BoxSession {
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
}
