package social.waddle.android.xmpp

import org.junit.Assert.assertEquals
import org.junit.Test

class OAuthBearerMechanismTest {
    @Test
    fun buildsRfc7628InitialClientResponse() {
        assertEquals(
            "n,,\u0001auth=Bearer session-token\u0001\u0001",
            OAuthBearerMechanism.oauthBearerInitialResponse("session-token"),
        )
    }
}
