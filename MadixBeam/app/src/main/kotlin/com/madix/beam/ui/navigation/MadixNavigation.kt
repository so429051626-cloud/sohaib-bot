package com.madix.beam.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.madix.beam.core.AppContainer
import com.madix.beam.ui.history.HistoryDetailScreen
import com.madix.beam.ui.history.HistoryScreen
import com.madix.beam.ui.home.HomeScreen
import com.madix.beam.ui.home.QuickAction
import com.madix.beam.ui.lock.LockScreen
import com.madix.beam.ui.onboarding.OnboardingScreen
import com.madix.beam.ui.receive.ReceivePairingScreen
import com.madix.beam.ui.receive.ReceiveResultScreen
import com.madix.beam.ui.receive.ReceiveScanScreen
import com.madix.beam.ui.receive.ReceiveViewModel
import com.madix.beam.ui.send.BroadcastScreen
import com.madix.beam.ui.send.SendComposeScreen
import com.madix.beam.ui.send.SendPairingScreen
import com.madix.beam.ui.send.SendViewModel
import com.madix.beam.ui.settings.AboutScreen
import com.madix.beam.ui.settings.PrivacyScreen
import com.madix.beam.ui.settings.SettingsScreen
import com.madix.beam.ui.splash.SplashScreen
import com.madix.beam.ui.theme.MadixTheme
import kotlinx.coroutines.flow.MutableStateFlow

object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"

    const val SEND_GRAPH = "send"
    const val SEND_COMPOSE = "send/compose"
    const val SEND_PAIR = "send/pair"
    const val SEND_BROADCAST = "send/broadcast"

    const val RECEIVE_GRAPH = "receive"
    const val RECEIVE_PAIR = "receive/pair"
    const val RECEIVE_SCAN = "receive/scan"
    const val RECEIVE_RESULT = "receive/result"

    const val HISTORY = "history"
    const val HISTORY_DETAIL = "history/{id}"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val PRIVACY = "privacy"

    fun historyDetail(id: String) = "history/$id"
}

/** One-shot request carried from the home screen into the send or receive flow. */
object PendingAction {
    val quickAction = MutableStateFlow<QuickAction?>(null)
}

@Composable
inline fun <reified VM : ViewModel> NavBackStackEntry.graphViewModel(
    navController: NavHostController,
    graphRoute: String,
    noinline factory: () -> VM,
): VM {
    val parent = remember(this) { navController.getBackStackEntry(graphRoute) }
    return viewModel(viewModelStoreOwner = parent, initializer = { factory() })
}

@Composable
fun MadixRoot(container: AppContainer) {
    val locked by container.appLock.locked.collectAsStateWithLifecycle()
    MadixTheme(darkTheme = isSystemInDarkTheme()) {
        val navController = rememberNavController()
        val pendingShare by container.pendingShare.collectAsStateWithLifecycle()
        val backStack by navController.currentBackStackEntryAsState()
        val currentRoute = backStack?.destination?.route

        Box(Modifier.fillMaxSize()) {
            MadixNavHost(navController, container)
            if (locked) {
                LockScreen(onUnlocked = container.appLock::unlock)
            }
        }

        LaunchedEffect(pendingShare, locked, currentRoute) {
            val ready = currentRoute != null && currentRoute != Routes.SPLASH && currentRoute != Routes.ONBOARDING
            if (pendingShare != null && !locked && ready && !currentRoute!!.startsWith("send/")) {
                navController.navigate(Routes.SEND_GRAPH) {
                    popUpTo(Routes.HOME)
                    launchSingleTop = true
                }
            }
        }
    }
}

private const val TRANSITION_MILLIS = 320

