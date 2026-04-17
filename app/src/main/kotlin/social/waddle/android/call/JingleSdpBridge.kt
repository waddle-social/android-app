package social.waddle.android.call

import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import social.waddle.android.call.jingle.ContentCreator
import social.waddle.android.call.jingle.IceUdpCandidate
import social.waddle.android.call.jingle.JingleContent
import social.waddle.android.call.jingle.JingleElement
import social.waddle.android.call.jingle.Xep0176
import social.waddle.android.call.jingle.buildCandidateElement
import social.waddle.android.call.jingle.buildJingleContentElement
import social.waddle.android.call.jingle.extractBundleMids
import social.waddle.android.call.jingle.jingleContentsToSdp
import social.waddle.android.call.jingle.parseCandidateElement
import social.waddle.android.call.jingle.parseJingleContentElement
import social.waddle.android.call.jingle.parseSdpCandidateLine
import social.waddle.android.call.jingle.sdpToJingleContents
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translation layer between WebRTC SDP / [IceCandidate] and the internal
 * [JingleElement] representation. Implementations shuttle the heavy lifting to
 * [social.waddle.android.call.jingle.JingleSdp]; the signaler is then
 * responsible for mapping [JingleElement] to/from Smack's wire-format
 * extension elements when sending or receiving stanzas.
 *
 * Separating the SDP↔Jingle layer from the Smack layer keeps the bridge
 * unit-testable on the JVM without an XMPP connection and keeps the Smack
 * adapter thin.
 */
interface JingleSdpBridge {
    /**
     * Convert a local WebRTC offer into the list of Jingle `<content>`
     * children the server's `session-initiate` expects.
     */
    fun offerToJingleContents(offer: SessionDescription): List<JingleElement>

    /**
     * Convert the server's `session-accept` `<content>` children back to a
     * [SessionDescription.Type.ANSWER] [SessionDescription] we can apply as
     * the remote description on our [org.webrtc.PeerConnection].
     */
    fun answerFromJingleContents(contents: List<JingleElement>): SessionDescription

    /** Convert a locally-gathered WebRTC ICE candidate to a Jingle `<candidate/>` element. */
    fun candidateToJingleElement(candidate: IceCandidate): JingleElement

    /** Parse a Jingle `<candidate/>` element received in a `transport-info` IQ. */
    fun candidateFromJingleElement(element: JingleElement): IceCandidate
}

/**
 * Production [JingleSdpBridge] backed by the pure-Kotlin port in
 * [social.waddle.android.call.jingle.JingleSdp].
 *
 * Candidate conversion mirrors the web client's behavior: WebRTC candidate
 * strings take the form `candidate:<foundation> <component> <protocol>
 * <priority> <ip> <port> typ <type> [generation N]`. We strip the
 * `candidate:` prefix, reuse the SDP parser from the bridge, then wrap back
 * up as an SDP `a=candidate:` line with the candidate's `sdpMid` so WebRTC
 * can address the correct m-section on receive.
 */
