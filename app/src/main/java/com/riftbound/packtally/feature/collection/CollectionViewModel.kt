package com.riftbound.packtally.feature.collection

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.riftbound.packtally.App
import com.riftbound.packtally.core.persistence.SessionRepository
import com.riftbound.packtally.model.PricingStatus
import com.riftbound.packtally.model.Rarity
import com.riftbound.packtally.model.RiftboundCard
import com.riftbound.packtally.model.ScanEntrySource
import com.riftbound.packtally.model.ScanSessionEntry
import com.riftbound.packtally.model.Variant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private const val TAG = "CollectionViewModel"

data class CollectionEntry(
    val card: RiftboundCard,
    val variant: Variant,
    val quantity: Int,
    val unitPrice: Double,
    val totalMarketValue: Double,
    val pendingCount: Int,
    val failedCount: Int,
    val unpriceableCount: Int,
    val latestScannedAt: Instant,
    val domains: List<String>,
)

enum class CollectionSort {
    NAME,
    SET,
    COLLECTOR,
    RARITY,
    DOMAIN,
    QUANTITY,
    VALUE,
    RECENT,
}

enum class CollectionGroupMode {
    SET,
    RARITY,
    DOMAIN,
    VARIANT,
    NONE,
}

data class CollectionFilter(
    val query: String = "",
    val sort: CollectionSort = CollectionSort.RECENT,
    val group: CollectionGroupMode = CollectionGroupMode.SET,
    val pendingOnly: Boolean = false,
    val variant: Variant? = null,
    val setCode: String? = null,
    val rarity: Rarity? = null,
    val domain: String? = null,
)

data class CollectionOptions(
    val sets: List<String> = emptyList(),
    val domains: List<String> = emptyList(),
    val rarities: List<Rarity> = emptyList(),
)

data class CollectionGroup(
    val title: String,
    val entries: List<CollectionEntry>,
    val totalValue: Double,
    val totalCards: Int,
)

data class CollectionState(
    val groups: List<CollectionGroup> = emptyList(),
    /** Full, unfiltered aggregate — used by export so it always reflects the whole collection. */
    val allEntries: List<CollectionEntry> = emptyList(),
    val totalValue: Double = 0.0,
    val totalCards: Int = 0,
    val uniqueCards: Int = 0,
    val pendingPriceCount: Int = 0,
    val filter: CollectionFilter = CollectionFilter(),
    val options: CollectionOptions = CollectionOptions(),
    val isLoading: Boolean = true,
)

sealed interface CollectionEvent {
    data class ExportSucceeded(val path: String) : CollectionEvent
    data class ExportFailed(val reason: String) : CollectionEvent
    data class ManualAddSucceeded(val cardName: String) : CollectionEvent
    data class ManualAddFailed(val cardName: String) : CollectionEvent
    data class RemoveSucceeded(val cardName: String) : CollectionEvent
    data class RemoveNotFound(val cardName: String) : CollectionEvent
    data class SubmitCompleted(val priced: Int, val failed: Int, val unpriceable: Int) : CollectionEvent
    data class SubmitFailed(val reason: String) : CollectionEvent
    data class RecallCompleted(
        val priced: Int,
        val failed: Int,
        val unpriceable: Int,
        val stoppedReason: String?,
    ) : CollectionEvent
    data class RecallFailed(val reason: String) : CollectionEvent
}

/** Estimate shown in the "refresh all prices" confirmation dialog. */
data class RecallPrompt(
    val calls: Int,
    val waits: Int,
)

class CollectionViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val sessionRepository: SessionRepository = app.sessionRepository

    private val _filter = MutableStateFlow(CollectionFilter())
    private val _submitInFlight = MutableStateFlow(false)
    val submitInFlight: StateFlow<Boolean> = _submitInFlight.asStateFlow()

    private val _pricingProgress = MutableStateFlow<String?>(null)
    val pricingProgress: StateFlow<String?> = _pricingProgress.asStateFlow()

    private val _recallInFlight = MutableStateFlow(false)
    val recallInFlight: StateFlow<Boolean> = _recallInFlight.asStateFlow()

    private val _recallProgress = MutableStateFlow<String?>(null)
    val recallProgress: StateFlow<String?> = _recallProgress.asStateFlow()

    private val _recallPrompt = MutableStateFlow<RecallPrompt?>(null)
    val recallPrompt: StateFlow<RecallPrompt?> = _recallPrompt.asStateFlow()

    val state: StateFlow<CollectionState> =
        combine(sessionRepository.observeAllEntries(), _filter) { entries, filter ->
            buildState(entries, filter)
        }
            // buildState aggregates/sorts/groups the whole collection — keep that
            // CPU work off the main thread.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, CollectionState())

    private val _events = MutableSharedFlow<CollectionEvent>()
    val events: SharedFlow<CollectionEvent> = _events.asSharedFlow()

    fun refresh() = Unit

    fun setQuery(query: String) {
        // Cap length so a pasted megastring can't trigger an unbounded filter/sort pass.
        _filter.value = _filter.value.copy(query = query.take(64))
    }

    fun setSort(sort: CollectionSort) {
        _filter.value = _filter.value.copy(sort = sort)
    }

    fun setGroup(group: CollectionGroupMode) {
        _filter.value = _filter.value.copy(group = group)
    }

    fun togglePendingOnly() {
        _filter.value = _filter.value.copy(pendingOnly = !_filter.value.pendingOnly)
    }

    fun setVariant(variant: Variant?) {
        _filter.value = _filter.value.copy(variant = variant)
    }

    fun setSetCode(setCode: String?) {
        _filter.value = _filter.value.copy(setCode = setCode)
    }

    fun setRarity(rarity: Rarity?) {
        _filter.value = _filter.value.copy(rarity = rarity)
    }

    fun setDomain(domain: String?) {
        _filter.value = _filter.value.copy(domain = domain)
    }

    fun clearFilters() {
        _filter.value = CollectionFilter()
    }

    fun addManualEntry(card: RiftboundCard, variant: Variant) {
        viewModelScope.launch {
            runCatching {
                sessionRepository.addEntry(
                    card = card,
                    variant = variant,
                    source = ScanEntrySource.MANUAL,
                    confidence = 1.0f,
                )
            }.onFailure {
                Log.e(TAG, "Manual add failed for ${card.id} $variant", it)
                _events.emit(CollectionEvent.ManualAddFailed(card.name))
            }.onSuccess {
                _events.emit(CollectionEvent.ManualAddSucceeded(card.name))
            }
        }
    }

    fun removeOne(card: RiftboundCard, variant: Variant) {
        viewModelScope.launch {
            val deleted = runCatching { sessionRepository.removeLatestEntryByCardVariant(card.id, variant) }
                .onFailure { Log.e(TAG, "Remove failed for ${card.id} $variant", it) }
                .getOrDefault(false)
            if (deleted) {
                _events.emit(CollectionEvent.RemoveSucceeded(card.name))
            } else {
                _events.emit(CollectionEvent.RemoveNotFound(card.name))
            }
        }
    }

    fun submitPendingPrices() {
        if (_submitInFlight.value || _recallInFlight.value) return
        _submitInFlight.value = true
        viewModelScope.launch {
            val result = runCatching {
                sessionRepository.submitPendingPrices(
                    pricing = app.pricing,
                    sessionId = null,
                    onProgress = {
                        _pricingProgress.value = "Pricing batch ${it.currentBatch} of ${it.totalBatches}"
                    },
                )
            }
            _submitInFlight.value = false
            _pricingProgress.value = null
            result
                .onSuccess { submit ->
                    when (submit) {
                        SessionRepository.SubmitResult.Empty ->
                            _events.emit(CollectionEvent.SubmitCompleted(0, 0, 0))
                        is SessionRepository.SubmitResult.Done ->
                            _events.emit(
                                CollectionEvent.SubmitCompleted(
                                    priced = submit.priced,
                                    failed = submit.failed,
                                    unpriceable = submit.unpriceable,
                                ),
                            )
                    }
                }
                .onFailure {
                    Log.e(TAG, "Submit pending failed", it)
                    _events.emit(CollectionEvent.SubmitFailed(it.message ?: "pricing failed"))
                }
        }
    }

    /** Compute the cost estimate and raise the confirmation prompt (no network yet). */
    fun requestRecall() {
        if (_recallInFlight.value || _submitInFlight.value) return
        viewModelScope.launch {
            val calls = runCatching { sessionRepository.repriceableCallEstimate() }
                .onFailure { Log.e(TAG, "Recall estimate failed", it) }
                .getOrDefault(0)
            if (calls == 0) {
                _events.emit(CollectionEvent.RecallCompleted(0, 0, 0, null))
            } else {
                _recallPrompt.value = RecallPrompt(
                    calls = calls,
                    waits = SessionRepository.recallWaitCount(calls),
                )
            }
        }
    }

    fun dismissRecallPrompt() {
        _recallPrompt.value = null
    }

    /** Force-refresh every collection price from JustTCG, rate-limited in 10-call windows. */
    fun recallAllPrices() {
        _recallPrompt.value = null
        if (_recallInFlight.value || _submitInFlight.value) return
        _recallInFlight.value = true
        viewModelScope.launch {
            val result = runCatching {
                sessionRepository.repriceAll(app.pricing) { progress ->
                    _recallProgress.value = when (progress) {
                        is SessionRepository.RepriceProgress.Pricing ->
                            "Refreshing ${progress.currentBatch}/${progress.totalBatches}"
                        is SessionRepository.RepriceProgress.Waiting ->
                            "Rate limit - next in ${progress.secondsRemaining}s"
                    }
                }
            }
            _recallInFlight.value = false
            _recallProgress.value = null
            result
                .onSuccess { submit ->
                    when (submit) {
                        SessionRepository.SubmitResult.Empty ->
                            _events.emit(CollectionEvent.RecallCompleted(0, 0, 0, null))
                        is SessionRepository.SubmitResult.Done ->
                            _events.emit(
                                CollectionEvent.RecallCompleted(
                                    priced = submit.priced,
                                    failed = submit.failed,
                                    unpriceable = submit.unpriceable,
                                    stoppedReason = submit.stoppedReason,
                                ),
                            )
                    }
                }
                .onFailure {
                    Log.e(TAG, "Recall all prices failed", it)
                    _events.emit(CollectionEvent.RecallFailed(it.message ?: "refresh failed"))
                }
        }
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

    private fun buildState(entries: List<ScanSessionEntry>, filter: CollectionFilter): CollectionState {
        val aggregate = aggregate(entries)
        val options = CollectionOptions(
            sets = aggregate.map { it.card.setCode }.distinct().sorted(),
            domains = aggregate.flatMap { it.domains }.distinct().sorted(),
            rarities = aggregate.map { it.card.rarity }.distinct().sortedBy { it.ordinal },
        )
        val filtered = aggregate.filter { entry ->
            val q = filter.query.trim().lowercase()
            val queryOk = q.isBlank() ||
                entry.card.name.lowercase().contains(q) ||
                entry.card.collectorNumber.lowercase().contains(q)
            queryOk &&
                // "Pending" means still-priceable work, so exclude UNPRICEABLE
                // (matches pendingPriceCount / the submit button semantics).
                (!filter.pendingOnly || entry.pendingCount + entry.failedCount > 0) &&
                (filter.variant == null || entry.variant == filter.variant) &&
                (filter.setCode == null || entry.card.setCode == filter.setCode) &&
                (filter.rarity == null || entry.card.rarity == filter.rarity) &&
                (filter.domain == null || filter.domain in entry.domains)
        }.sortedWith(comparatorFor(filter.sort))

        val groups = groupEntries(filtered, filter.group)
        return CollectionState(
            groups = groups,
            allEntries = aggregate,
            totalValue = aggregate.sumOf { it.totalMarketValue },
            totalCards = aggregate.sumOf { it.quantity },
            uniqueCards = aggregate.map { it.card.id }.distinct().size,
            pendingPriceCount = aggregate.sumOf { it.pendingCount + it.failedCount },
            filter = filter,
            options = options,
            isLoading = false,
        )
    }

    private fun aggregate(entries: List<ScanSessionEntry>): List<CollectionEntry> =
        entries
            .groupBy { it.card.id to it.variant }
            .map { (_, group) ->
                val card = group.first().card
                val variant = group.first().variant
                val latestPrice = group
                    .filter { it.price != null }
                    .maxByOrNull { it.price!!.lastUpdated }
                    ?.price
                CollectionEntry(
                    card = card,
                    variant = variant,
                    quantity = group.size,
                    unitPrice = latestPrice?.marketPrice ?: 0.0,
                    totalMarketValue = group.sumOf { it.marketPrice },
                    pendingCount = group.count { it.pricingStatus == PricingStatus.PENDING },
                    failedCount = group.count { it.pricingStatus == PricingStatus.FAILED },
                    unpriceableCount = group.count { it.pricingStatus == PricingStatus.UNPRICEABLE },
                    latestScannedAt = group.maxOf { it.scannedAt },
                    domains = card.domains,
                )
            }

    private fun comparatorFor(sort: CollectionSort): Comparator<CollectionEntry> = when (sort) {
        CollectionSort.NAME -> compareBy { it.card.name.lowercase() }
        CollectionSort.SET -> compareBy<CollectionEntry> { it.card.setCode }.thenBy { it.card.collectorNumber }
        CollectionSort.COLLECTOR -> compareBy<CollectionEntry> { it.card.collectorNumber }
        CollectionSort.RARITY -> compareBy<CollectionEntry> { it.card.rarity.ordinal }.thenBy { it.card.name }
        CollectionSort.DOMAIN -> compareBy<CollectionEntry> { it.domains.firstOrNull().orEmpty() }.thenBy { it.card.name }
        CollectionSort.QUANTITY -> compareByDescending<CollectionEntry> { it.quantity }.thenBy { it.card.name }
        CollectionSort.VALUE -> compareByDescending<CollectionEntry> { it.totalMarketValue }.thenBy { it.card.name }
        CollectionSort.RECENT -> compareByDescending<CollectionEntry> { it.latestScannedAt }.thenBy { it.card.name }
    }

    private fun groupEntries(entries: List<CollectionEntry>, groupMode: CollectionGroupMode): List<CollectionGroup> {
        val grouped = when (groupMode) {
            CollectionGroupMode.SET -> entries.groupBy { it.card.setCode }
            CollectionGroupMode.RARITY -> entries.groupBy { it.card.rarity.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
            CollectionGroupMode.DOMAIN -> entries.groupBy { it.domains.firstOrNull() ?: "No domain" }
            CollectionGroupMode.VARIANT -> entries.groupBy { it.variant.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
            CollectionGroupMode.NONE -> mapOf("All cards" to entries)
        }
        return grouped.map { (title, list) ->
            CollectionGroup(
                title = title,
                entries = list,
                totalValue = list.sumOf { it.totalMarketValue },
                totalCards = list.sumOf { it.quantity },
            )
        }.sortedBy { it.title }
    }

    private suspend fun writeExport(state: CollectionState): String = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val dir = context.getExternalFilesDir(null) ?: error("External files dir unavailable")
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val file = File(dir, "collection-$timestamp.json")
        file.writeText(exportJson.encodeToString(ExportPayload.serializer(), state.toExportPayload()))
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
    @SerialName("total_value_usd") val totalValueUsd: Double,
    @SerialName("total_cards") val totalCards: Int,
    @SerialName("unique_cards") val uniqueCards: Int,
    @SerialName("pending_prices") val pendingPrices: Int,
    val collection: List<EntryExport>,
)

@Serializable
private data class EntryExport(
    @SerialName("card_id") val cardId: String,
    val name: String,
    @SerialName("set_code") val setCode: String,
    @SerialName("collector_number") val collectorNumber: String,
    val rarity: String,
    val domains: List<String>,
    val variant: String,
    val quantity: Int,
    @SerialName("unit_price_usd") val unitPriceUsd: Double,
    @SerialName("total_value_usd") val totalValueUsd: Double,
    @SerialName("pending_count") val pendingCount: Int,
    @SerialName("failed_count") val failedCount: Int,
    @SerialName("unpriceable_count") val unpriceableCount: Int,
)

private fun CollectionState.toExportPayload(): ExportPayload = ExportPayload(
    exportedAt = Instant.now().atOffset(ZoneOffset.UTC).toString(),
    totalValueUsd = totalValue,
    totalCards = totalCards,
    uniqueCards = uniqueCards,
    pendingPrices = pendingPriceCount,
    collection = allEntries.map { it.toExport() },
)

private fun CollectionEntry.toExport(): EntryExport = EntryExport(
    cardId = card.id,
    name = card.name,
    setCode = card.setCode,
    collectorNumber = card.collectorNumber,
    rarity = card.rarity.name,
    domains = domains,
    variant = variant.name,
    quantity = quantity,
    unitPriceUsd = unitPrice,
    totalValueUsd = totalMarketValue,
    pendingCount = pendingCount,
    failedCount = failedCount,
    unpriceableCount = unpriceableCount,
)
