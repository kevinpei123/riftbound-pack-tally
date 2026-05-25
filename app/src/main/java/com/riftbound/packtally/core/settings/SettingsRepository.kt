package com.riftbound.packtally.core.settings

/**
 * User-controlled settings persisted across launches.
 *
 * Placeholder interface — the real implementation will be wired up in the next prompt
 * (likely DataStore-backed). [PricingRepository][com.riftbound.packtally.core.pricing.PricingRepository]
 * already consumes [getApiKey], so the contract is fixed.
 */
interface SettingsRepository {
    suspend fun getApiKey(): String?
}
