package com.riftbound.packtally

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.riftbound.packtally.ui.nav.AppNav
import com.riftbound.packtally.ui.theme.RiftboundPackTallyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RiftboundPackTallyTheme {
                AppNav()
            }
        }
    }
}
