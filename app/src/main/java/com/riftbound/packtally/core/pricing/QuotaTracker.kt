package com.riftbound.packtally.core.pricing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * JustTCG free tier has three independent limits:
 *
 *   - Monthly: 1000 / billing cycle (server tells us exactly how many remain)
 *   - Daily:   100  / UTC day
 *   - Minute:  10   / rolling minute
 *
 * The minute bucket is the most aggressive — at 7/10 used the tracker signals
 * [JustTcgPricingRepository] to back off with a 6-second delay so we never
 * actually hit the 429.
 *
 * Server hints from `_metadata` on every JustTCG response are authoritative —
 * if the server says we have 750 monthly remaining and our local counter says
 * 800, [applyServerHints] adjusts to 250 used. This corrects drift from any
 * out-of-band requests (e.g. another device on the same key).
 */
class QuotaTracker(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = { Instant.now() },
) {

    private val mutex = Mutex()
    private var lastObservedMonth: String = utcYearMonth(clock())
    private var lastObservedDate: String = utcDate(clock())
    private var lastObservedMinute: String = utcMinute(clock())
    private var snackbarShownThisSession: Boolean = false

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<QuotaState> = _state.asStateFlow()

    private val _useCachedOnly = MutableStateFlow(false)
    val useCachedOnly: StateFlow<Boolean> = _useCachedOnly.asStateFlow()

    private val _events = MutableSharedFlow<QuotaEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<QuotaEvent> = _events.asSharedFlow()

    init {
        scope.launch { refresh() }
    }

    suspend fun currentState(): QuotaState {
        refreshKeysIfRolled()
        return _state.value
    }

    suspend fun isAtCapacity(): Boolean {
        val s = currentState()
        return s.monthlyUsed >= s.monthlyLimit ||
            s.dailyUsed >= s.dailyLimit ||
            s.minuteUsed >= s.minuteLimit
    }

    /** Returns true if a backoff delay is recommended (≥7/10 minute used). */
    suspend fun shouldBackoff(): Boolean = currentState().minuteUsed >= MINUTE_BACKOFF_THRESHOLD

    /**
     * Record one successful network call. The batch path increments by [calls]
     * (typically 1 per POST, regardless of how many items were in the batch —
     * JustTCG charges per request, not per item).
     */
    suspend fun recordNetworkCall(calls: Int = 1): QuotaState = mutex.withLock {
        refreshKeysIfRolled()
        val monthKey = lastObservedMonth
        val dateKey = lastObservedDate
        val minuteKey = lastObservedMinute
        dataStore.edit { prefs ->
            prefs[monthlyKey(monthKey)] = (prefs[monthlyKey(monthKey)] ?: 0) + calls
            prefs[dailyKey(dateKey)] = (prefs[dailyKey(dateKey)] ?: 0) + calls
            prefs[minuteKey(minuteKey)] = (prefs[minuteKey(minuteKey)] ?: 0) + calls
            prefs[LAST_MONTH_KEY] = monthKey
            prefs[LAST_DATE_KEY] = dateKey
            prefs[LAST_MINUTE_KEY] = minuteKey
        }
        val s = readSnapshot()
        _state.value = s
        emitThresholdEventsIfNeeded(s)
        s
    }

    /**
     * Correct local counters from the server's `_metadata` on every JustTCG
     * response. If the server says we have N monthly requests remaining and our
     * local count would suggest a different number, trust the server.
     */
    suspend fun applyServerHints(metadata: JustTcgMetadata) = mutex.withLock {
        refreshKeysIfRolled()
        val monthly = metadata.monthlyRemaining
        val daily = metadata.dailyRemaining
        val rateLimit = metadata.rateLimit
        if (monthly == null && daily == null) return@withLock
        dataStore.edit { prefs ->
            if (monthly != null) {
                prefs[monthlyKey(lastObservedMonth)] =
                    (MONTHLY_LIMIT - monthly).coerceIn(0, MONTHLY_LIMIT)
            }
            if (daily != null) {
                prefs[dailyKey(lastObservedDate)] =
                    (DAILY_LIMIT - daily).coerceIn(0, DAILY_LIMIT)
            }
            // rateLimit is informational; we keep our own minute counter.
        }
        _state.value = readSnapshot()
    }

    suspend fun reset(): QuotaState = mutex.withLock {
        refreshKeysIfRolled()
        dataStore.edit { prefs ->
            prefs[monthlyKey(lastObservedMonth)] = 0
            prefs[dailyKey(lastObservedDate)] = 0
            prefs[minuteKey(lastObservedMinute)] = 0
        }
        snackbarShownThisSession = false
        val s = readSnapshot()
        _state.value = s
        s
    }

    fun setUseCachedOnly(value: Boolean) {
        _useCachedOnly.value = value
        scope.launch { dataStore.edit { it[USE_CACHED_ONLY_KEY] = value } }
    }

    private suspend fun refresh() {
        refreshKeysIfRolled()
        _useCachedOnly.value = dataStore.data.first()[USE_CACHED_ONLY_KEY] ?: false
        _state.value = readSnapshot()
    }

    private suspend fun readSnapshot(): QuotaState {
        val prefs = dataStore.data.first()
        val monthly = prefs[monthlyKey(lastObservedMonth)] ?: 0
        val daily = prefs[dailyKey(lastObservedDate)] ?: 0
        val minute = prefs[minuteKey(lastObservedMinute)] ?: 0
        return QuotaState(
            monthlyUsed = monthly,
            monthlyLimit = MONTHLY_LIMIT,
            dailyUsed = daily,
            dailyLimit = DAILY_LIMIT,
            minuteUsed = minute,
            minuteLimit = MINUTE_LIMIT,
            resetsAt = nextMonthStart(clock()),
            dailyResetsAt = utcMidnightAfter(clock()),
            minuteResetsAt = nextMinuteStart(clock()),
        )
    }

    private fun refreshKeysIfRolled() {
        val month = utcYearMonth(clock())
        val date = utcDate(clock())
        val minute = utcMinute(clock())
        var rolled = false
        if (month != lastObservedMonth) {
            lastObservedMonth = month
            snackbarShownThisSession = false
            _useCachedOnly.value = false
            scope.launch { dataStore.edit { it[USE_CACHED_ONLY_KEY] = false } }
            rolled = true
        }
        if (date != lastObservedDate) {
            lastObservedDate = date
            rolled = true
        }
        if (minute != lastObservedMinute) {
            lastObservedMinute = minute
            rolled = true
        }
        if (rolled) {
            // The persisted counters are date/month/minute-scoped, so just re-read.
            scope.launch { refresh() }
        }
    }

    private fun emitThresholdEventsIfNeeded(state: QuotaState) {
        if (state.monthlyPercentUsed >= NEAR_LIMIT && !snackbarShownThisSession) {
            snackbarShownThisSession = true
            _events.tryEmit(QuotaEvent.NearLimit(state))
        }
        if (state.monthlyPercentUsed >= CONFIRM_THRESHOLD && state.monthlyUsed < state.monthlyLimit) {
            _events.tryEmit(QuotaEvent.PromptConfirm(state))
        }
    }

    private fun initialState(): QuotaState = QuotaState(
        monthlyUsed = 0,
        monthlyLimit = MONTHLY_LIMIT,
        dailyUsed = 0,
        dailyLimit = DAILY_LIMIT,
        minuteUsed = 0,
        minuteLimit = MINUTE_LIMIT,
        resetsAt = nextMonthStart(clock()),
        dailyResetsAt = utcMidnightAfter(clock()),
        minuteResetsAt = nextMinuteStart(clock()),
    )

    private fun monthlyKey(month: String) = intPreferencesKey("quota_monthly_$month")
    private fun dailyKey(date: String) = intPreferencesKey("quota_daily_$date")
    private fun minuteKey(minute: String) = intPreferencesKey("quota_minute_$minute")

    private fun utcYearMonth(now: Instant): String =
        YearMonth.from(now.atZone(ZoneOffset.UTC)).toString()

    private fun utcDate(now: Instant): String =
        DateTimeFormatter.ISO_LOCAL_DATE.format(now.atZone(ZoneOffset.UTC))

    private fun utcMinute(now: Instant): String =
        now.atZone(ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MINUTES)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

    private fun nextMonthStart(now: Instant): Instant {
        val zdt = now.atZone(ZoneOffset.UTC)
        val nextMonth = YearMonth.from(zdt).plusMonths(1)
        return nextMonth.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()
    }

    private fun utcMidnightAfter(now: Instant): Instant =
        now.atZone(ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.DAYS)
            .plusDays(1)
            .toInstant()

    private fun nextMinuteStart(now: Instant): Instant =
        now.atZone(ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MINUTES)
            .plusMinutes(1)
            .toInstant()

    companion object {
        const val MONTHLY_LIMIT: Int = 1000
        const val DAILY_LIMIT: Int = 100
        const val MINUTE_LIMIT: Int = 10
        const val MINUTE_BACKOFF_THRESHOLD: Int = 7
        const val BACKOFF_DELAY_MS: Long = 6_000L

        const val NEAR_LIMIT: Float = 0.80f
        const val CONFIRM_THRESHOLD: Float = 0.95f

        private val LAST_MONTH_KEY = stringPreferencesKey("quota_last_month")
        private val LAST_DATE_KEY = stringPreferencesKey("quota_last_date")
        private val LAST_MINUTE_KEY = stringPreferencesKey("quota_last_minute")
        private val USE_CACHED_ONLY_KEY = booleanPreferencesKey("quota_use_cached_only")
    }
}

