package com.riftbound.packtally.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.ui.graphics.vector.ImageVector

enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Filled.Home),
    Scanner("scanner", "Scan", Icons.Filled.CameraAlt),
    Pack("pack", "Pack", Icons.Filled.Style),
    Box("box", "Box", Icons.Filled.Inventory2),
    Collection("collection", "Collection", Icons.Filled.Collections),
    Settings("settings", "Settings", Icons.Filled.Settings),
}
