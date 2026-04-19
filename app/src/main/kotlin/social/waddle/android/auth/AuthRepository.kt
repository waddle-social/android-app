package social.waddle.android.auth

import androidx.room.withTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import social.waddle.android.data.db.AccountDao
import social.waddle.android.data.db.AccountEntity
import social.waddle.android.data.db.AppDatabase
import social.waddle.android.data.model.AuthProviderSummary
import social.waddle.android.data.model.AuthSession
import social.waddle.android.data.model.SessionResponse
import social.waddle.android.data.model.StoredSession
import social.waddle.android.data.model.WaddleEnvironment
import social.waddle.android.data.network.WaddleGateway
import social.waddle.android.util.WaddleLog
import social.waddle.android.xmpp.SessionProvider
import java.net.URI
import java.time.Instant
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
        private val waddle: WaddleGateway,
    ) : SessionProvider {
        override suspend fun currentSession(): StoredSession? = ensureFreshSession()

        private val mutableSession = MutableStateFlow<AuthSession?>(null)
        val session: StateFlow<AuthSession?> = mutableSession.asStateFlow()
        private val refreshMutex = Mutex()

        suspend fun restoreSession() {
            val stored =
                sessionStore.read() ?: run {
                    mutableSession.value = null
                    return
                }
            if (!stored.matchesConfiguredEnvironment()) {
                sessionStore.clear()
                purgeLocalSessionData()
                mutableSession.value = null
                return
            }
            val refreshed =
                if (stored.accessTokenExpired()) {
                    runCatching { refreshSession(stored) }
                        .onFailure { WaddleLog.error("Access token refresh on restore failed.", it) }
                        .getOrNull()
                } else {
                    stored
                }
            if (refreshed == null) {
                sessionStore.clear()
                purgeLocalSessionData()
                mutableSession.value = null
                return
            }
            mutableSession.value = AuthSession(refreshed)
        }

        /**
         * Refresh the stored session if it is expired or within a short renewal window.
         * Thread-safe: concurrent callers share one refresh round-trip.
         */
        suspend fun ensureFreshSession(): StoredSession? {
            val current = sessionStore.read() ?: return null
            if (!current.accessTokenExpired(renewalMargin = RENEWAL_MARGIN_SECONDS)) {
                return current
            }
            return refreshMutex.withLock {
                val latest = sessionStore.read() ?: return@withLock null
                if (!latest.accessTokenExpired(renewalMargin = RENEWAL_MARGIN_SECONDS)) {
                    return@withLock latest
                }
                runCatching { refreshSession(latest) }
                    .onFailure { WaddleLog.error("Background token refresh failed.", it) }
                    .getOrNull()
            }
        }

        private suspend fun refreshSession(current: StoredSession): StoredSession? {
            val refreshToken = current.refreshToken?.takeIf { it.isNotBlank() } ?: return null
            val tokenResponse = performRefreshTokenRequest(current.environment, refreshToken)
            val accessToken =
                tokenResponse.accessToken
                    ?: error("Refresh-token response did not include access_token.")
            val sessionResponse = waddle.fetchSession(current.environment, accessToken)
            require(!sessionResponse.isExpired) { "Refreshed session is marked expired." }
            val updated =
                current.copy(
                    accessToken = accessToken,
                    refreshToken = tokenResponse.refreshToken ?: refreshToken,
                    accessTokenExpiresAt = tokenResponse.accessTokenExpirationTimeIso(),
                    sessionId = sessionResponse.sessionId,
                    userId = sessionResponse.userId,
                    username = sessionResponse.username,
                    avatarUrl = sessionResponse.avatarUrl,
                    xmppLocalpart = sessionResponse.xmppLocalpart,
                    jid = sessionResponse.jid,
                    xmppWebSocketUrl = sessionResponse.xmppWebSocketUrl,
                    expiresAt = sessionResponse.expiresAt,
                )
            sessionStore.write(updated)
            accountDao.upsert(updated.toAccountEntity(selected = true))
            mutableSession.value = AuthSession(updated)
            return updated
        }

        private suspend fun performRefreshTokenRequest(
            environment: WaddleEnvironment,
            refreshToken: String,
        ): TokenResponse {
            val request =
                TokenRequest
                    .Builder(
                        OAuthConfig.serviceConfiguration(environment),
                        OAuthConfig.CLIENT_ID,
                    ).setGrantType(GrantTypeValues.REFRESH_TOKEN)
                    .setRefreshToken(refreshToken)
                    .setScope(OAuthConfig.SCOPE)
                    .build()
            return suspendCancellableCoroutine { continuation ->
                authorizationService.performTokenRequest(request) { tokenResponse, exception ->
                    when {
                        tokenResponse != null -> {
                            continuation.resume(tokenResponse)
                        }

                        exception != null -> {
                            continuation.resumeWithException(exception)
                        }

                        else -> {
                            continuation.resumeWithException(
                                IllegalStateException("Empty refresh-token response."),
                            )
                        }
                    }
                }
            }
        }

        private fun StoredSession.accessTokenExpired(renewalMargin: Long = 0L): Boolean {
            val expiresAt =
                accessTokenExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: return refreshToken.isNullOrBlank().not() // assume expired if unknown but refreshable
            return Instant.now().isAfter(expiresAt.minusSeconds(renewalMargin))
        }

        private fun TokenResponse.accessTokenExpirationTimeIso(): String? =
            accessTokenExpirationTime?.let { millis ->
                runCatching { Instant.ofEpochMilli(millis).toString() }.getOrNull()
            }

        suspend fun authProviders(environment: WaddleEnvironment): List<AuthProviderSummary> = waddle.authProviders(environment)

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
            val sessionResponse = waddle.fetchSession(environment, accessToken)
            require(!sessionResponse.isExpired) { "Waddle OAuth session is expired." }

            val stored =
                sessionResponse.toStoredSession(
                    environment = environment,
                    accessToken = accessToken,
                    refreshToken = tokenResponse.refreshToken,
                    accessTokenExpiresAt = tokenResponse.accessTokenExpirationTimeIso(),
                )
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
                waddle.disconnect()
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
            refreshToken: String?,
            accessTokenExpiresAt: String?,
        ): StoredSession =
            StoredSession(
                environmentId = environment.id,
                accessToken = accessToken,
                refreshToken = refreshToken,
                accessTokenExpiresAt = accessTokenExpiresAt,
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

        private companion object {
            // Refresh the access token this many seconds before its nominal expiry.
            const val RENEWAL_MARGIN_SECONDS = 60L
        }
    }
