package com.wineverdict.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.wineverdict.app.data.CrashLog
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wineverdict.app.ui.AdvancedTasteScreen
import com.wineverdict.app.ui.CellarScreen
import com.wineverdict.app.ui.ChatScreen
import com.wineverdict.app.ui.QuickStartScreen
import com.wineverdict.app.ui.ScanScreen
import com.wineverdict.app.ui.SettingsScreen
import com.wineverdict.app.ui.TasteScreen
import com.wineverdict.app.ui.WineDetailScreen
import com.wineverdict.app.ui.WineEditorScreen
import com.wineverdict.app.ui.theme.WineVerdictTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything else: an error during start-up is exactly the kind nobody can reproduce.
        CrashLog.install(applicationContext)
        AppGraph.init(applicationContext)
        setContent {
            WineVerdictTheme {
                AppRoot()
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("cellar", "Cellar", Icons.Filled.LocalBar),
    Tab("scan", "Scan", Icons.Filled.CameraAlt),
    Tab("chat", "Ask", Icons.Filled.Chat),
    Tab("taste", "Taste", Icons.Filled.Psychology),
    Tab("settings", "Settings", Icons.Filled.Settings)
)

@Composable
private fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "cellar",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("cellar") {
                CellarScreen(
                    onOpenWine = { id -> navController.navigate("wine/$id") },
                    onAddWine = { navController.navigate("editor") },
                    // Adding by name can dead-end (the web names no such wine), so the scan is one
                    // tap away from the dialog rather than a screen the user has to find.
                    onScan = {
                        navController.navigate("scan") {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable("scan") {
                ScanScreen(onReview = { navController.navigate("editor") })
            }
            composable("chat") {
                ChatScreen()
            }
            composable("taste") {
                TasteScreen(
                    onStartQuickStart = { navController.navigate("quickstart?extras=0") },
                    onAddDetail = { navController.navigate("quickstart?extras=1") },
                    onOpenAdvanced = { navController.navigate("advanced-taste") }
                )
            }
            composable("settings") {
                SettingsScreen(onOpenAdvancedTaste = { navController.navigate("advanced-taste") })
            }
            composable(
                route = "quickstart?extras={extras}",
                arguments = listOf(navArgument("extras") { defaultValue = "0" })
            ) { entry ->
                QuickStartScreen(
                    onDone = { navController.popBackStack() },
                    startAtExtras = entry.arguments?.getString("extras") == "1"
                )
            }
            composable("advanced-taste") {
                AdvancedTasteScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = "wine/{wineId}",
                arguments = listOf(navArgument("wineId") { type = NavType.StringType })
            ) { entry ->
                WineDetailScreen(
                    wineId = entry.arguments?.getString("wineId"),
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate("editor?wineId=$id") }
                )
            }
            composable(
                route = "editor?wineId={wineId}",
                arguments = listOf(
                    navArgument("wineId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { entry ->
                WineEditorScreen(
                    wineId = entry.arguments?.getString("wineId"),
                    onDone = { navController.popBackStack() }
                )
            }
        }
    }
}