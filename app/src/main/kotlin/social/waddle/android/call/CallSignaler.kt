package social.waddle.android.call

import kotlinx.coroutines.flow.Flow
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Wire-format abstraction for call signaling. The reference implementation
 * speaks XMPP Jingle (XEP-0482 invite, XEP-0166 session-initiate/accept/terminate,
 * XEP-0176 trickle-ICE transport-info) to the Waddle server's SFU component —
 * see [XmppJingleCallSignaler].
 *
 * All round-trip exchanges are suspending so the caller (a [CallController])
 * can sequence the handshake without race conditions.
 */
interface CallSignaler {
    /** Stream of peer-initiated events (invites, answers, remote ICE, terminations). */
    val events: Flow<CallSignalEvent>

    /** Send an XEP-0482 `<invite>` message to [peerJid] advertising [sid]. */
    suspend fun sendInvite(
        peerJid: String,
        sid: String,
    )

    /** Send a Jingle `session-initiate` IQ to the SFU with our local [offer]. */
    suspend fun sendSessionInitiate(
        sfuJid: String,
        sid: String,
        offer: SessionDescription,
    )

    /** Send a Jingle `session-accept` IQ with our local [answer]. */
    suspend fun sendSessionAccept(
        sfuJid: String,
        sid: String,
        answer: SessionDescription,
    )

    /** Send a Jingle `transport-info` IQ with a locally-gathered trickle [candidate]. */
    suspend fun sendTransportInfo(
        sfuJid: String,
        sid: String,
        candidate: IceCandidate,
    )

    /** Send a Jingle `session-terminate` IQ closing the call with optional [reason]. */
    suspend fun sendSessionTerminate(
        sfuJid: String,
        sid: String,
        reason: String?,
    )

    /**
     * Query the server for its STUN/TURN servers (XEP-0215). Implementations
     * may cache the result for the session. Return empty to fall back to
     * whatever defaults the WebRTC stack provides (none).
     */
    suspend fun discoverIceServers(): List<IceServerConfig>
}

sealed interface CallSignalEvent {
    /** A peer just sent us an XEP-0482 `<invite>`. */
    data class IncomingInvite(
        val sid: String,
        val fromJid: String,
        val fromDisplayName: String?,
        val sfuJid: String?,
        val muji: Boolean = false,
        val meetingDescription: String? = null,
    ) : CallSignalEvent

    /** The SFU accepted our session-initiate with an SDP answer. */
    data class RemoteAnswer(
        val sid: String,
        val fromJid: String,
        val answer: SessionDescription,
    ) : CallSignalEvent

    /** The SFU is issuing a trickle-ICE candidate for us. */
    data class RemoteCandidate(
        val sid: String,
        val candidate: IceCandidate,
    ) : CallSignalEvent

    /** The call was ended from the far side (peer rejected / SFU terminated). */
    data class RemoteTerminate(
        val sid: String,
        val reason: String?,
    ) : CallSignalEvent
}

data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)
