package com.riftbound.packtally.feature.collection

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riftbound.packtally.App
import com.riftbound.packtally.core.persistence.SessionRepository
import com.riftbound.packtally.feature.scanner.Variant
import com.riftbound.packtally.model.Rarity
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.ScannedEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val TAG = "CollectionViewModel"

data class CollectionEntry(
    val card: RiftboundCard,
    val variant: Variant,
    val quantity: Int,
    val unitPrice: Double,
    val totalMarketValue: Double,
)

data class CollectionFilter(
    val foilOnly: Boolean = false,
    val signatureOnly: Boolean = false,
    val selectedRarities: Set<Rarity> = emptySet(),
)

data class CollectionGroup(
    val setCode: String,
    val entries: List<CollectionEntry>,
    val totalValue: Double,
)

data class CollectionState(
    val groups: List<CollectionGroup> = emptyList(),
    val totalValue: Double = 0.0,
    val totalCards: Int = 0,
    val filter: CollectionFilter = CollectionFilter(),
    val isLoading: Boolean = true,
    val hasAnyCompletedPacks: Boolean = false,
)

sealed interface CollectionEvent {
    data class ExportSucceeded(val path: String) : CollectionEvent
    data class ExportFailed(val reason: String) : CollectionEvent
}

class CollectionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val sessionRepository: SessionRepository = app.sessionRepository

    private val _allEntries = MutableStateFlow<List<CollectionEntry>>(emptyList())
    private val _filter = MutableStateFlow(CollectionFilter())
    private val _isLoading = MutableStateFlow(true)
    private val _hasAnyCompletedPacks = MutableStateFlow(false)

    val state: StateFlow<CollectionState> =
        combine(
            _allEntries,
            _filter,
            _isLoading,
            _hasAnyCompletedPacks,
        ) { entries, filter, isLoading, hasAny ->
            val filtered = applyFilter(entries, filter)
            val grouped = filtered
                .groupBy { it.card.setCode }
                .map { (setCode, list) ->
                    val sorted = list.sortedByDescending { it.totalMarketValue }
                    CollectionGroup(
                        setCode = setCode,
                        entries = sorted,
                        totalValue = sorted.sumOf { it.totalMarketValue },
                    )
                }
                .sortedByDescending { it.totalValue }
            CollectionState(
                groups = grouped,
                totalValue = filtered.sumOf { it.totalMarketValue },
                totalCards = filtered.sumOf { it.quantity },
                filter = filter,
                isLoading = isLoading,
                hasAnyCompletedPacks = hasAny,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CollectionState())

    private val _events = MutableSharedFlow<CollectionEvent>()
    val events: SharedFlow<CollectionEvent> = _events.asSharedFlow()

    init {
        refresh()
        viewModelScope.launch {
            app.resetEvents.collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _isLoading.value = true
            val boxes = runCatching { sessionRepository.loadAllBoxes() }
                .onFailure { Log.e(TAG, "Loading sessions failed", it) }
                .getOrDefault(emptyList())

            // "Completed" granularity = per-pack, not per-box. A pack with 14 entries
            // is considered done even if its containing box isn't fully open yet.
            val completedPacks = boxes.flatMap { it.packs.value }.filter { it.isFull }
            val rawEntries = completedPacks.flatMap { it.entries.value }
            _hasAnyCompletedPacks.value = completedPacks.isNotEmpty()
            _allEntries.value = aggregate(rawEntries)
            _isLoading.value = false
        }
    }

    fun toggleFoilFilter() {
        _filter.value = _filter.value.copy(foilOnly = !_filter.value.foilOnly)
    }

    fun toggleSignatureFilter() {
        _filter.value = _filter.value.copy(signatureOnly = !_filter.value.signatureOnly)
    }

    fun toggleRarityFilter(rarity: Rarity) {
        val current = _filter.value.selectedRarities
        _filter.value = _filter.value.copy(
            selectedRarities = if (rarity in current) current - rarity else current + rarity,
        )
    }

    fun clearFilters() {
        _filter.value = CollectionFilter()
    }

    fun exportToJson() {
        val snapshot = state.value
        viewModelScope.launch {
            val path = runCatching { writeExport(snapshot) }
                .onFailure { Log.e(TAG, "Export failed", it) }
                .getOrElse {
                    _events.emit(CollectionEvent.ExportFailed(it.message ?: "Export failed"))
                    return@launch
                }
            _events.emit(CollectionEvent.ExportSucceeded(path))
        }
    }

    private fun aggregate(entries: List<ScannedEntry>): List<CollectionEntry> =
        entries
            .groupBy { it.card.id to it.variant }
            .map { (_, group) ->
                val card = group.first().card
                val variant = group.first().variant
                val unitPrice = group
                    .maxByOrNull { it.price.lastUpdated }
                    ?.price?.marketPrice
                    ?: 0.0
                CollectionEntry(
                    card = card,
                    variant = variant,
                    quantity = group.size,
                    unitPrice = unitPrice,
                    totalMarketValue = group.sumOf { it.price.marketPrice },
                )
            }

    private fun applyFilter(
        entries: List<CollectionEntry>,
        filter: CollectionFilter,
    ): List<CollectionEntry> {
        val allowedVariants: Set<Variant> = when {
            filter.foilOnly && filter.signatureOnly -> setOf(Variant.FOIL, Variant.SIGNATURE)
            filter.foilOnly -> setOf(Variant.FOIL)
            filter.signatureOnly -> setOf(Variant.SIGNATURE)
            else -> Variant.entries.toSet()
        }
        return entries.filter { entry ->
            entry.variant in allowedVariants &&
                (filter.selectedRarities.isEmpty() || entry.card.rarity in filter.selectedRarities)
        }
    }

    private suspend fun writeExport(state: CollectionState): String = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val dir = context.getExternalFilesDir(null)
            ?: error("External files dir unavailable")
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(dir, "collection-$timestamp.json")
        val payload = state.toExportPayload()
        file.writeText(exportJson.encodeToString(ExportPayload.serializer(), payload))
        file.absolutePath
    }

    private companion object {
        val exportJson: Json = Json {
            prettyPrint = true
            encodeDefaults = true
        }
    }
}

