package com.riftbound.packtally

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.riftbound.packtally.core.backup.BackupRepository
import com.riftbound.packtally.core.carddb.CardDbSync
import com.riftbound.packtally.core.carddb.RiftcodexClient
import com.riftbound.packtally.core.currency.CurrencyRateRepository
import com.riftbound.packtally.core.currency.FrankfurterCurrencyRateService
import com.riftbound.packtally.core.persistence.BackfillJob
import com.riftbound.packtally.core.persistence.CardDao
import com.riftbound.packtally.core.persistence.LooseScanRepository
import com.riftbound.packtally.core.persistence.SessionDatabase
import com.riftbound.packtally.core.persistence.SessionRepository
import com.riftbound.packtally.core.pricing.CachedPricingRepository
import com.riftbound.packtally.core.pricing.JustTcgClient
import com.riftbound.packtally.core.pricing.JustTcgPricingRepository
import com.riftbound.packtally.core.pricing.PricingRepository
import com.riftbound.packtally.core.pricing.QuotaTracker
import com.riftbound.packtally.core.settings.DataStoreSettingsRepository
import com.riftbound.packtally.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.time.Duration

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Card-art disk cache bound — keeps the browser/detail screens' image cache from growing unbounded. */
private const val CARD_ART_CACHE_BYTES = 50L * 1024 * 1024

class App : Application(), ImageLoaderFactory {

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

    lateinit var currencyRateRepository: CurrencyRateRepository
        private set

    lateinit var quotaTracker: QuotaTracker
        private set

    lateinit var backupRepository: BackupRepository
        private set

    lateinit var riftcodexClient: RiftcodexClient
        private set

    lateinit var cardDao: CardDao
        private set

    lateinit var cardDbSync: CardDbSync
        private set

    lateinit var backfillJob: BackfillJob
        private set

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob())

    private val _resetEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resetEvents: SharedFlow<Unit> = _resetEvents.asSharedFlow()

    override fun onCreate() {
        super.onCreate()

        settingsRepository = DataStoreSettingsRepository(settingsDataStore)
        currencyRateRepository = CurrencyRateRepository(
            service = FrankfurterCurrencyRateService(),
            settingsRepository = settingsRepository,
        )

        quotaTracker = QuotaTracker(
            dataStore = settingsDataStore,
            scope = appScope,
        )

        // JustTCG batched pricing. Quota concerns live inside the JustTcg
        // repository (per-batch increment + server hints + back-off).
        val justTcgClient = JustTcgClient(settingsRepository)
        val httpRepo = JustTcgPricingRepository(justTcgClient, quotaTracker)
        cachedPricing = CachedPricingRepository(
            delegate = httpRepo,
            cacheDir = cacheDir,
            ttlProvider = {
                Duration.ofHours(
                    settingsRepository.getCurrentSettings().cacheTtlHours.toLong(),
                )
            },
        )
        pricing = cachedPricing

        sessionDatabase = SessionDatabase.create(this)
        sessionRepository = SessionRepository(sessionDatabase.sessionDao(), settingsDataStore)
        looseScanRepository = LooseScanRepository(sessionDatabase.looseScanDao())
        cardDao = sessionDatabase.cardDao()

        riftcodexClient = RiftcodexClient()
        cardDbSync = CardDbSync(
            client = riftcodexClient,
            cardDao = cardDao,
            dataStore = settingsDataStore,
        )
        backfillJob = BackfillJob(
            looseScanDao = sessionDatabase.looseScanDao(),
            cardDao = cardDao,
            dataStore = settingsDataStore,
        )

        backupRepository = BackupRepository(
            context = this,
            sessionDatabase = sessionDatabase,
            settingsRepository = settingsRepository,
        )

        appScope.launch {
            currencyRateRepository.refreshIfStale()
        }
        appScope.launch {
            sessionRepository.migrateLegacyPacksIfNeeded()
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("card_art_cache"))
                .maxSizeBytes(CARD_ART_CACHE_BYTES)
                .build()
        }
        .build()

    suspend fun resetAll() {
        sessionDatabase.clearAllTables()
        cachedPricing.clearCache()
        settingsRepository.resetAll()
        _resetEvents.tryEmit(Unit)
    }
}
