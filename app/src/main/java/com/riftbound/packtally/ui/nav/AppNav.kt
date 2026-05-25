package com.riftbound.packtally.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.riftbound.packtally.feature.collection.CollectionScreen
import com.riftbound.packtally.feature.pack.PackScreen
import com.riftbound.packtally.feature.scanner.ScannerScreen
import com.riftbound.packtally.feature.settings.SettingsScreen

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { dest ->
                    val selected = backStackEntry?.destination?.hierarchy
                        ?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.navigateToTab(dest.route) },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Scanner.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.Scanner.route) { ScannerScreen() }
            composable(Destination.Pack.route) {
                PackScreen(
                    onNavigateToScanner = { navController.navigateToTab(Destination.Scanner.route) },
                )
            }
            composable(Destination.Collection.route) { CollectionScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
        }
    }
}

private fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