data class QuotaState(
    val monthlyUsed: Int,
    val monthlyLimit: Int,
    val dailyUsed: Int,
    val dailyLimit: Int,
    val minuteUsed: Int,
    val minuteLimit: Int,
    val resetsAt: Instant,
    val dailyResetsAt: Instant,
    val minuteResetsAt: Instant,
) {
    val monthlyPercentUsed: Float get() = if (monthlyLimit == 0) 0f else monthlyUsed.toFloat() / monthlyLimit
    val dailyPercentUsed: Float get() = if (dailyLimit == 0) 0f else dailyUsed.toFloat() / dailyLimit
    val minutePercentUsed: Float get() = if (minuteLimit == 0) 0f else minuteUsed.toFloat() / minuteLimit
    val isAtCapacity: Boolean
        get() = monthlyUsed >= monthlyLimit ||
            dailyUsed >= dailyLimit ||
            minuteUsed >= minuteLimit
}

sealed interface QuotaEvent {
    data class NearLimit(val state: QuotaState) : QuotaEvent
    data class PromptConfirm(val state: QuotaState) : QuotaEvent
}

class RateLimitedException(
    val state: QuotaState,
    val reason: String = "Daily/monthly/minute quota exhausted",
) : Exception("$reason — monthly ${state.monthlyUsed}/${state.monthlyLimit}, daily ${state.dailyUsed}/${state.dailyLimit}, minute ${state.minuteUsed}/${state.minuteLimit}")

class CachedOnlyModeException : Exception("Network blocked — cache-only mode is active for this session.")
