package social.waddle.android.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class StoredSessionTest {
    @Test
    fun derivesXmppRuntimeAddressesFromJid() {
        val session =
            StoredSession(
                environmentId = WaddleEnvironment.Prod.id,
                accessToken = "oauth-access-token",
                sessionId = "waddle-session-id",
                userId = "user-1",
                username = "stefan",
                xmppLocalpart = "stefan",
                jid = "stefan@xmpp.waddle.social",
                xmppWebSocketUrl = "wss://xmpp.waddle.social/xmpp-websocket",
                expiresAt = null,
            )

        assertEquals("xmpp.waddle.social", session.xmppDomain)
        assertEquals("muc.xmpp.waddle.social", session.mucDomain)
        assertEquals("waddle-session-id", session.xmppBearerToken)
    }
}
