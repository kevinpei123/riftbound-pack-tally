package com.riftbound.packtally

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.core.persistence.SessionDatabase
import com.riftbound.packtally.core.persistence.SessionRepository
import com.riftbound.packtally.core.pricing.CachedPricingRepository
import com.riftbound.packtally.core.pricing.HttpPricingRepository
import com.riftbound.packtally.core.pricing.PricingRepository
import com.riftbound.packtally.core.settings.DataStoreSettingsRepository
import com.riftbound.packtally.core.settings.SettingsRepository
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

    lateinit var sessionDatabase: SessionDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    private val _resetEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val resetEvents: SharedFlow<Unit> = _resetEvents.asSharedFlow()

    override fun onCreate() {
        super.onCreate()
        CardDatabase.init(this)

        settingsRepository = DataStoreSettingsRepository(settingsDataStore)

        val http = HttpPricingRepository(settingsRepository)
        cachedPricing = CachedPricingRepository(
            delegate = http,
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
    }

    /**
     * Nuclear reset — wipes Room tables, the price cache directory, and DataStore
     * preferences, then emits a [resetEvents] tick so live ViewModels can clear
     * their in-memory state too.
     */
    suspend fun resetAll() {
        sessionDatabase.clearAllTables()
        cachedPricing.clearCache()
        settingsRepository.resetAll()
        _resetEvents.tryEmit(Unit)
    }
}
