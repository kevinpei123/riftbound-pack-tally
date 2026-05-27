package com.riftbound.packtally.feature.collection

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riftbound.packtally.App
import com.riftbound.packtally.core.persistence.LooseScanRepository
import com.riftbound.packtally.core.persistence.SessionRepository
import com.riftbound.packtally.model.Rarity
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.ScannedEntry
import com.riftbound.packtally.model.Variant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** Subset of [quantity] that came from loose scans (manual add / Quick Scan). */
    val looseQuantity: Int = 0,
    /** Subset of [quantity] that came from pack scans. Always = quantity - looseQuantity. */
    val packQuantity: Int = 0,
    /** True if at least one copy needs a price fetched. */
    val hasPendingPrice: Boolean = false,
)

data class CollectionFilter(
    val foilOnly: Boolean = false,
    val signatureOnly: Boolean = false,
    val looseOnly: Boolean = false,
    val selectedRarities: Set<Rarity> = emptySet(),
    val nameQuery: String = "",
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

    /** Manual "+ Add card" succeeded; UI shows a confirmation toast. */
    data class ManualAddSucceeded(val cardName: String) : CollectionEvent

    data class RemoveSucceeded(val cardName: String) : CollectionEvent

    /** Row vanished between when the list was rendered and the user tapped
     *  remove — vanishingly rare; surface a soft error so the user knows
     *  nothing happened. */
    data class RemoveNotFound(val cardName: String) : CollectionEvent

    data class SubmitCompleted(val priced: Int, val failed: Int, val totalValue: Double) : CollectionEvent
    data class SubmitFailed(val reason: String) : CollectionEvent
}

class CollectionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val sessionRepository: SessionRepository = app.sessionRepository
    private val looseScanRepository: LooseScanRepository = app.looseScanRepository

    private val _allEntries = MutableStateFlow<List<CollectionEntry>>(emptyList())
    private val _filter = MutableStateFlow(CollectionFilter())
    private val _isLoading = MutableStateFlow(true)
    private val _hasAnyCompletedPacks = MutableStateFlow(false)
    private val _pendingPriceCount = MutableStateFlow(0)
    val pendingPriceCount: StateFlow<Int> = _pendingPriceCount.asStateFlow()
    private val _submitInFlight = MutableStateFlow(false)
    val submitInFlight: StateFlow<Boolean> = _submitInFlight.asStateFlow()

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

            // Every scanned card appears in Collection — pack entries no longer
            // wait for the pack to be "full" / submitted. Unpriced entries just
            // show with $0 totals until the user submits and prices land.
            val allPackEntries = boxes.flatMap { box -> box.packs.value.flatMap { it.entries.value } }

            // Loose scans participate in Collection aggregation.
            val looseEntries = runCatching { looseScanRepository.getAllForExport() }
                .onFailure { Log.e(TAG, "Loading loose scans failed", it) }
                .getOrDefault(emptyList())

            _hasAnyCompletedPacks.value = allPackEntries.isNotEmpty() || looseEntries.isNotEmpty()
            _allEntries.value = aggregate(allPackEntries + looseEntries)
            _pendingPriceCount.value = runCatching { looseScanRepository.getPending().size }
                .getOrDefault(0)
            _isLoading.value = false
        }
    }

    /** Identical to QuickScanViewModel.submitPending, but reachable from Collection. */
    fun submitPendingPrices() {
        if (_submitInFlight.value) return
        _submitInFlight.value = true
        viewModelScope.launch {
            when (val result = looseScanRepository.submitPendingPrices(app.pricing)) {
                is com.riftbound.packtally.core.persistence.LooseScanRepository.SubmitResult.Empty ->
                    _events.emit(CollectionEvent.SubmitCompleted(priced = 0, failed = 0, totalValue = 0.0))
                is com.riftbound.packtally.core.persistence.LooseScanRepository.SubmitResult.Done ->
                    _events.emit(
                        CollectionEvent.SubmitCompleted(
                            priced = result.priced,
                            failed = result.failed,
                            totalValue = result.totalValue,
                        ),
                    )
                is com.riftbound.packtally.core.persistence.LooseScanRepository.SubmitResult.NetworkError ->
                    _events.emit(CollectionEvent.SubmitFailed(result.reason))
            }
            _submitInFlight.value = false
            refresh()
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

    fun toggleLooseOnlyFilter() {
        _filter.value = _filter.value.copy(looseOnly = !_filter.value.looseOnly)
    }

    fun setNameQuery(query: String) {
        _filter.value = _filter.value.copy(nameQuery = query)
    }

    fun clearFilters() {
        _filter.value = CollectionFilter()
    }

    /**
     * Persist a card the user added by hand (not via OCR). Stored as a loose
     * scan with `price = null` — the next batch submit on the Quick Scan tab
     * picks it up. We deliberately don't auto-fire a single-card pricing call,
     * since that would burn one JustTCG quota slot per add.
     */
    fun addManualEntry(card: RiftboundCard, variant: Variant) {
        viewModelScope.launch {
            runCatching { looseScanRepository.saveEntry(card, variant, price = null) }
                .onFailure { Log.e(TAG, "Manual add failed for ${card.id} $variant", it) }
                .onSuccess {
                    _events.emit(CollectionEvent.ManualAddSucceeded(card.name))
                    refresh()
                }
        }
    }

    /**
     * Remove one copy of (card, variant). Preference order:
     *   1. The most recent loose-scan row (manual add or quick-scan).
     *   2. Failing that, the most recent matching entry inside any persisted pack.
     *
     * Reaching into past packs matters because the Pack tab only shows the
     * active pack — if a card lives in a previously-completed pack the user
     * has no other way to remove it. Surfacing it here keeps Collection as the
     * single source of truth for "what do I own".
     */
    fun removeOne(card: RiftboundCard, variant: Variant) {
        viewModelScope.launch {
            val looseDeleted = runCatching { looseScanRepository.deleteOneMatching(card.id, variant) }
                .onFailure { Log.e(TAG, "Loose remove failed for ${card.id} $variant", it) }
                .getOrDefault(false)
            val deleted = if (looseDeleted) {
                true
            } else {
                runCatching { sessionRepository.removeOneByCardVariant(card.id, variant.name) }
                    .onFailure { Log.e(TAG, "Pack remove failed for ${card.id} $variant", it) }
                    .getOrDefault(false)
            }
            if (deleted) {
                _events.emit(CollectionEvent.RemoveSucceeded(card.name))
            } else {
                _events.emit(CollectionEvent.RemoveNotFound(card.name))
            }
            refresh()
        }
    }

    fun exportToJson() {
        val snapshot = state.value
        viewModelScope.launch {
            val looseSnapshot = runCatching { looseScanRepository.getAllForExport() }
                .getOrDefault(emptyList())
            val path = runCatching { writeExport(snapshot, looseSnapshot) }
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
                // Pick the most recently-priced entry in the group as the
                // representative unit price; if nothing is priced yet, fall
                // back to 0 so the row still appears in the list.
                val unitPrice = group
                    .filter { it.price != null }
                    .maxByOrNull { it.price!!.lastUpdated }
                    ?.price?.marketPrice
                    ?: 0.0
                // Loose-scan entries are tagged "loose-<rowid>" by
                // LooseScanRepository; everything else is a pack scan.
                val looseQty = group.count { it.id.startsWith("loose-") }
                CollectionEntry(
                    card = card,
                    variant = variant,
                    quantity = group.size,
                    unitPrice = unitPrice,
                    totalMarketValue = group.sumOf { it.marketPrice },
                    looseQuantity = looseQty,
                    packQuantity = group.size - looseQty,
                    hasPendingPrice = group.any { it.price == null },
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
        val nameQuery = filter.nameQuery.trim().lowercase()
        return entries.filter { entry ->
            entry.variant in allowedVariants &&
                (filter.selectedRarities.isEmpty() || entry.card.rarity in filter.selectedRarities) &&
                (!filter.looseOnly || entry.looseQuantity > 0) &&
                (nameQuery.isEmpty() || entry.card.name.lowercase().contains(nameQuery))
        }
    }

    private suspend fun writeExport(
        state: CollectionState,
        looseEntries: List<ScannedEntry>,
    ): String = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val dir = context.getExternalFilesDir(null)
            ?: error("External files dir unavailable")
        val timestamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = File(dir, "collection-$timestamp.json")
        val payload = state.toExportPayload(looseEntries)
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
    @SerialName("loose_scans") val looseScans: List<LooseScanExport> = emptyList(),
)

@Serializable
private data class LooseScanExport(
    val id: String,
    @SerialName("card_id") val cardId: String,
    val name: String,
    @SerialName("set_code") val setCode: String,
    @SerialName("collector_number") val collectorNumber: String,
    val rarity: String,
    val variant: String,
    @SerialName("market_price") val marketPrice: Double,
    @SerialName("scanned_at") val scannedAt: String,
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

private fun CollectionState.toExportPayload(
    looseEntries: List<ScannedEntry>,
): ExportPayload = ExportPayload(
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
    looseScans = looseEntries.map { entry ->
        LooseScanExport(
            id = entry.id,
            cardId = entry.card.id,
            name = entry.card.name,
            setCode = entry.card.setCode,
            collectorNumber = entry.card.collectorNumber,
            rarity = entry.card.rarity.name,
            variant = entry.variant.name,
            marketPrice = entry.marketPrice,
            scannedAt = entry.scannedAt.atOffset(ZoneOffset.UTC).toString(),
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
