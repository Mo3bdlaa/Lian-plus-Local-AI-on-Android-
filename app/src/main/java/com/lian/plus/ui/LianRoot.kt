package com.lian.plus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lian.plus.core.LianRuntime
import com.lian.plus.ui.screens.AboutScreen
import com.lian.plus.ui.screens.ChatScreen
import com.lian.plus.ui.screens.DeviceScreen
import com.lian.plus.ui.screens.HomeScreen
import com.lian.plus.ui.screens.ImageScreen
import com.lian.plus.ui.screens.ModelsScreen
import com.lian.plus.ui.screens.OnboardingScreen
import com.lian.plus.ui.screens.PlaygroundScreen
import com.lian.plus.ui.screens.ServerScreen
import com.lian.plus.ui.screens.SettingsScreen
import com.lian.plus.ui.screens.SplashScreen
import com.lian.plus.ui.theme.Lian
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector)

/**
 * Four tabs, matching the product design. Everything else — settings, the
 * playground, the device report, the API server — hangs off Home or Settings,
 * because a nav bar with six entries stops being a nav bar.
 */
private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("chat", "Chat", Icons.Default.Chat),
    Tab("create", "Create", Icons.Default.AutoAwesome),
    Tab("models", "Models", Icons.Default.Storage),
)

@Composable
fun LianRoot() {
    val context = LocalContext.current
    val runtime = remember { LianRuntime.get(context) }
    val settings by runtime.settingsStore.settings
        .collectAsState(initial = com.lian.plus.data.AppSettings())

    var phase by remember { mutableStateOf(Phase.SPLASH) }

    // The splash decides where to go only once the saved settings have actually
    // arrived; showing onboarding to a returning user would be worse than
    // holding the splash for another frame.
    when (phase) {
        Phase.SPLASH -> {
            SplashScreen(
                onFinished = {
                    phase = if (settings.onboardingDone) Phase.MAIN else Phase.ONBOARDING
                },
            )
            return
        }

        Phase.ONBOARDING -> {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            OnboardingScreen(
                onGetStarted = {
                    scope.launch {
                        runtime.settingsStore.update { it.copy(onboardingDone = true) }
                    }
                    phase = Phase.MAIN
                },
            )
            return
        }

        Phase.MAIN -> Unit
    }

    MainScaffold()
}

private enum class Phase { SPLASH, ONBOARDING, MAIN }

@Composable
private fun MainScaffold() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination
    val onTab = tabs.any { tab -> currentRoute?.hierarchy?.any { it.route == tab.route } == true }

    Scaffold(
        containerColor = Lian.Background,
        bottomBar = {
            if (onTab) {
                NavigationBar(
                    containerColor = Lian.Surface,
                    tonalElevation = NavigationBarDefaults.Elevation,
                ) {
                    tabs.forEach { tab ->
                        val selected =
                            currentRoute?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Lian.TextPrimary,
                                selectedTextColor = Lian.TextPrimary,
                                indicatorColor = Lian.Purple.copy(alpha = 0.30f),
                                unselectedIconColor = Lian.TextMuted,
                                unselectedTextColor = Lian.TextMuted,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Lian.Background)
                .padding(padding),
        ) {
            NavHost(navController = navController, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        onNavigate = { route ->
                            if (tabs.any { it.route == route }) navController.switchTab(route)
                            else navController.navigate(route)
                        },
                        onOpenChat = { chatId ->
                            navController.switchTab("chat")
                            ChatDeepLink.pendingChatId = chatId
                        },
                    )
                }
                composable("chat") { ChatScreen() }
                composable("create") { ImageScreen() }
                composable("models") { ModelsScreen() }

                composable("settings") {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onNavigate = { navController.navigate(it) },
                    )
                }
                composable("device") { DeviceScreen(onBack = { navController.popBackStack() }) }
                composable("server") { ServerScreen(onBack = { navController.popBackStack() }) }
                composable("playground") {
                    PlaygroundScreen(onBack = { navController.popBackStack() })
                }
                composable("about") {
                    AboutScreen(
                        onBack = { navController.popBackStack() },
                        onOpenDevice = { navController.navigate("device") },
                    )
                }
            }
        }
    }
}

/** Keeps one instance of each tab alive and restores its scroll position. */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Lets Home ask the chat tab to open a particular conversation.
 *
 * A nav argument would recreate the chat screen on every tab switch and throw
 * away its state; this hands over the id once and the screen clears it.
 */
object ChatDeepLink {
    @Volatile
    var pendingChatId: Long? = null

    fun consume(): Long? {
        val id = pendingChatId
        pendingChatId = null
        return id
    }
}
