package social.waddle.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuthProviderSummary(
    val id: String,
    @SerialName("display_name")
    val displayName: String,
    val kind: String,
)

@Serializable
data class StoredSession(
    val environmentId: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val accessTokenExpiresAt: String? = null,
    val sessionId: String,
    val userId: String,
    val username: String,
    val avatarUrl: String? = null,
    val xmppLocalpart: String,
    val jid: String,
    val xmppWebSocketUrl: String?,
    val expiresAt: String?,
) {
    val environment: WaddleEnvironment
        get() = WaddleEnvironment.fromId(environmentId)

    val xmppDomain: String
        get() = jid.substringAfter('@', missingDelimiterValue = environment.xmppHost)

    val xmppBearerToken: String
        get() = sessionId

    val mucDomain: String
        get() = "muc.$xmppDomain"
}

data class AuthSession(
    val stored: StoredSession,
) {
    val displayName: String = stored.username
    val jid: String = stored.jid
    val environment: WaddleEnvironment = stored.environment
}

@Serializable
data class SessionResponse(
    @SerialName("session_id")
    val sessionId: String,
    @SerialName("user_id")
    val userId: String,
    val username: String,
    @SerialName("avatar_url")
    val avatarUrl: String? = null,
    @SerialName("xmpp_localpart")
    val xmppLocalpart: String,
    val jid: String,
    @SerialName("xmpp_websocket_url")
    val xmppWebSocketUrl: String? = null,
    @SerialName("is_expired")
    val isExpired: Boolean,
    @SerialName("expires_at")
    val expiresAt: String? = null,
)
