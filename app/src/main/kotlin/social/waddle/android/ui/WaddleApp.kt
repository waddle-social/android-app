package social.waddle.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import social.waddle.android.auth.AuthViewModel
import social.waddle.android.call.CallState
import social.waddle.android.call.WaddleCallService
import social.waddle.android.notifications.CatchUpScheduler
import social.waddle.android.notifications.DeepLink
import social.waddle.android.notifications.PendingDeepLink
import social.waddle.android.notifications.WaddleMessagingService
import social.waddle.android.ui.auth.AccountScreen
import social.waddle.android.ui.auth.SignInScreen
import social.waddle.android.ui.call.CallScreen
import social.waddle.android.ui.call.CallViewModel
import social.waddle.android.ui.chat.ChatScreen
import social.waddle.android.ui.chat.ChatViewModel
import social.waddle.android.ui.lock.AppLockViewModel

@Composable
fun WaddleApp(
    pendingDeepLink: PendingDeepLink,
    authViewModel: AuthViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
    appLockViewModel: AppLockViewModel = hiltViewModel(),
    callViewModel: CallViewModel = hiltViewModel(),
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

    ForegroundNotificationSuppressionEffect(chatViewModel = chatViewModel)
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
    CallNavigationEffect(callViewModel = callViewModel, backStack = backStack, context = context)

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
                    entry<Route.Call> {
                        CallScreen(
                            onCallFinished = {
                                if (backStack.lastOrNull() == Route.Call) backStack.removeLastOrNull()
                            },
                            viewModel = callViewModel,
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
private fun ForegroundNotificationSuppressionEffect(chatViewModel: ChatViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, chatViewModel) {
        fun publishForegroundState() {
            chatViewModel.setAppForeground(
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            )
        }

        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> chatViewModel.setAppForeground(true)
                    Lifecycle.Event.ON_STOP -> chatViewModel.setAppForeground(false)
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        publishForegroundState()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            chatViewModel.setAppForeground(false)
        }
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

/**
 * Routes the user into [Route.Call] when a call is in flight and pops back out
 * when it ends. Keeps the signaler's event collector wired for as long as the
 * composable is in the tree so incoming invites update [CallState] even while
 * the call screen is not yet mounted.
 */
@Composable
private fun CallNavigationEffect(
    callViewModel: CallViewModel,
    backStack: NavBackStack<Route>,
    context: android.content.Context,
) {
    LaunchedEffect(Unit) { callViewModel.observeSignaler() }
    val callState by callViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(callState) {
        when (callState) {
            is CallState.OutgoingRinging,
            is CallState.Connecting,
            is CallState.InCall,
            -> {
                WaddleCallService.start(context)
                if (backStack.lastOrNull() != Route.Call) backStack += Route.Call
            }

            is CallState.Incoming -> {
                // Start the service so it posts a full-screen-intent
                // notification while the banner handles the in-app accept UX.
                WaddleCallService.start(context)
            }

            is CallState.Ended -> {
                WaddleCallService.stop(context)
                if (backStack.lastOrNull() == Route.Call) backStack.removeLastOrNull()
            }

            CallState.Idle -> {
                WaddleCallService.stop(context)
                if (backStack.lastOrNull() == Route.Call) backStack.removeLastOrNull()
            }
        }
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
