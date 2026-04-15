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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import social.waddle.android.auth.AuthViewModel
import social.waddle.android.ui.auth.AccountScreen
import social.waddle.android.ui.auth.SignInScreen
import social.waddle.android.ui.chat.ChatScreen
import social.waddle.android.ui.chat.ChatViewModel

@Composable
fun WaddleApp(
    authViewModel: AuthViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel(),
) {
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val backStack = remember { NavBackStack<Route>(Route.SignIn) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val signInLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            authViewModel.onAuthorizationResult(result.data)
        }

    LaunchedEffect(authState.session) {
        backStack.clear()
        backStack += if (authState.session == null) Route.SignIn else Route.Chat
    }

    LaunchedEffect(authState.error) {
        authState.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            authViewModel.clearError()
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.fillMaxSize(),
            onBack = {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
            },
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
                        )
                    }
                },
        )
    }
}
