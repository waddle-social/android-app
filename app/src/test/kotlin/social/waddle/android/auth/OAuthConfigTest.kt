package social.waddle.android.auth

import org.junit.Assert.assertEquals
import org.junit.Test
import social.waddle.android.data.model.WaddleEnvironment

class OAuthConfigTest {
    @Test
    fun prodEndpointsUseXmppBroker() {
        assertEquals(
            "https://xmpp.waddle.social/.well-known/oauth-authorization-server",
            OAuthConfig.wellKnownEndpoint(WaddleEnvironment.Prod),
        )
        assertEquals(
            "https://xmpp.waddle.social/api/auth/xmpp/authorize",
            OAuthConfig.authorizationEndpoint(WaddleEnvironment.Prod),
        )
        assertEquals(
            "https://xmpp.waddle.social/api/auth/xmpp/token",
            OAuthConfig.tokenEndpoint(WaddleEnvironment.Prod),
        )
    }

    @Test
    fun devEndpointsUseLocalhostForAdbReverse() {
        assertEquals(
            "http://localhost:3000/.well-known/oauth-authorization-server",
            OAuthConfig.wellKnownEndpoint(WaddleEnvironment.Dev),
        )
        assertEquals(
            "http://localhost:3000/api/auth/xmpp/authorize",
            OAuthConfig.authorizationEndpoint(WaddleEnvironment.Dev),
        )
        assertEquals(
            "http://localhost:3000/api/auth/xmpp/token",
            OAuthConfig.tokenEndpoint(WaddleEnvironment.Dev),
        )
    }
}
