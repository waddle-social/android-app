package social.waddle.android.auth

import android.net.Uri
import androidx.core.net.toUri
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.CodeVerifierUtil
import net.openid.appauth.ResponseTypeValues
import social.waddle.android.data.model.WaddleEnvironment

object OAuthConfig {
    const val CLIENT_ID: String = "waddle-android"
    const val SCOPE: String = "xmpp"
    const val REDIRECT_URI_VALUE: String = "social.waddle.android:/oauth2redirect"

    val REDIRECT_URI: Uri
        get() = REDIRECT_URI_VALUE.toUri()

    fun serviceConfiguration(environment: WaddleEnvironment): AuthorizationServiceConfiguration =
        AuthorizationServiceConfiguration(
            authorizationEndpoint(environment).toUri(),
            tokenEndpoint(environment).toUri(),
        )

    fun wellKnownUri(environment: WaddleEnvironment): Uri = wellKnownEndpoint(environment).toUri()

    fun wellKnownEndpoint(environment: WaddleEnvironment): String = "${environment.apiBaseUrl}/.well-known/oauth-authorization-server"

    fun authorizationEndpoint(environment: WaddleEnvironment): String = "${environment.apiBaseUrl}/api/auth/xmpp/authorize"

    fun tokenEndpoint(environment: WaddleEnvironment): String = "${environment.apiBaseUrl}/api/auth/xmpp/token"

    fun authorizationRequest(
        environment: WaddleEnvironment,
        providerId: String?,
    ): AuthorizationRequest {
        val builder =
            AuthorizationRequest
                .Builder(
                    serviceConfiguration(environment),
                    CLIENT_ID,
                    ResponseTypeValues.CODE,
                    REDIRECT_URI,
                ).setScope(SCOPE)
                .setCodeVerifier(CodeVerifierUtil.generateRandomCodeVerifier())
        providerId?.takeIf { it.isNotBlank() }?.let { provider ->
            builder.setAdditionalParameters(mapOf("provider" to provider))
        }
        return builder.build()
    }
}
