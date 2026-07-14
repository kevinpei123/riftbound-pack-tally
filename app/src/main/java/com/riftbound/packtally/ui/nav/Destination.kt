package com.riftbound.packtally.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Filled.Home),
    Scanner("scanner", "Scan", Icons.Filled.CameraAlt),
    Current("current", "Current", Icons.AutoMirrored.Filled.ListAlt),
    Collection("collection", "Cards", Icons.Filled.Collections),
    Settings("settings", "Settings", Icons.Filled.Settings),
}
