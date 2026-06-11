package ai.deepmost.triage.ui.nav

import ai.deepmost.triage.R
import ai.deepmost.triage.di.AppContainer
import ai.deepmost.triage.ui.screens.AnalyticsScreen
import ai.deepmost.triage.ui.screens.CaptureScreen
import ai.deepmost.triage.ui.screens.FleetScreen
import ai.deepmost.triage.ui.screens.InspectHomeScreen
import ai.deepmost.triage.ui.screens.LoginScreen
import ai.deepmost.triage.ui.screens.OnboardingScreen
import ai.deepmost.triage.ui.screens.ReviewSignScreen
import ai.deepmost.triage.ui.screens.SettingsScreen
import ai.deepmost.triage.ui.screens.VehicleHistoryScreen
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch

object Routes {
    const val ONBOARDING = "onboarding"
    const val LOGIN = "login"
    const val HOME = "home"
    const val CAPTURE = "capture/{inspectionId}"
    const val REVIEW = "review/{inspectionId}"
    const val HISTORY = "history/{vehicleId}"
    const val FLEET = "fleet"
    const val ANALYTICS = "analytics"
    const val SETTINGS = "settings"

    fun capture(id: String) = "capture/$id"
    fun review(id: String) = "review/$id"
    fun history(vehicleId: String) = "history/$vehicleId"
}

private val BOTTOM_ROUTES = setOf(Routes.HOME, Routes.FLEET, Routes.SETTINGS)

@Composable
fun TriageNavHost(container: AppContainer, navController: NavHostController = rememberNavController()) {
    val scope = rememberCoroutineScope()
    val session by container.authRepository.session.collectAsState(initial = null)
    val settings by container.settingsRepository.settings.collectAsState(initial = container.currentSettings)

    val start = when {
        !settings.onboardingDone -> Routes.ONBOARDING
        session == null -> Routes.LOGIN
        else -> Routes.HOME
    }

    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val showBottomBar = currentRoute in BOTTOM_ROUTES

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.HOME,
                        onClick = { navTo(navController, Routes.HOME) },
                        icon = { Icon(Icons.Filled.DirectionsCar, contentDescription = null) },
                        label = { Text(stringResource(R.string.inspect)) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.FLEET,
                        onClick = { navTo(navController, Routes.FLEET) },
                        icon = { Icon(Icons.Filled.BarChart, contentDescription = null) },
                        label = { Text(stringResource(R.string.fleet)) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SETTINGS,
                        onClick = { navTo(navController, Routes.SETTINGS) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.settings)) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(navController = navController, startDestination = start, modifier = Modifier.padding(padding)) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(onDone = {
                    scope.launch { container.settingsRepository.setOnboardingDone(true) }
                    navController.navigate(Routes.LOGIN) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                })
            }
            composable(Routes.LOGIN) {
                LoginScreen(container = container, onLoggedIn = {
                    navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
                })
            }
            composable(Routes.HOME) {
                InspectHomeScreen(
                    container = container,
                    onStartCapture = { navController.navigate(Routes.capture(it)) },
                    onReview = { navController.navigate(Routes.review(it)) },
                    onHistory = { navController.navigate(Routes.history(it)) },
                    onFleet = { navController.navigate(Routes.FLEET) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                    onLogout = {
                        container.authRepository.logout()
                        navController.navigate(Routes.LOGIN) { popUpTo(Routes.HOME) { inclusive = true } }
                    }
                )
            }
            composable(
                Routes.CAPTURE,
                arguments = listOf(navArgument("inspectionId") { type = NavType.StringType })
            ) { backStack ->
                val id = backStack.arguments?.getString("inspectionId") ?: return@composable
                CaptureScreen(
                    container = container,
                    inspectionId = id,
                    onFinishCapture = { navController.navigate(Routes.review(id)) { popUpTo(Routes.HOME) } },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                Routes.REVIEW,
                arguments = listOf(navArgument("inspectionId") { type = NavType.StringType })
            ) { backStack ->
                val id = backStack.arguments?.getString("inspectionId") ?: return@composable
                ReviewSignScreen(
                    container = container,
                    inspectionId = id,
                    onFinalized = { vehicleId -> navController.navigate(Routes.history(vehicleId)) { popUpTo(Routes.HOME) } },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                Routes.HISTORY,
                arguments = listOf(navArgument("vehicleId") { type = NavType.StringType })
            ) { backStack ->
                val vehicleId = backStack.arguments?.getString("vehicleId") ?: return@composable
                VehicleHistoryScreen(
                    container = container,
                    vehicleId = vehicleId,
                    onReview = { navController.navigate(Routes.review(it)) },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.FLEET) {
                FleetScreen(
                    container = container,
                    onHistory = { navController.navigate(Routes.history(it)) },
                    onReview = { navController.navigate(Routes.review(it)) },
                    onCapture = { navController.navigate(Routes.capture(it)) },
                    onAnalytics = { navController.navigate(Routes.ANALYTICS) },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.ANALYTICS) {
                AnalyticsScreen(container = container, onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(container = container, onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun navTo(navController: NavHostController, route: String) {
    navController.navigate(route) {
        popUpTo(Routes.HOME)
        launchSingleTop = true
    }
}
