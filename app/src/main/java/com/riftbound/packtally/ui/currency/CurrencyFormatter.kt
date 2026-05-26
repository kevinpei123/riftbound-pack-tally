package com.riftbound.packtally.ui.currency

import androidx.compose.runtime.staticCompositionLocalOf
import com.riftbound.packtally.core.settings.Currency

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
        val converted = if (currency == Currency.USD) usdAmount else usdAmount * usdToTargetRate
        return "${currency.symbol}${"%.2f".format(converted)}"
    }

    companion object {
        val Default = CurrencyFormatter(Currency.USD, 1.0)
    }
}

val LocalCurrencyFormatter = staticCompositionLocalOf { CurrencyFormatter.Default }
