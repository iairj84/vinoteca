package com.vinoteca.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.vinoteca.app.ui.AdvancedTasteScreen
import com.vinoteca.app.ui.CellarScreen
import com.vinoteca.app.ui.ChatScreen
import com.vinoteca.app.ui.QuickStartScreen
import com.vinoteca.app.ui.ScanScreen
import com.vinoteca.app.ui.SettingsScreen
import com.vinoteca.app.ui.TasteScreen
import com.vinoteca.app.ui.WineDetailScreen
import com.vinoteca.app.ui.WineEditorScreen
import com.vinoteca.app.ui.theme.VinotecaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(applicationContext)
        setContent {
            VinotecaTheme {
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
                    onAddWine = { navController.navigate("editor") }
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
                    onStartQuickStart = { navController.navigate("quickstart") },
                    onOpenAdvanced = { navController.navigate("advanced-taste") }
                )
            }
            composable("settings") {
                SettingsScreen(onOpenAdvancedTaste = { navController.navigate("advanced-taste") })
            }
            composable("quickstart") {
                QuickStartScreen(onDone = { navController.popBackStack() })
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