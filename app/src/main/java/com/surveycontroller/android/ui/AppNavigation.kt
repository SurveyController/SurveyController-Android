package com.surveycontroller.android.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.surveycontroller.android.ui.screens.AboutScreen
import com.surveycontroller.android.ui.screens.AnswerRulesScreen
import com.surveycontroller.android.ui.screens.ConfigureScreen
import com.surveycontroller.android.ui.screens.HomeScreen
import com.surveycontroller.android.ui.screens.IpUsageScreen
import com.surveycontroller.android.ui.screens.RunScreen
import com.surveycontroller.android.ui.screens.ScanScreen
import com.surveycontroller.android.ui.screens.SettingsScreen

object Routes {
    const val HOME = "home"
    const val CONFIGURE = "configure"
    const val RUN = "run"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val SCAN = "scan"
    const val ANSWER_RULES = "answer_rules"
    const val IP_USAGE = "ip_usage"
}

private data class TabItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun AppNavigation(viewModel: MainViewModel = hiltViewModel(), navigationLabelVisible: Boolean = true) {
    val navController = rememberNavController()
    val tabs = listOf(
        TabItem(Routes.HOME, "工作台", Icons.Filled.Dashboard),
        TabItem(Routes.SETTINGS, "设置", Icons.Filled.Settings),
        TabItem(Routes.ABOUT, "关于", Icons.Filled.Info),
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = currentRoute in tabs.map { it.route }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 7.dp),
                        shape = RoundedCornerShape(25.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        NavigationBar(
                            modifier = Modifier.height(66.dp),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            tabs.forEach { tab ->
                                val selected = backStack?.destination?.hierarchy?.any { it.route == tab.route } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(tab.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = if (navigationLabelVisible) ({
                                        Text(tab.label, style = MaterialTheme.typography.labelMedium)
                                    }) else null,
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { inner ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(inner),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    viewModel = viewModel,
                    onConfigure = { navController.navigate(Routes.CONFIGURE) },
                    onScan = { navController.navigate(Routes.SCAN) },
                )
            }
            composable(Routes.CONFIGURE) {
                ConfigureScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onRun = {
                        viewModel.startRun {
                            navController.navigate(Routes.RUN)
                        }
                    },
                    onEditRules = { navController.navigate(Routes.ANSWER_RULES) },
                )
            }
            composable(Routes.ANSWER_RULES) {
                AnswerRulesScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Routes.RUN) {
                RunScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    onOpenIpUsage = { navController.navigate(Routes.IP_USAGE) },
                )
            }
            composable(Routes.ABOUT) { AboutScreen(viewModel = viewModel) }
            composable(Routes.IP_USAGE) { IpUsageScreen(viewModel = viewModel, onBack = { navController.popBackStack() }) }
            composable(Routes.SCAN) {
                ScanScreen(
                    onResult = { url ->
                        viewModel.setUrl(url)
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
