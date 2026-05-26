package com.riftbound.packtally.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.ui.graphics.vector.ImageVector

// Quick Scan is a top-level nav destination because it's a primary daily-use
// case. Seven tabs is cramped on a 360dp phone but workable on the P30 Pro's
// 19.5:9 screen with short labels.
enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Filled.Home),
    QuickScan("quick-scan", "Quick", Icons.Filled.AddAPhoto),
    Scanner("scanner", "Scan", Icons.Filled.CameraAlt),
    Pack("pack", "Pack", Icons.Filled.Style),
    Box("box", "Box", Icons.Filled.Inventory2),
    Collection("collection", "Cards", Icons.Filled.Collections),
    Settings("settings", "Set", Icons.Filled.Settings),
}