@Composable
private fun MadixNavHost(navController: NavHostController, container: AppContainer) {
    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        enterTransition = {
            fadeIn(tween(TRANSITION_MILLIS)) +
                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(TRANSITION_MILLIS), initialOffset = { it / 6 })
        },
        exitTransition = { fadeOut(tween(TRANSITION_MILLIS / 2)) },
        popEnterTransition = { fadeIn(tween(TRANSITION_MILLIS)) },
        popExitTransition = {
            fadeOut(tween(TRANSITION_MILLIS / 2)) +
                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(TRANSITION_MILLIS), targetOffset = { it / 6 })
        },
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                container = container,
                onFinished = { onboardingDone ->
                    navController.navigate(if (onboardingDone) Routes.HOME else Routes.ONBOARDING) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                container = container,
                onFinished = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.HOME) {
            HomeScreen(
                container = container,
                onSend = { navController.navigate(Routes.SEND_GRAPH) },
                onReceive = { navController.navigate(Routes.RECEIVE_GRAPH) },
                onQuickAction = { action ->
                    PendingAction.quickAction.value = action
                    if (action == QuickAction.SCAN) {
                        navController.navigate(Routes.RECEIVE_GRAPH)
                    } else {
                        navController.navigate(Routes.SEND_GRAPH)
                    }
                },
                onHistory = { navController.navigate(Routes.HISTORY) },
                onOpenRecord = { navController.navigate(Routes.historyDetail(it)) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        navigation(startDestination = Routes.SEND_COMPOSE, route = Routes.SEND_GRAPH) {
            composable(Routes.SEND_COMPOSE) { entry ->
                val vm = entry.graphViewModel(navController, Routes.SEND_GRAPH) { SendViewModel(container) }
                SendComposeScreen(
                    viewModel = vm,
                    container = container,
                    onBack = { navController.popBackStack() },
                    onPairingRequired = { navController.navigate(Routes.SEND_PAIR) },
                    onBroadcast = { navController.navigate(Routes.SEND_BROADCAST) },
                )
            }
            composable(Routes.SEND_PAIR) { entry ->
                val vm = entry.graphViewModel(navController, Routes.SEND_GRAPH) { SendViewModel(container) }
                SendPairingScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onPaired = {
                        navController.navigate(Routes.SEND_BROADCAST) {
                            popUpTo(Routes.SEND_COMPOSE)
                        }
                    },
                )
            }
            composable(Routes.SEND_BROADCAST) { entry ->
                val vm = entry.graphViewModel(navController, Routes.SEND_GRAPH) { SendViewModel(container) }
                BroadcastScreen(
                    viewModel = vm,
                    container = container,
                    onBack = { navController.popBackStack(Routes.SEND_COMPOSE, inclusive = false) },
                    onFinished = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        }
                    },
                )
            }
        }

        navigation(startDestination = Routes.RECEIVE_PAIR, route = Routes.RECEIVE_GRAPH) {
            composable(Routes.RECEIVE_PAIR) { entry ->
                val vm = entry.graphViewModel(navController, Routes.RECEIVE_GRAPH) { ReceiveViewModel(container) }
                ReceivePairingScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onStartScanning = { navController.navigate(Routes.RECEIVE_SCAN) },
                )
            }
            composable(Routes.RECEIVE_SCAN) { entry ->
                val vm = entry.graphViewModel(navController, Routes.RECEIVE_GRAPH) { ReceiveViewModel(container) }
                ReceiveScanScreen(
                    viewModel = vm,
                    container = container,
                    onClose = { navController.popBackStack() },
                    onCompleted = {
                        // The pairing and scanning entries stay below so that the graph-scoped
                        // view model survives; the result screen returns home on back.
                        navController.navigate(Routes.RECEIVE_RESULT) { launchSingleTop = true }
                    },
                )
            }
            composable(Routes.RECEIVE_RESULT) { entry ->
                val vm = entry.graphViewModel(navController, Routes.RECEIVE_GRAPH) { ReceiveViewModel(container) }
                ReceiveResultScreen(
                    viewModel = vm,
                    container = container,
                    onDone = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        }
                    },
                )
            }
        }

        composable(Routes.HISTORY) {
            HistoryScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onOpen = { navController.navigate(Routes.historyDetail(it)) },
            )
        }

        composable(
            route = Routes.HISTORY_DETAIL,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            HistoryDetailScreen(
                container = container,
                recordId = entry.arguments?.getString("id").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                container = container,
                onBack = { navController.popBackStack() },
                onAbout = { navController.navigate(Routes.ABOUT) },
                onPrivacy = { navController.navigate(Routes.PRIVACY) },
            )
        }

        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() }, onPrivacy = { navController.navigate(Routes.PRIVACY) })
        }

        composable(Routes.PRIVACY) {
            PrivacyScreen(onBack = { navController.popBackStack() })
        }
    }
}
