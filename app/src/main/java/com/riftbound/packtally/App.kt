package com.riftbound.packtally

import android.app.Application
import com.riftbound.packtally.core.carddb.CardDatabase
import com.riftbound.packtally.core.persistence.SessionDatabase
import com.riftbound.packtally.core.persistence.SessionRepository
import com.riftbound.packtally.core.pricing.CachedPricingRepository
import com.riftbound.packtally.core.pricing.HttpPricingRepository
import com.riftbound.packtally.core.pricing.PricingRepository
import com.riftbound.packtally.core.settings.SettingsRepository

class App : Application() {

    lateinit var pricing: PricingRepository
        private set

    lateinit var cachedPricing: CachedPricingRepository
        private set

    lateinit var sessionRepository: SessionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        CardDatabase.init(this)

        // SettingsRepository real impl arrives later; until then, the stub returns
        // null and any pricing call without a key will fail with
        // Result.failure("Missing tcgapi.dev API key…"). MockPricingRepository
        // remains importable from previews.
        val settings: SettingsRepository = StubSettingsRepository
        val http = HttpPricingRepository(settings)
        cachedPricing = CachedPricingRepository(delegate = http, cacheDir = cacheDir)
        pricing = cachedPricing

        val sessionDb = SessionDatabase.create(this)
        sessionRepository = SessionRepository(sessionDb.sessionDao())
    }
}

private object StubSettingsRepository : SettingsRepository {
    override suspend fun getApiKey(): String? = null
}
