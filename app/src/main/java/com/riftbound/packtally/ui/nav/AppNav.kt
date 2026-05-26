package com.riftbound.packtally.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.riftbound.packtally.App
import com.riftbound.packtally.core.pricing.QuotaEvent
import com.riftbound.packtally.feature.backup.BackupScreen
import com.riftbound.packtally.feature.box.BoxScreen
import com.riftbound.packtally.feature.collection.CollectionScreen
import com.riftbound.packtally.feature.home.HomeScreen
import com.riftbound.packtally.feature.pack.PackScreen
import com.riftbound.packtally.feature.quickscan.QuickScanScreen
import com.riftbound.packtally.feature.scanner.ScannerScreen
import com.riftbound.packtally.feature.settings.SettingsScreen

@Composable
fun AppNav() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val app = LocalContext.current.applicationContext as App

    val snackbarHostState = remember { SnackbarHostState() }

    // CHOICE: confirm dialog state is host-local — tracker emits PromptConfirm on
    // every successful network call at ≥95%, but UI shows at most one dialog at
    // a time. Once user picks an action, dialog dismisses; future events re-open
    // it unless the user picked Cache-only (in which case future network calls
    // fail without firing the event).
    var quotaConfirm by remember { mutableStateOf<QuotaEvent.PromptConfirm?>(null) }

    LaunchedEffect(app.quotaTracker) {
        app.quotaTracker.events.collect { event ->
            when (event) {
                is QuotaEvent.NearLimit -> {
                    snackbarHostState.showSnackbar(
                        "API quota at ${(event.used * 100 / event.limit)}% " +
                            "(${event.used}/${event.limit}). Cache will be used where possible.",
                    )
                }
                is QuotaEvent.PromptConfirm -> {
                    if (quotaConfirm == null) quotaConfirm = event
                }
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            startDestination = Destination.Home.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Destination.Home.route) {
                HomeScreen(
                    onNavigateToScanner = { navController.navigateToTab(Destination.Scanner.route) },
                )
            }
            composable(Destination.QuickScan.route) { QuickScanScreen() }
            composable(Destination.Scanner.route) { ScannerScreen() }
            composable(Destination.Pack.route) {
                PackScreen(
                    onNavigateToScanner = { navController.navigateToTab(Destination.Scanner.route) },
                )
            }
            composable(Destination.Box.route) { BoxScreen() }
            composable(Destination.Collection.route) { CollectionScreen() }
            composable(Destination.Settings.route) {
                SettingsScreen(onNavigateToBackup = { navController.navigate("backup") })
            }
            composable("backup") { BackupScreen() }
        }
    }

    quotaConfirm?.let { confirm ->
        AlertDialog(
            onDismissRequest = { quotaConfirm = null },
            title = { Text("API quota almost exhausted") },
            text = {
                Text(
                    "You've used ${confirm.used}/${confirm.limit} tcgapi.dev " +
                        "requests today. The counter resets at UTC midnight.\n\n" +
                        "Cache hits don't count, so you can keep scanning cards " +
                        "already in cache without burning more budget.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    app.quotaTracker.setUseCachedOnly(true)
                    quotaConfirm = null
                }) { Text("Use cached only") }
            },
            dismissButton = {
                TextButton(
                    onClick = { quotaConfirm = null },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) { Text("Continue") }
            },
        )
    }
}

private fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
