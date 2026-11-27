package com.wineverdict.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import com.wineverdict.app.data.CrashLog
import com.wineverdict.app.data.Reminders
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
import com.wineverdict.app.ui.DrinksScreen
import com.wineverdict.app.ui.QuickStartScreen
import com.wineverdict.app.ui.SavedListScreen
import com.wineverdict.app.ui.SavedListsScreen
import com.wineverdict.app.ui.ScanScreen
import com.wineverdict.app.ui.SettingsScreen
import com.wineverdict.app.ui.TasteScreen
import com.wineverdict.app.ui.WineDetailScreen
import com.wineverdict.app.ui.WineEditorScreen
import com.wineverdict.app.ui.theme.WineVerdictTheme

class MainActivity : ComponentActivity() {
    /**
     * The list a reminder asked for, read from the intent that opened the screen.
     *
     * A notification that opened the cellar left the user to find their own way back to the list
     * they had just photographed, an hour earlier and in the middle of a meal. The id travels with
     * the intent instead, and the graph opens that list once it is up.
     */
    private val askedForList = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before anything else: an error during start-up is exactly the kind nobody can reproduce.
        CrashLog.install(applicationContext)
        AppGraph.init(applicationContext)
        askedForList.value = intent?.getStringExtra(Reminders.EXTRA_LIST)
        setContent {
            WineVerdictTheme {
                AppRoot(
                    askedForList = askedForList.value,
                    onListOpened = { askedForList.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        askedForList.value = intent.getStringExtra(Reminders.EXTRA_LIST)
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
private fun AppRoot(askedForList: String?, onListOpened: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }
    LaunchedEffect(askedForList) {
        val id = askedForList ?: return@LaunchedEffect
        navController.navigate("list/$id")
        onListOpened()
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                // Paper with one hairline above it, and the open tab stamped in claret: the bar
                // belongs to the page rather than sitting on top of it.
                Column {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp
                    ) {
                        // Past a certain size five labels stop fitting their columns and break
                        // mid-word ("Cella/r"), so the bar keeps the icons and moves each name into
                        // the icon's description, where TalkBack still reads it.
                        val iconsOnly = LocalDensity.current.fontScale >= 1.45f
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
                                icon = {
                                    Icon(
                                        tab.icon,
                                        contentDescription = if (iconsOnly) tab.label else null
                                    )
                                },
                                label = if (iconsOnly) {
                                    null
                                } else {
                                    { Text(tab.label) }
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.outline,
                                    unselectedTextColor = MaterialTheme.colorScheme.outline
                                )
                            )
                        }
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
                    },
                    onOpenLists = { navController.navigate("lists") },
                    onOpenDrinks = { navController.navigate("drinks") }
                )
            }
            composable("drinks") {
                DrinksScreen(onBack = { navController.popBackStack() })
            }
            composable("lists") {
                SavedListsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenList = { id -> navController.navigate("list/$id") }
                )
            }
            composable(
                route = "list/{listId}",
                arguments = listOf(navArgument("listId") { type = NavType.StringType })
            ) { entry ->
                SavedListScreen(
                    listId = entry.arguments?.getString("listId"),
                    onBack = { navController.popBackStack() }
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