package com.lian.plus.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lian.plus.R
import com.lian.plus.ui.screens.ChatScreen
import com.lian.plus.ui.screens.DeviceScreen
import com.lian.plus.ui.screens.ImageScreen
import com.lian.plus.ui.screens.ModelsScreen
import com.lian.plus.ui.screens.ServerScreen
import com.lian.plus.ui.screens.SettingsScreen

private data class Tab(val route: String, val labelRes: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab("chat", R.string.tab_chat, Icons.Default.Chat),
    Tab("models", R.string.tab_models, Icons.Default.Storage),
    Tab("images", R.string.tab_images, Icons.Default.Image),
    Tab("device", R.string.tab_device, Icons.Default.Memory),
    Tab("server", R.string.tab_server, Icons.Default.Dns),
    Tab("settings", R.string.tab_settings, Icons.Default.Settings),
)

@Composable
fun LianRoot() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                // Keep one instance of each tab and restore its
                                // scroll position rather than rebuilding it.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "chat",
            modifier = Modifier.padding(padding),
        ) {
            composable("chat") { ChatScreen() }
            composable("models") { ModelsScreen() }
            composable("images") { ImageScreen() }
            composable("device") { DeviceScreen() }
            composable("server") { ServerScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
