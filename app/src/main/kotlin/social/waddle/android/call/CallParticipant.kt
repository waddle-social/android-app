package social.waddle.android.call

import org.webrtc.VideoTrack

/**
 * A remote participant in an active Muji call. The SFU streams their media
 * to us as a set of tracks tagged with a stream id; we map that id back to
 * a Jabber ID via session-info `<participant-map>` payloads so the UI can
 * label tiles with the actual peer name.
 *
 * For 1:1 calls this reduces to exactly one participant; the same type is
 * used so the UI can render uniformly.
 */
data class CallParticipant(
    /** Stable identifier assigned by the SFU — matches the `msid` in the SDP. */
    val streamId: String,
    /** Bare JID of the peer producing this media, or null until the SFU tells us. */
    val peerJid: String? = null,
    /** Display name derived from the roster, falling back to the localpart. */
    val displayName: String? = null,
    /** Remote video track, if any — audio-only participants have null here. */
    val videoTrack: VideoTrack? = null,
    /**
     * Whether we're currently receiving audio from this participant. The
     * SFU doesn't split audio per-participant in 1:1 calls (it's all one
     * stream), so this is set to true whenever the session is active.
     */
    val hasAudio: Boolean = true,
) {
    val bestLabel: String
        get() = displayName ?: peerJid?.substringBefore('@') ?: streamId
}
