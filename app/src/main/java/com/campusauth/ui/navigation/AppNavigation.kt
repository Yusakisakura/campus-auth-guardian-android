package com.campusauth.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.campusauth.ui.screen.AboutScreen
import com.campusauth.ui.screen.LogsScreen
import com.campusauth.ui.screen.SettingsScreen
import com.campusauth.ui.screen.StatusScreen
import com.campusauth.ui.screen.WizardScreen

enum class Screen(val route: String, val label: String, val icon: ImageVector) {
    Status("status", "状态", Icons.Default.Dashboard),
    Settings("settings", "设置", Icons.Default.Settings),
    Logs("logs", "日志", Icons.AutoMirrored.Filled.ListAlt),
    About("about", "关于", Icons.Default.Info),
}

private val tabEnterTransition: EnterTransition =
    fadeIn(spring()) + slideInHorizontally(spring()) { it / 4 }

private val tabExitTransition: ExitTransition =
    fadeOut(spring()) + slideOutHorizontally(spring()) { -it / 4 }

private val tabPopEnterTransition: EnterTransition =
    fadeIn(spring()) + slideInHorizontally(spring()) { -it / 4 }

private val tabPopExitTransition: ExitTransition =
    fadeOut(spring()) + slideOutHorizontally(spring()) { it / 4 }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(needsWizard: Boolean) {
    val navController = rememberNavController()
    var showWizard by remember { mutableStateOf(needsWizard) }

    // Full-screen wizard overlay with fade transition
    AnimatedVisibility(
        visible = showWizard,
        enter = fadeIn(spring()),
        exit = fadeOut(spring()),
    ) {
        WizardScreen(
            onComplete = { showWizard = false }
        )
    }
    if (showWizard) return

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                Screen.entries.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentRoute == screen.route,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Status.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { tabEnterTransition },
            exitTransition = { tabExitTransition },
            popEnterTransition = { tabPopEnterTransition },
            popExitTransition = { tabPopExitTransition },
        ) {
            composable(Screen.Status.route)   { StatusScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
            composable(Screen.Logs.route)     { LogsScreen() }
            composable(Screen.About.route)    { AboutScreen() }
        }
    }
}
