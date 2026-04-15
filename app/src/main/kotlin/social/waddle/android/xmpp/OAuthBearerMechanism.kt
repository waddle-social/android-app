package social.waddle.android.xmpp

import org.jivesoftware.smack.SmackException
import org.jivesoftware.smack.sasl.SASLMechanism
import javax.security.auth.callback.CallbackHandler

class OAuthBearerMechanism : SASLMechanism() {
    override fun getName(): String = NAME

    override fun getPriority(): Int = PRIORITY

    override fun requiresPassword(): Boolean = true

    override fun getAuthenticationText(): ByteArray = toBytes(oauthBearerInitialResponse(password))

    override fun authenticateInternal(cbh: CallbackHandler): Unit =
        throw SmackException.SmackSaslException("OAUTHBEARER requires token authentication through setUsernameAndPassword.")

    override fun checkIfSuccessfulOrThrow() = Unit

    override fun newInstance(): SASLMechanism = OAuthBearerMechanism()

    companion object {
        const val NAME: String = "OAUTHBEARER"
        private const val PRIORITY = 250

        fun oauthBearerInitialResponse(token: String): String = "n,,\u0001auth=Bearer $token\u0001\u0001"
    }
}
