package com.riftbound.packtally

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.core.backup.BackupRepository
import com.riftbound.packtally.core.persistence.LooseScanRepository
import com.riftbound.packtally.core.persistence.SessionDatabase
import com.riftbound.packtally.core.persistence.SessionRepository
import com.riftbound.packtally.core.pricing.CachedPricingRepository
import com.riftbound.packtally.core.pricing.HttpPricingRepository
import com.riftbound.packtally.core.pricing.PricingRepository
import com.riftbound.packtally.core.pricing.QuotaAwarePricingRepository
import com.riftbound.packtally.core.pricing.QuotaTracker
import com.riftbound.packtally.core.settings.DataStoreSettingsRepository
import com.riftbound.packtally.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Duration

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class App : Application() {

    lateinit var pricing: PricingRepository
        private set

    lateinit var cachedPricing: CachedPricingRepository
        private set

    lateinit var sessionRepository: SessionRepository
        private set

    lateinit var looseScanRepository: LooseScanRepository
        private set

    lateinit var sessionDatabase: SessionDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var quotaTracker: QuotaTracker
        private set

    lateinit var backupRepository: BackupRepository
        private set

    /** Application-scoped coroutine scope for long-lived background work. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob())

    private val _resetEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resetEvents: SharedFlow<Unit> = _resetEvents.asSharedFlow()

    override fun onCreate() {
        super.onCreate()
        CardDatabase.init(this)

        settingsRepository = DataStoreSettingsRepository(settingsDataStore)

        // Quota tracker shares the same DataStore as settings — keyed under
        // `quota_used_<yyyy-MM-dd>` so there's no collision with settings keys.
        quotaTracker = QuotaTracker(
            dataStore = settingsDataStore,
            scope = appScope,
        )

        val http = HttpPricingRepository(settingsRepository)
        // CHOICE: Cached wraps QuotaAware wraps Http. Cache hits never touch
        // QuotaAware, so they don't burn budget — matching tcgapi.dev's billing.
        val quotaAware = QuotaAwarePricingRepository(http, quotaTracker)
        cachedPricing = CachedPricingRepository(
            delegate = quotaAware,
            cacheDir = cacheDir,
            ttlProvider = {
                Duration.ofHours(
                    settingsRepository.getCurrentSettings().cacheTtlHours.toLong(),
                )
            },
        )
        pricing = cachedPricing

        sessionDatabase = SessionDatabase.create(this)
        sessionRepository = SessionRepository(sessionDatabase.sessionDao())
        looseScanRepository = LooseScanRepository(sessionDatabase.looseScanDao())

        backupRepository = BackupRepository(
            context = this,
            sessionDatabase = sessionDatabase,
            settingsRepository = settingsRepository,
        )
    }

    /**
     * Nuclear reset — wipes Room tables, the price cache directory, and DataStore
     * preferences (which also wipes the quota counter), then emits a [resetEvents]
     * tick so live ViewModels can clear their in-memory state too.
     */
    suspend fun resetAll() {
        sessionDatabase.clearAllTables()
        cachedPricing.clearCache()
        settingsRepository.resetAll()
        _resetEvents.tryEmit(Unit)
    }
}

