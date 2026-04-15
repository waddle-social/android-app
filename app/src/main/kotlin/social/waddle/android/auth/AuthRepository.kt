package social.waddle.android.auth

import androidx.room.withTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.TokenResponse
import social.waddle.android.data.db.AccountDao
import social.waddle.android.data.db.AccountEntity
import social.waddle.android.data.db.AppDatabase
import social.waddle.android.data.model.AuthProviderSummary
import social.waddle.android.data.model.AuthSession
import social.waddle.android.data.model.SessionResponse
import social.waddle.android.data.model.StoredSession
import social.waddle.android.data.model.WaddleEnvironment
import social.waddle.android.data.network.WaddleApi
import social.waddle.android.util.WaddleLog
import social.waddle.android.xmpp.XmppClient
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class AuthRepository
    @Inject
    constructor(
        private val authorizationService: AuthorizationService,
        private val sessionStore: SecureSessionStore,
        private val accountDao: AccountDao,
        private val database: AppDatabase,
        private val xmppClient: XmppClient,
        private val api: WaddleApi,
    ) {
        private val mutableSession = MutableStateFlow<AuthSession?>(null)
        val session: StateFlow<AuthSession?> = mutableSession.asStateFlow()

        suspend fun restoreSession() {
            val stored = sessionStore.read()
            if (stored != null && !stored.matchesConfiguredEnvironment()) {
                sessionStore.clear()
                purgeLocalSessionData()
                mutableSession.value = null
                return
            }
            mutableSession.value = stored?.let(::AuthSession)
        }

        suspend fun authProviders(environment: WaddleEnvironment): List<AuthProviderSummary> = api.authProviders(environment)

        fun buildAuthorizationRequest(
            environment: WaddleEnvironment,
            providerId: String?,
        ) = OAuthConfig.authorizationRequest(environment, providerId)

        fun authorizationIntent(
            environment: WaddleEnvironment,
            providerId: String?,
        ) = authorizationService.getAuthorizationRequestIntent(buildAuthorizationRequest(environment, providerId))

        suspend fun exchange(
            response: AuthorizationResponse,
            environment: WaddleEnvironment,
        ): AuthSession {
            val tokenResponse = performTokenRequest(response)
            val accessToken = tokenResponse.accessToken ?: error("Waddle OAuth token response did not include access_token.")
            val sessionResponse = api.fetchSession(environment, accessToken)
            require(!sessionResponse.isExpired) { "Waddle OAuth session is expired." }

            val stored = sessionResponse.toStoredSession(environment, accessToken)
            accountDao.clearSelected()
            accountDao.upsert(stored.toAccountEntity(selected = true))
            sessionStore.write(stored)
            return AuthSession(stored).also { mutableSession.value = it }
        }

        suspend fun logout() {
            disconnectXmpp()
            purgeLocalSessionData()
            sessionStore.clear()
            mutableSession.value = null
        }

        private suspend fun disconnectXmpp() {
            runCatching {
                xmppClient.disconnect()
            }.onFailure { throwable ->
                WaddleLog.error("XMPP disconnect failed during logout.", throwable)
            }
        }

        private suspend fun purgeLocalSessionData() {
            database.withTransaction {
                database.pendingOutboundDao().clear()
                database.deliveryStateDao().clear()
                database.dmReactionDao().clear()
                database.reactionDao().clear()
                database.dmMessageDao().clear()
                database.dmConversationDao().clear()
                database.messageDao().clear()
                database.occupantDao().clear()
                accountDao.clear()
            }
        }

        private suspend fun performTokenRequest(response: AuthorizationResponse): TokenResponse =
            suspendCancellableCoroutine { continuation ->
                val request = response.createTokenExchangeRequest()
                authorizationService.performTokenRequest(request) { tokenResponse, exception ->
                    when {
                        tokenResponse != null -> continuation.resume(tokenResponse)
                        exception != null -> continuation.resumeWithException(exception)
                        else -> continuation.resumeWithException(IllegalStateException("Empty token response."))
                    }
                }
            }

        private fun SessionResponse.toStoredSession(
            environment: WaddleEnvironment,
            accessToken: String,
        ): StoredSession =
            StoredSession(
                environmentId = environment.id,
                accessToken = accessToken,
                sessionId = sessionId,
                userId = userId,
                username = username,
                avatarUrl = avatarUrl,
                xmppLocalpart = xmppLocalpart,
                jid = jid,
                xmppWebSocketUrl = xmppWebSocketUrl,
                expiresAt = expiresAt,
            )

        private fun StoredSession.toAccountEntity(selected: Boolean): AccountEntity =
            AccountEntity(
                userId = userId,
                environmentId = environmentId,
                sessionId = sessionId,
                username = username,
                xmppLocalpart = xmppLocalpart,
                jid = jid,
                selected = selected,
                expiresAt = expiresAt,
            )

        private fun StoredSession.matchesConfiguredEnvironment(): Boolean {
            val websocketHost =
                xmppWebSocketUrl
                    ?.let { url -> runCatching { URI(url).host }.getOrNull() }
            return xmppDomain == environment.xmppHost &&
                (websocketHost == null || websocketHost == environment.xmppHost)
        }
    }