@Singleton
class RealJingleSdpBridge
    @Inject
    constructor() : JingleSdpBridge {
        override fun offerToJingleContents(offer: SessionDescription): List<JingleElement> {
            val contents = sdpToJingleContents(offer.description, ContentCreator.INITIATOR)
            return contents.map(::buildJingleContentElement)
        }

        override fun answerFromJingleContents(contents: List<JingleElement>): SessionDescription {
            val parsed = contents.mapNotNull(::parseJingleContentElement)
            require(parsed.isNotEmpty()) { "answerFromJingleContents requires at least one <content>" }
            val sdp = jingleContentsToSdp(parsed)
            return SessionDescription(SessionDescription.Type.ANSWER, sdp)
        }

        override fun candidateToJingleElement(candidate: IceCandidate): JingleElement {
            val candidateBody = candidate.sdp.removePrefix("candidate:")
            val parsed =
                parseSdpCandidateLine(candidateBody)
                    ?: error("Unable to parse local ICE candidate: ${candidate.sdp}")
            return buildCandidateElement(parsed)
        }

        override fun candidateFromJingleElement(element: JingleElement): IceCandidate {
            val parsed =
                parseCandidateElement(element)
                    ?: error("Unable to parse remote <candidate/>: ${element.toXmlString()}")
            val sdp = buildCandidateSdpLine(parsed)
            // Jingle candidates target a specific transport which maps to an
            // SDP m-section. The Waddle server always sends candidates scoped
            // to the content whose name is the mid, so the signaler is
            // responsible for telling us which mid this candidate belongs to
            // by wrapping the element in a content-scoped event. Here we
            // default to mid="0" and sdpMLineIndex=0; the signaler overrides
            // when the <content> parent is known.
            return IceCandidate(DEFAULT_MID, DEFAULT_M_LINE_INDEX, sdp)
        }

        private fun buildCandidateSdpLine(c: IceUdpCandidate): String {
            val sb = StringBuilder()
            sb
                .append("candidate:")
                .append(c.foundation)
                .append(' ')
                .append(c.component)
                .append(' ')
                .append(c.protocol)
                .append(' ')
                .append(c.priority)
                .append(' ')
                .append(c.ip)
                .append(' ')
                .append(c.port)
                .append(" typ ")
                .append(c.candidateType.attr)
            c.generation?.let { sb.append(" generation ").append(it) }
            return sb.toString()
        }

        private companion object {
            const val DEFAULT_MID = "0"
            const val DEFAULT_M_LINE_INDEX = 0
        }
    }

/**
 * Re-export surface for callers that want the computed BUNDLE mids when
 * wiring up signalling (e.g., to decide which m-line index to use for a
 * candidate). Lives here so the call package doesn't need to reach into
 * the jingle sub-package for one helper.
 */
fun bundleMidsFromSdp(sdp: String): List<String> = extractBundleMids(sdp)

/** Scope a Jingle candidate element to a specific m-section on parse. */
fun iceCandidateFromJingle(
    bridge: JingleSdpBridge,
    element: JingleElement,
    mid: String,
    sdpMLineIndex: Int,
): IceCandidate {
    val raw = bridge.candidateFromJingleElement(element)
    return IceCandidate(mid, sdpMLineIndex, raw.sdp)
}

/** Convenience: is this a Jingle `<candidate/>` from XEP-0176? */
fun JingleElement.isIceCandidate(): Boolean = isTag("candidate", Xep0176.NS)

/** Convenience: is the element a `<content/>` holding a single iceudp candidate? */
fun JingleElement.firstCandidateChild(): JingleElement? =
    children.firstOrNull { it.isTag("candidate", Xep0176.NS) }
        ?: children
            .firstOrNull { it.name == "transport" }
            ?.children
            ?.firstOrNull { it.isTag("candidate", Xep0176.NS) }

/** Build a Jingle candidate sdp line from an internal candidate (exposed for tests). */
internal fun IceUdpCandidate.toSdpAttribute(): String {
    val sb = StringBuilder()
    sb
        .append("candidate:")
        .append(foundation)
        .append(' ')
        .append(component)
        .append(' ')
        .append(protocol)
        .append(' ')
        .append(priority)
        .append(' ')
        .append(ip)
        .append(' ')
        .append(port)
        .append(" typ ")
        .append(candidateType.attr)
    generation?.let { sb.append(" generation ").append(it) }
    return sb.toString()
}

/** Discard the old not-implemented stub; [RealJingleSdpBridge] is the production path. */
class JingleSdpBridgeError(
    message: String,
) : IllegalStateException(message)

/**
 * Compatibility shim: used by a few call sites that wanted a "fail fast"
 * bridge for tests where SDP conversion must not be triggered. Prefer
 * injecting [JingleSdpBridge] directly and using a test double instead.
 */
class FailFastJingleSdpBridge : JingleSdpBridge {
    override fun offerToJingleContents(offer: SessionDescription): List<JingleElement> = unavailable()

    override fun answerFromJingleContents(contents: List<JingleElement>): SessionDescription = unavailable()

    override fun candidateToJingleElement(candidate: IceCandidate): JingleElement = unavailable()

    override fun candidateFromJingleElement(element: JingleElement): IceCandidate = unavailable()

    private fun unavailable(): Nothing =
        throw JingleSdpBridgeError(
            "FailFastJingleSdpBridge used outside tests — wire RealJingleSdpBridge via Hilt.",
        )
}
