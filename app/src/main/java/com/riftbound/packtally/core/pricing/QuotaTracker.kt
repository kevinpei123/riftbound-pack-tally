package com.riftbound.packtally.core.pricing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * tcgapi.dev Hobby tier — 1000 successful network requests per UTC day, resetting
 * at 00:00 UTC. Cache hits and 4xx/5xx responses do NOT count, matching the
 * upstream billing model.
 *
 * State is persisted to DataStore under a date-scoped key (`quota_used_<yyyy-MM-dd>`),
 * so a force-stop mid-day doesn't lose the counter, and a stale key from a previous
 * day is implicitly ignored because the read goes through `usedKey(today)`.
 *
 * The "cache-only" flag is session-scoped — it flips back to false on app restart
 * or when the UTC date rolls over.
 */
class QuotaTracker(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = { Instant.now() },
) {

    private val mutex = Mutex()
    private var lastObservedDate: String = utcDate(clock())
    private var snackbarShownThisSession: Boolean = false

    private val _state = MutableStateFlow(
        QuotaState(used = 0, limit = LIMIT, resetsAt = utcMidnightAfter(clock())),
    )
    val state: StateFlow<QuotaState> = _state.asStateFlow()

    private val _useCachedOnly = MutableStateFlow(false)
    val useCachedOnly: StateFlow<Boolean> = _useCachedOnly.asStateFlow()

    private val _events = MutableSharedFlow<QuotaEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<QuotaEvent> = _events.asSharedFlow()

    init {
        // Pull persisted count on construction so the Settings quota card
        // shows accurate "used" even before the first scan of the session.
        scope.launch { refreshState() }
    }

    /** Read current state. Cheap — doesn't touch DataStore unless date rolled over. */
    suspend fun currentState(): QuotaState {
        refreshDateIfRolled()
        return _state.value
    }

    /** True once today's counter has hit [LIMIT]. */
    suspend fun isAtCapacity(): Boolean = currentState().used >= LIMIT

    /**
     * Record one successful network call. Returns the new state after increment.
     * Idempotent w.r.t. UTC midnight rollover — increments are scoped to today's key.
     */
    suspend fun recordNetworkCall(): QuotaState = mutex.withLock {
        refreshDateIfRolled()
        val today = lastObservedDate
        val newCount = dataStore.edit { prefs ->
            val key = usedKey(today)
            prefs[key] = (prefs[key] ?: 0) + 1
            prefs[LAST_DATE_KEY] = today
        }[usedKey(today)] ?: 0

        val newState = QuotaState(
            used = newCount,
            limit = LIMIT,
            resetsAt = utcMidnightAfter(clock()),
        )
        _state.value = newState
        emitThresholdEventsIfNeeded(newState)
        newState
    }

    /** Debug button on Settings calls this. Wipes today's counter to zero. */
    suspend fun reset(): QuotaState = mutex.withLock {
        refreshDateIfRolled()
        val today = lastObservedDate
        dataStore.edit { prefs ->
            prefs[usedKey(today)] = 0
        }
        snackbarShownThisSession = false
        val s = QuotaState(used = 0, limit = LIMIT, resetsAt = utcMidnightAfter(clock()))
        _state.value = s
        s
    }

    /** Flip the session-scoped "skip network even on cache miss" flag. */
    fun setUseCachedOnly(value: Boolean) {
        _useCachedOnly.value = value
    }

    private suspend fun refreshState() {
        refreshDateIfRolled()
        val today = lastObservedDate
        val used = dataStore.data.first()[usedKey(today)] ?: 0
        _state.value = QuotaState(
            used = used,
            limit = LIMIT,
            resetsAt = utcMidnightAfter(clock()),
        )
    }

    private fun refreshDateIfRolled() {
        val today = utcDate(clock())
        if (today != lastObservedDate) {
            lastObservedDate = today
            snackbarShownThisSession = false
            _useCachedOnly.value = false
        }
    }

    private fun emitThresholdEventsIfNeeded(state: QuotaState) {
        val pct = state.percentUsed
        if (pct >= NEAR_LIMIT && !snackbarShownThisSession) {
            snackbarShownThisSession = true
            _events.tryEmit(QuotaEvent.NearLimit(state.used, state.limit))
        }
        if (pct >= CONFIRM_THRESHOLD && state.used < state.limit) {
            _events.tryEmit(QuotaEvent.PromptConfirm(state.used, state.limit))
        }
    }

    private fun usedKey(date: String) = intPreferencesKey("quota_used_$date")

    private fun utcDate(now: Instant): String =
        DateTimeFormatter.ISO_LOCAL_DATE.format(now.atZone(ZoneOffset.UTC))

    private fun utcMidnightAfter(now: Instant): Instant =
        now.atZone(ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.DAYS)
            .plusDays(1)
            .toInstant()

    companion object {
        const val LIMIT: Int = 1000
        const val NEAR_LIMIT: Float = 0.80f
        const val CONFIRM_THRESHOLD: Float = 0.95f
        private val LAST_DATE_KEY = stringPreferencesKey("quota_last_date")
    }
}

data class QuotaState(
    val used: Int,
    val limit: Int,
    val resetsAt: Instant,
) {
    val percentUsed: Float get() = if (limit == 0) 0f else used.toFloat() / limit
    val remaining: Int get() = (limit - used).coerceAtLeast(0)
    val isAtCapacity: Boolean get() = used >= limit
}

sealed interface QuotaEvent {
    /** Fires once per session at ≥80% usage — host shows a non-blocking Snackbar. */
    data class NearLimit(val used: Int, val limit: Int) : QuotaEvent

    /** Fires on every network call at ≥95% — host shows the confirm dialog. */
    data class PromptConfirm(val used: Int, val limit: Int) : QuotaEvent
}

/** Throws when the daily 1000-request budget is exhausted. */
class RateLimitedException(
    val used: Int,
    val limit: Int,
    val resetsAt: Instant,
) : Exception("Daily quota exhausted ($used/$limit). Resets at $resetsAt.")

/** Throws when the user has flipped the cache-only session flag. */
class CachedOnlyModeException : Exception("Network blocked — cache-only mode is active for this session.")
