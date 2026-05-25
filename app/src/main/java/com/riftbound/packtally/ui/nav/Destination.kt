package com.riftbound.packtally.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.ui.graphics.vector.ImageVector

enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Scanner("scanner", "Scan", Icons.Filled.CameraAlt),
    Pack("pack", "Pack", Icons.Filled.Inventory2),
    Collection("collection", "Collection", Icons.Filled.Style),
    Settings("settings", "Settings", Icons.Filled.Settings),
}
