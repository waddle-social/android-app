package social.waddle.android.auth

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import social.waddle.android.data.EnvironmentRepository
import social.waddle.android.data.model.AuthProviderSummary
import social.waddle.android.data.model.AuthSession
import social.waddle.android.data.model.WaddleEnvironment
import social.waddle.android.util.WaddleLog
import javax.inject.Inject

data class AuthUiState(
    val loading: Boolean = true,
    val providersLoading: Boolean = false,
    val environment: WaddleEnvironment = WaddleEnvironment.Prod,
    val providers: List<AuthProviderSummary> = emptyList(),
    val selectedProviderId: String? = null,
    val session: AuthSession? = null,
    val error: String? = null,
)

@HiltViewModel
class AuthViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
        private val environmentRepository: EnvironmentRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow(AuthUiState())
        val state: StateFlow<AuthUiState> = mutableState.asStateFlow()
        private var providerLoadJob: Job? = null

        init {
            viewModelScope.launch {
                authRepository.restoreSession()
                mutableState.update { it.copy(loading = false) }
            }
            viewModelScope.launch {
                environmentRepository.selectedEnvironment.collect { environment ->
                    mutableState.update { it.copy(environment = environment) }
                    loadProviders(environment)
                }
            }
            viewModelScope.launch {
                authRepository.session.collect { session ->
                    mutableState.update { it.copy(session = session) }
                }
            }
        }

        fun authorizationIntent(): Intent =
            authRepository.authorizationIntent(
                mutableState.value.environment,
                mutableState.value.selectedProviderId,
            )

        fun setEnvironment(environment: WaddleEnvironment) {
            viewModelScope.launch {
                environmentRepository.setEnvironment(environment)
            }
        }

        fun setProvider(providerId: String) {
            mutableState.update { state ->
                if (state.providers.any { it.id == providerId }) {
                    state.copy(selectedProviderId = providerId, error = null)
                } else {
                    state
                }
            }
        }

        fun onAuthorizationResult(data: Intent?) {
            val response = data?.let(AuthorizationResponse::fromIntent)
            val exception = data?.let(AuthorizationException::fromIntent)
            if (exception != null) {
                WaddleLog.error("OAuth authorization failed.", exception)
                mutableState.update { it.copy(error = exception.errorDescription ?: exception.message) }
                return
            }
            if (response == null) {
                mutableState.update { it.copy(error = "OAuth sign-in was cancelled.") }
                return
            }

            viewModelScope.launch {
                mutableState.update { it.copy(loading = true, error = null) }
                runCatching {
                    authRepository.exchange(response, mutableState.value.environment)
                }.onFailure { throwable ->
                    WaddleLog.error("OAuth token exchange failed.", throwable)
                    mutableState.update { it.copy(error = throwable.message ?: "OAuth token exchange failed.") }
                }
                mutableState.update { it.copy(loading = false) }
            }
        }

        fun logout() {
            viewModelScope.launch {
                authRepository.logout()
            }
        }

        fun clearError() {
            mutableState.update { it.copy(error = null) }
        }

        private fun loadProviders(environment: WaddleEnvironment) {
            providerLoadJob?.cancel()
            providerLoadJob =
                viewModelScope.launch {
                    mutableState.update { it.copy(providersLoading = true, error = null) }
                    runCatching {
                        authRepository.authProviders(environment)
                    }.onSuccess { providers ->
                        mutableState.update { state ->
                            val selectedProviderId =
                                state.selectedProviderId
                                    ?.takeIf { selected -> providers.any { it.id == selected } }
                                    ?: providers.firstOrNull()?.id
                            state.copy(
                                providers = providers,
                                selectedProviderId = selectedProviderId,
                                providersLoading = false,
                            )
                        }
                    }.onFailure { throwable ->
                        WaddleLog.error("Failed to load auth providers.", throwable)
                        mutableState.update {
                            it.copy(
                                providers = emptyList(),
                                selectedProviderId = null,
                                providersLoading = false,
                                error = throwable.message ?: "Failed to load auth providers.",
                            )
                        }
                    }
                }
        }
    }