@Serializable
private data class ExportPayload(
    @SerialName("exported_at") val exportedAt: String,
    @SerialName("total_value") val totalValue: Double,
    @SerialName("total_cards") val totalCards: Int,
    val sets: List<SetGroupExport>,
)

@Serializable
private data class SetGroupExport(
    @SerialName("set_code") val setCode: String,
    @SerialName("total_value") val totalValue: Double,
    val entries: List<EntryExport>,
)

@Serializable
private data class EntryExport(
    @SerialName("card_id") val cardId: String,
    val name: String,
    @SerialName("set_code") val setCode: String,
    @SerialName("collector_number") val collectorNumber: String,
    val rarity: String,
    val variant: String,
    val quantity: Int,
    @SerialName("unit_price") val unitPrice: Double,
    @SerialName("total_value") val totalValue: Double,
)

private fun CollectionState.toExportPayload(): ExportPayload = ExportPayload(
    exportedAt = Instant.now().atOffset(ZoneOffset.UTC).toString(),
    totalValue = totalValue,
    totalCards = totalCards,
    sets = groups.map { group ->
        SetGroupExport(
            setCode = group.setCode,
            totalValue = group.totalValue,
            entries = group.entries.map { it.toExport() },
        )
    },
)

private fun CollectionEntry.toExport(): EntryExport = EntryExport(
    cardId = card.id,
    name = card.name,
    setCode = card.setCode,
    collectorNumber = card.collectorNumber,
    rarity = card.rarity.name,
    variant = variant.name,
    quantity = quantity,
    unitPrice = unitPrice,
    totalValue = totalMarketValue,
)
