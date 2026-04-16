package social.waddle.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import social.waddle.android.auth.AuthViewModel
import social.waddle.android.notifications.CatchUpScheduler
import social.waddle.android.notifications.DeepLink
import social.waddle.android.notifications.PendingDeepLink
import social.waddle.android.notifications.WaddleMessagingService
import social.waddle.android.ui.auth.AccountScreen
import social.waddle.android.ui.auth.SignInScreen
import social.waddle.android.ui.chat.ChatScreen
import social.waddle.android.ui.chat.ChatViewModel
import social.waddle.android.ui.lock.AppLockViewModel

@Composable
fun WaddleApp(
    pendingDeepLink: PendingDeepLink,
    authViewModel: AuthViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    appLockViewModel: AppLockViewModel = hiltViewModel(),
) {
    val lockEnabled by appLockViewModel.lockEnabled.collectAsStateWithLifecycle()
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val backStack = remember { NavBackStack<Route>(Route.SignIn) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val signInLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            authViewModel.onAuthorizationResult(result.data)
        }

    SessionLifecycleEffect(
        session = authState.session,
        chatViewModel = chatViewModel,
        backStack = backStack,
        context = context,
    )
    DeepLinkRouter(
        pendingDeepLink = pendingDeepLink,
        session = authState.session,
        chatViewModel = chatViewModel,
    )
    AuthErrorSnackbar(authState = authState, snackbarHost = snackbarHostState, authViewModel = authViewModel)

    Surface(modifier = Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryProvider =
                entryProvider<Route> {
                    entry<Route.SignIn> {
                        SignInScreen(
                            state = authState,
                            onEnvironmentSelected = authViewModel::setEnvironment,
                            onProviderSelected = authViewModel::setProvider,
                            onSignIn = { signInLauncher.launch(authViewModel.authorizationIntent()) },
                        )
                    }
                    entry<Route.Chat> {
                        val session = authState.session ?: return@entry
                        ChatScreen(
                            session = session,
                            viewModel = chatViewModel,
                            onOpenAccount = { backStack += Route.Account },
                            snackbarHost = { SnackbarHost(snackbarHostState) },
                        )
                    }
                    entry<Route.Account> {
                        val session = authState.session ?: return@entry
                        AccountScreen(
                            session = session,
                            onBack = { backStack.removeLastOrNull() },
                            onLogout = {
                                scope.launch {
                                    authViewModel.logout()
                                    backStack.clear()
                                    backStack += Route.SignIn
                                }
                            },
                            biometricLockEnabled = lockEnabled,
                            onBiometricLockChange = appLockViewModel::setLockEnabled,
                        )
                    }
                },
        )
    }
}

@Composable
private fun SessionLifecycleEffect(
    session: social.waddle.android.data.model.AuthSession?,
    chatViewModel: ChatViewModel,
    backStack: NavBackStack<Route>,
    context: android.content.Context,
) {
    LaunchedEffect(session) {
        if (session == null) {
            chatViewModel.reset()
            WaddleMessagingService.stop(context)
            CatchUpScheduler.cancel(context)
        } else {
            WaddleMessagingService.start(context)
            CatchUpScheduler.enqueue(context)
        }
        backStack.clear()
        backStack += if (session == null) Route.SignIn else Route.Chat
    }
}

@Composable
private fun DeepLinkRouter(
    pendingDeepLink: PendingDeepLink,
    session: social.waddle.android.data.model.AuthSession?,
    chatViewModel: ChatViewModel,
) {
    val deepLink by pendingDeepLink.link.collectAsStateWithLifecycle()
    LaunchedEffect(deepLink, session) {
        val link = deepLink ?: return@LaunchedEffect
        val active = session ?: return@LaunchedEffect
        when (link) {
            is DeepLink.OpenDirectMessage -> {
                chatViewModel.showDirectMessages()
                chatViewModel.selectDirectMessage(active, link.peerJid)
            }

            is DeepLink.OpenRoom -> {
                chatViewModel.showRooms()
                chatViewModel.openRoomFromIntent(link.roomJid)
            }
        }
        pendingDeepLink.consume()
    }
}

@Composable
private fun AuthErrorSnackbar(
    authState: social.waddle.android.auth.AuthUiState,
    snackbarHost: SnackbarHostState,
    authViewModel: AuthViewModel,
) {
    LaunchedEffect(authState.error, authState.session) {
        val message = authState.error ?: return@LaunchedEffect
        if (authState.session != null) {
            snackbarHost.showSnackbar(message)
            authViewModel.clearError()
        }
        // When signed out, SignInScreen renders the error inline — no snackbar host exists.
    }
}
