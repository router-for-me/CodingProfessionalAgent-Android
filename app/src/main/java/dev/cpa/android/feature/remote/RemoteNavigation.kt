package dev.cpa.android.feature.remote

import dev.cpa.android.core.push.TaskNotificationTarget
import dev.cpa.android.data.repository.ConnectionPhase
import kotlinx.coroutines.flow.first
import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import dev.cpa.android.core.designsystem.Cpa
import dev.cpa.android.data.repository.RemoteDesktopRepository

fun remoteThreadPath(connectionId: String, sessionId: String) = "remote/${Uri.encode(connectionId)}/${Uri.encode(sessionId)}"
fun remoteSubAgentPath(connectionId: String, parentSessionId: String, agentId: String) =
    "subagent/${Uri.encode(connectionId)}/${Uri.encode(parentSessionId)}/${Uri.encode(agentId)}"

private const val HOME = "remoteHome"

@Composable
fun RemoteNavigation(remote: RemoteDesktopRepository, notification: TaskNotificationTarget? = null,
    onConsumed: () -> Unit = {}, resolveNotification: (String) -> String? = { null }) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val homePreferences = remember(context) { dev.cpa.android.core.storage.AndroidHomePreferences(context) }
    val nav = rememberNavController()
    val homeFactory = remember(remote, homePreferences) { viewModelFactory { initializer { RemoteHomeViewModel(createSavedStateHandle(), remote, homePreferences) } } }
    val threadFactory = remember(remote) { viewModelFactory { initializer { RemoteThreadViewModel(createSavedStateHandle(), remote) } } }
    @Composable fun homeVm(entry: NavBackStackEntry): RemoteHomeViewModel {
        val owner = remember(entry) { runCatching { nav.getBackStackEntry(HOME) }.getOrDefault(entry) }
        return viewModel(owner, factory = homeFactory)
    }
    fun openThread(connection: String, session: String) {
        val current = nav.currentBackStackEntry
        if (current?.destination?.route == "remote/{connectionId}/{sessionId}" &&
            current.arguments?.getString("connectionId") == connection && current.arguments?.getString("sessionId") == session) return
        // The same route pattern with new arguments needs a fresh SavedStateHandle and thread ViewModel.
        nav.navigate(remoteThreadPath(connection, session))
    }
    LaunchedEffect(notification) {
        val target = notification ?: return@LaunchedEffect
        val local = resolveNotification(target.route)
        if (local != null && remote.prepareNotificationTarget(local)) {
            val connection = remote.connections.first { rows ->
                val row = rows.find { it.id == local }
                row == null || !row.enabled || row.phase !in setOf(ConnectionPhase.Disconnected, ConnectionPhase.Connecting)
            }.find { it.id == local }
            if (connection?.enabled == true && resolveNotification(target.route) == local) openThread(local, target.session)
        }
        onConsumed()
    }
    fun openNewThread() = nav.navigate("newThread") { launchSingleTop = true }
    Box(Modifier.fillMaxSize().background(Cpa.colors.background)) {
        NavHost(navController = nav, startDestination = HOME,
            enterTransition = { slideInHorizontally(tween(320)) { it } + fadeIn(tween(200)) },
            exitTransition = { slideOutHorizontally(tween(320)) { -it / 4 } + fadeOut(tween(320), .6f) },
            popEnterTransition = { slideInHorizontally(tween(320)) { -it / 4 } + fadeIn(tween(320), .6f) },
            popExitTransition = { slideOutHorizontally(tween(320)) { it } + fadeOut(tween(200)) }) {
            composable(HOME) { entry ->
                val vm = homeVm(entry)
                RemoteHomeRoute(vm, ::openThread, onNewThread = ::openNewThread)
            }
            composable("newThread") { entry ->
                val home = homeVm(entry)
                val icons by home.connectionIcons.collectAsStateWithLifecycle()
                NewThreadRoute(home.newThread, icons, onBack = { nav.popBackStack() }, onOpened = { connection, session ->
                    // Creation succeeded: the new chat replaces the form, like iOS Router.replaceTop.
                    nav.navigate(remoteThreadPath(connection, session)) { popUpTo("newThread") { inclusive = true } }
                }, onOpenExisting = { connection, session -> nav.popBackStack(); openThread(connection, session) })
            }
            composable("remote/{connectionId}/{sessionId}", arguments = listOf(navArgument("connectionId") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType })) { entry ->
                val vm: RemoteThreadViewModel = viewModel(factory = threadFactory)
                val home = homeVm(entry)
                val icons by home.connectionIcons.collectAsStateWithLifecycle()
                RemoteThreadRoute(vm, onForked = { target -> nav.navigate(remoteThreadPath(vm.connectionId, target)) },
                    onNewThread = { project -> home.newThread.open(vm.connectionId, project, autoProject = project == null); openNewThread() },
                    onSubAgent = { agent -> nav.navigate(remoteSubAgentPath(vm.connectionId, agent.parentSessionId, agent.id)) },
                    icons = icons) { nav.popBackStack() }
            }
            composable("subagent/{connectionId}/{sessionId}/{agentId}", arguments = listOf(navArgument("connectionId") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType }, navArgument("agentId") { type = NavType.StringType })) { entry ->
                val vm: RemoteThreadViewModel = viewModel(factory = threadFactory)
                val home = homeVm(entry)
                val icons by home.connectionIcons.collectAsStateWithLifecycle()
                val agentId = remember(entry) { entry.arguments?.getString("agentId").orEmpty() }
                SubAgentRoute(vm, agentId, icons, onSubAgent = { agent -> nav.navigate(remoteSubAgentPath(vm.connectionId, agent.parentSessionId, agent.id)) }) {
                    nav.popBackStack()
                }
            }
        }
    }
}

internal fun NavHostController.safePop() { if (previousBackStackEntry != null) popBackStack() }
