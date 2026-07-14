package com.riftbound.packtally.ui.currency

import androidx.compose.runtime.staticCompositionLocalOf
import com.riftbound.packtally.core.settings.Currency
import java.util.Locale

/**
 * Formats USD amounts (the canonical storage unit) into the user's preferred
 * display currency. When [currency] is [Currency.USD], [usdToTargetRate] is
 * ignored (no conversion).
 *
 * Provided via [LocalCurrencyFormatter] at the activity level so every screen
 * can format prices without threading the settings flow through every VM.
 */
class CurrencyFormatter(
    private val currency: Currency,
    private val usdToTargetRate: Double,
) {
    fun format(usdAmount: Double): String {
        val converted = if (currency == Currency.USD) {
            usdAmount
        } else {
            // A failed/stale exchange-rate fetch can leave the rate at 0.0, NaN,
            // or negative; fall back to 1.0 so prices never render as NaN/Infinity.
            val safeRate = usdToTargetRate.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            usdAmount * safeRate
        }
        return "${currency.symbol}${String.format(Locale.US, "%.2f", converted)}"
    }

    companion object {
        val Default = CurrencyFormatter(Currency.USD, 1.0)
    }
}

val LocalCurrencyFormatter = staticCompositionLocalOf { CurrencyFormatter.Default }
