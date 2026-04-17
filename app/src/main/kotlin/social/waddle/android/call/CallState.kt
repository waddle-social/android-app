package social.waddle.android.call

/**
 * State machine for a single call. Matches the states the Waddle server's
 * XEP-0482 invite + XEP-0166 Jingle lifecycle exposes:
 *  - Idle: no call in progress
 *  - Outgoing/Ringing: we sent an invite, waiting for the peer to accept
 *  - Incoming: a peer sent us an invite, we haven't accepted yet
 *  - Connecting: session-initiate sent, waiting for session-accept + DTLS handshake
 *  - InCall: DTLS up, RTP flowing
 *  - Ended: session-terminate in either direction
 */
sealed interface CallState {
    data object Idle : CallState

    data class OutgoingRinging(
        val sid: String,
        val peerJid: String,
        val startedAtEpochMillis: Long,
        val muji: Boolean = false,
    ) : CallState

    data class Incoming(
        val sid: String,
        val peerJid: String,
        val peerDisplayName: String?,
        val muji: Boolean = false,
        val meetingDescription: String? = null,
    ) : CallState

    data class Connecting(
        val sid: String,
        val peerJid: String,
        val direction: CallDirection,
        val muji: Boolean = false,
    ) : CallState

    data class InCall(
        val sid: String,
        val peerJid: String,
        val direction: CallDirection,
        val connectedAtEpochMillis: Long,
        val muji: Boolean = false,
    ) : CallState

    data class Ended(
        val sid: String,
        val reason: String?,
    ) : CallState
}

enum class CallDirection { OUTGOING, INCOMING }
