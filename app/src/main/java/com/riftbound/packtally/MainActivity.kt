package com.riftbound.packtally

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riftbound.packtally.core.settings.AppSettings
import com.riftbound.packtally.ui.currency.CurrencyFormatter
import com.riftbound.packtally.ui.currency.LocalCurrencyFormatter
import com.riftbound.packtally.ui.nav.AppNav
import com.riftbound.packtally.ui.theme.RiftboundPackTallyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as App
        setContent {
            RiftboundPackTallyTheme {
                val settings by app.settingsRepository.settings
                    .collectAsStateWithLifecycle(initialValue = AppSettings())
                val formatter = remember(settings.currency, settings.usdToTargetRate) {
                    CurrencyFormatter(settings.currency, settings.usdToTargetRate)
                }
                CompositionLocalProvider(LocalCurrencyFormatter provides formatter) {
                    AppNav()
                }
            }
        }
    }
}
