package social.waddle.android.call.jingle

/**
 * Jingle ↔ SDP translation layer.
 *
 * Port of `server/crates/waddle-xmpp/src/sfu/sdp.rs`. Byte-identical output is
 * required so the Waddle SFU accepts our offers and answers.
 *
 * Namespaces covered:
 *  - XEP-0166 Jingle (content element)
 *  - XEP-0167 RTP description
 *  - XEP-0176 ICE-UDP transport
 *  - XEP-0320 DTLS fingerprints
 *  - XEP-0338 Jingle Grouping Framework (`<group semantics='BUNDLE'>`)
 *  - XEP-0294 RTP Header Extensions (`<rtp-hdrext/>`) — critical for BUNDLE
 *    demultiplexing; without pass-through the SFU drops incoming RTP with
 *    "No mid/SSRC for header".
 */
object JingleSdp {
    /** XEP-0338 Jingle Grouping Framework namespace. */
    const val NS_JINGLE_GROUPING: String = "urn:xmpp:jingle:apps:grouping:0"

    /** XEP-0294 Jingle RTP Header Extensions namespace. */
    const val NS_JINGLE_RTP_HDREXT: String = "urn:xmpp:jingle:apps:rtp:rtp-hdrext:0"

    /** Waddle's custom SFU participant-map namespace (session-info payload). */
    const val NS_PARTICIPANT_MAP: String = "urn:waddle:sfu:participant-map:0"
}

// ---------------------------------------------------------------------------
// XEP-0294 RTP Header Extensions
// ---------------------------------------------------------------------------

/**
 * A parsed XEP-0294 `<rtp-hdrext>` entry or SDP `a=extmap:` line. `senders`
 * maps to the SDP `/sendonly`, `/recvonly`, `/inactive` suffix on
 * `a=extmap:` lines ("both" / none is symmetric, no suffix).
 */
data class RtpHeaderExtension(
    val id: Int,
    val uri: String,
    val senders: String? = null,
)

internal fun parseRtpHeaderExtensions(description: JingleElement): List<RtpHeaderExtension> =
    description.children
        .filter { it.isTag("rtp-hdrext", JingleSdp.NS_JINGLE_RTP_HDREXT) }
        .mapNotNull { c ->
            val id = c.attr("id")?.toIntOrNull() ?: return@mapNotNull null
            val uri = c.attr("uri") ?: return@mapNotNull null
            val senders = c.attr("senders")
            RtpHeaderExtension(id = id, uri = uri, senders = senders)
        }

/** Format a header extension as the SDP `a=extmap:` line body (without the leading `a=`). */
internal fun extmapLineSuffix(ext: RtpHeaderExtension): String {
    val dir =
        when (ext.senders) {
            "initiator" -> "/sendonly"
            "responder" -> "/recvonly"
            "none" -> "/inactive"
            else -> ""
        }
    return "extmap:${ext.id}$dir ${ext.uri}"
}

/** Parse an SDP `a=extmap:<id>[/dir] <uri>` line (caller strips the `a=` prefix). */
internal fun parseExtmapLine(rest: String): RtpHeaderExtension? {
    val stripped = rest.removePrefix("extmap:").takeIf { it != rest } ?: return null
    val space = stripped.indexOf(' ')
    if (space < 0) return null
    val idPart = stripped.substring(0, space)
    val uri = stripped.substring(space + 1)
    val slash = idPart.indexOf('/')
    val (idStr, senders) =
        if (slash >= 0) {
            val id = idPart.substring(0, slash)
            val dir = idPart.substring(slash + 1)
            id to
                when (dir) {
                    "sendonly" -> "initiator"
                    "recvonly" -> "responder"
                    "inactive" -> "none"
                    else -> null
                }
        } else {
            idPart to null
        }
    val id = idStr.toIntOrNull() ?: return null
    return RtpHeaderExtension(id = id, uri = uri, senders = senders)
}

// ---------------------------------------------------------------------------
// XEP-0338 BUNDLE group helpers
// ---------------------------------------------------------------------------

/**
 * Parse `a=group:BUNDLE <mid> <mid> ...` out of an SDP string. Returns an
 * empty list when no BUNDLE group is declared. Only the first BUNDLE group
 * is considered — an SDP with multiple groups is nonstandard here.
 */
internal fun extractBundleMids(sdp: String): List<String> {
    for (rawLine in sdp.lineSequence()) {
        val line = rawLine.trimEnd()
        val rest = line.removePrefix("a=group:BUNDLE ")
        if (rest !== line) {
            return rest.split(' ').filter { it.isNotEmpty() }
        }
    }
    return emptyList()
}

/**
 * Build a XEP-0338 `<group semantics='BUNDLE'>` element with `<content
 * name='X'/>` children. Returns `null` for an empty mid list so callers can
 * skip appending an empty group.
 */
internal fun buildBundleGroupElement(mids: List<String>): JingleElement? {
    if (mids.isEmpty()) return null
    val builder =
        JingleElement
            .builder("group", JingleSdp.NS_JINGLE_GROUPING)
            .attr("semantics", "BUNDLE")
    for (mid in mids) {
        builder.child(
            JingleElement
                .builder("content", JingleSdp.NS_JINGLE_GROUPING)
                .attr("name", mid)
                .build(),
        )
    }
    return builder.build()
}

// ---------------------------------------------------------------------------
// Jingle → SDP
// ---------------------------------------------------------------------------

/**
 * Convert parsed Jingle `<content>` elements into a raw SDP string suitable
 * for feeding to `setRemoteDescription()` on the PeerConnection. Rejects an
 * empty input — an offer with no content elements is always a bug.
 */
fun jingleContentsToSdp(contents: List<JingleContent>): String {
    require(contents.isNotEmpty()) { "No Jingle content elements provided" }

    val sdp = StringBuilder()
    // Session-level lines (RFC 4566).
    sdp.append("v=0\r\n")
    sdp.append("o=- 0 0 IN IP4 0.0.0.0\r\n")
    sdp.append("s=-\r\n")
    sdp.append("t=0 0\r\n")

    // BUNDLE group containing every content name.
    sdp.append("a=group:BUNDLE ")
    sdp.append(contents.joinToString(" ") { it.name })
    sdp.append("\r\n")

    for (content in contents) {
        appendContentToSdp(content, sdp)
    }
    return sdp.toString()
}

private fun appendContentToSdp(
    content: JingleContent,
    sdp: StringBuilder,
) {
    val rtpDesc = content.description?.let(::parseRtpDescriptionElement)
    val headerExts = content.description?.let(::parseRtpHeaderExtensions).orEmpty()

    appendMediaHeader(content, rtpDesc, sdp)
    if (rtpDesc != null) appendRtpDescription(rtpDesc, sdp)
    for (ext in headerExts) sdp.append("a=").append(extmapLineSuffix(ext)).append("\r\n")
    content.transport?.let(::parseIceUdpTransportElement)?.let { appendTransport(it, sdp) }
}

private fun appendMediaHeader(
    content: JingleContent,
    rtpDesc: RtpDescription?,
    sdp: StringBuilder,
) {
    val mediaType = rtpDesc?.media?.attr ?: "application"
    val ptIds = rtpDesc?.payloadTypes?.map { it.id.toString() }.orEmpty()
    val ptIdsStr = if (ptIds.isEmpty()) "0" else ptIds.joinToString(" ")
    sdp
        .append("m=")
        .append(mediaType)
        .append(" 9 UDP/TLS/RTP/SAVPF ")
        .append(ptIdsStr)
        .append("\r\n")
    sdp.append("c=IN IP4 0.0.0.0\r\n")
    sdp.append("a=mid:").append(content.name).append("\r\n")
    sdp.append("a=").append(sendersAttr(content.senders)).append("\r\n")
}

private fun sendersAttr(senders: Senders?): String =
    when (senders) {
        Senders.INITIATOR -> "sendonly"
        Senders.RESPONDER -> "recvonly"
        Senders.NONE -> "inactive"
        Senders.BOTH, null -> "sendrecv"
    }

private fun appendRtpDescription(
    rtpDesc: RtpDescription,
    sdp: StringBuilder,
) {
    for (pt in rtpDesc.payloadTypes) appendPayloadType(pt, sdp)
    if (rtpDesc.rtcpMux) sdp.append("a=rtcp-mux\r\n")
}

private fun appendPayloadType(
    pt: RtpPayloadType,
    sdp: StringBuilder,
) {
    val name = pt.name
    if (name != null) {
        val clockrate = pt.clockrate ?: 8000L
        val channelsSuffix = pt.channels?.let { if (it > 1) "/$it" else "" } ?: ""
        sdp
            .append("a=rtpmap:")
            .append(pt.id)
            .append(' ')
            .append(name)
            .append('/')
            .append(clockrate)
            .append(channelsSuffix)
            .append("\r\n")
    }
    if (pt.parameters.isNotEmpty()) {
        sdp.append("a=fmtp:").append(pt.id).append(' ')
        sdp.append(
            pt.parameters.joinToString(";") { param ->
                if (param.value != null) "${param.name}=${param.value}" else param.name
            },
        )
        sdp.append("\r\n")
    }
}

private fun appendTransport(
    transport: IceUdpTransport,
    sdp: StringBuilder,
) {
    transport.ufrag?.let { sdp.append("a=ice-ufrag:").append(it).append("\r\n") }
    transport.pwd?.let { sdp.append("a=ice-pwd:").append(it).append("\r\n") }
    for (fp in transport.fingerprints) {
        sdp
            .append("a=fingerprint:")
            .append(fp.hash)
            .append(' ')
            .append(fp.value)
            .append("\r\n")
    }
    transport.fingerprints.firstNotNullOfOrNull { it.setup }?.let { setup ->
        sdp.append("a=setup:").append(setup.attr).append("\r\n")
    }
    for (c in transport.candidates) appendCandidate(c, sdp)
}

private fun appendCandidate(
    c: IceUdpCandidate,
    sdp: StringBuilder,
) {
    sdp
        .append("a=candidate:")
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
    c.generation?.let { sdp.append(" generation ").append(it) }
    sdp.append("\r\n")
}

// ---------------------------------------------------------------------------
// SDP → Jingle
// ---------------------------------------------------------------------------

/** Accumulated state for a single SDP media section. */
internal class MediaSectionState {
    var mediaType: String? = null
    var mid: String? = null
    val payloadTypes: MutableList<RtpPayloadType> = mutableListOf()
    var rtcpMux: Boolean = false
    var ufrag: String? = null
    var pwd: String? = null
    val fingerprints: MutableList<DtlsFingerprint> = mutableListOf()
    val candidates: MutableList<IceUdpCandidate> = mutableListOf()
    var senders: Senders? = null
    var pendingSetup: FingerprintSetup? = null
    val headerExtensions: MutableList<RtpHeaderExtension> = mutableListOf()

    fun startMediaSection(mLine: String) {
        val parts = mLine.split(' ', limit = 4)
        if (parts.isNotEmpty()) mediaType = parts[0]
        if (parts.size >= 4) {
            for (idStr in parts[3].split(Regex("\\s+"))) {
                val id = idStr.toIntOrNull() ?: continue
                payloadTypes.add(RtpPayloadType.empty(id))
            }
        }
    }

    fun parseAttribute(line: String) {
        if (parseDirectional(line)) return
        if (parseSingleValued(line)) return
        if (parseMultiValued(line)) return
    }

    private fun parseDirectional(line: String): Boolean {
        when (line) {
            "a=sendrecv" -> senders = Senders.BOTH
            "a=sendonly" -> senders = Senders.INITIATOR
            "a=recvonly" -> senders = Senders.RESPONDER
            "a=inactive" -> senders = Senders.NONE
            "a=rtcp-mux" -> rtcpMux = true
            else -> return false
        }
        return true
    }

    private fun parseSingleValued(line: String): Boolean {
        val mid = line.removePrefix("a=mid:")
        if (mid !== line) {
            this.mid = mid
            return true
        }
        val ufrag = line.removePrefix("a=ice-ufrag:")
        if (ufrag !== line) {
            this.ufrag = ufrag
            return true
        }
        val pwd = line.removePrefix("a=ice-pwd:")
        if (pwd !== line) {
            this.pwd = pwd
            return true
        }
        val rtpmap = line.removePrefix("a=rtpmap:")
        if (rtpmap !== line) {
            parseRtpmap(rtpmap)
            return true
        }
        val fmtp = line.removePrefix("a=fmtp:")
        if (fmtp !== line) {
            parseFmtp(fmtp)
            return true
        }
        return false
    }

    private fun parseMultiValued(line: String): Boolean {
        val fingerprintRest = line.removePrefix("a=fingerprint:")
        if (fingerprintRest !== line) {
            parseFingerprint(fingerprintRest)
            return true
        }
        val setupRest = line.removePrefix("a=setup:")
        if (setupRest !== line) {
            parseSetup(setupRest)
            return true
        }
        val candidateRest = line.removePrefix("a=candidate:")
        if (candidateRest !== line) {
            parseSdpCandidateLine(candidateRest)?.let(candidates::add)
            return true
        }
        if (line.startsWith("a=extmap:")) {
            parseExtmapLine(line.removePrefix("a="))?.let(headerExtensions::add)
            return true
        }
        return false
    }

    private fun parseFingerprint(rest: String) {
        val sp = rest.indexOf(' ')
        if (sp <= 0) return
        fingerprints.add(DtlsFingerprint(hash = rest.substring(0, sp), value = rest.substring(sp + 1)))
    }

    /**
     * `a=setup:` applies to every fingerprint in this section (RFC 8122).
     * Apply to any fingerprint that doesn't yet have an explicit setup, and
     * remember as pending so fingerprints parsed later in the same section
     * pick it up at flush time.
     */
    private fun parseSetup(rest: String) {
        val setup = FingerprintSetup.fromAttr(rest) ?: return
        for (fp in fingerprints) if (fp.setup == null) fp.setup = setup
        pendingSetup = setup
    }

    fun flush(
        creator: ContentCreator,
        contents: MutableList<JingleContent>,
    ) {
        if (mediaType == null) return

        val currentMid = mid ?: contents.size.toString()
        val media = MediaType.fromAttr(mediaType!!)

        val desc = RtpDescription(media = media)
        desc.payloadTypes.addAll(payloadTypes)
        desc.rtcpMux = rtcpMux

        // Apply any remaining pendingSetup to fingerprints that lack one.
        pendingSetup?.let { setup ->
            for (fp in fingerprints) if (fp.setup == null) fp.setup = setup
        }

        val transport = IceUdpTransport(ufrag = ufrag, pwd = pwd)
        transport.fingerprints.addAll(fingerprints)
        transport.candidates.addAll(candidates)

        // Attach XEP-0294 `<rtp-hdrext>` children — required for
        // `urn:ietf:params:rtp-hdrext:sdes:mid` BUNDLE demultiplexing.
        val description = buildRtpDescriptionElement(desc)
        for (ext in headerExtensions) {
            val builder =
                JingleElement
                    .builder("rtp-hdrext", JingleSdp.NS_JINGLE_RTP_HDREXT)
                    .attr("id", ext.id.toString())
                    .attr("uri", ext.uri)
                    .attrIfNotNull("senders", ext.senders)
            description.children.add(builder.build())
        }

        contents.add(
            JingleContent(
                creator = creator,
                name = currentMid,
                senders = senders,
                description = description,
                transport = buildIceUdpTransportElement(transport),
            ),
        )

        reset()
    }

    private fun reset() {
        mediaType = null
        mid = null
        payloadTypes.clear()
        rtcpMux = false
        ufrag = null
        pwd = null
        fingerprints.clear()
        candidates.clear()
        senders = null
        pendingSetup = null
        headerExtensions.clear()
    }

    private fun parseRtpmap(rest: String) {
        val sp = rest.indexOf(' ')
        if (sp < 0) return
        val id = rest.substring(0, sp).toIntOrNull() ?: return
        val encoding = rest.substring(sp + 1)
        val parts = encoding.split('/')
        val name = parts.getOrNull(0)
        val clockrate = parts.getOrNull(1)?.toLongOrNull()
        val channels = parts.getOrNull(2)?.toIntOrNull()
        val existing = payloadTypes.firstOrNull { it.id == id }
        if (existing != null) {
            existing.name = name
            existing.clockrate = clockrate
            existing.channels = channels
        } else {
            val pt = RtpPayloadType(id = id, name = name, clockrate = clockrate, channels = channels)
            payloadTypes.add(pt)
        }
    }

    private fun parseFmtp(rest: String) {
        val sp = rest.indexOf(' ')
        if (sp < 0) return
        val id = rest.substring(0, sp).toIntOrNull() ?: return
        val paramsStr = rest.substring(sp + 1)
        val params =
            paramsStr
                .split(';')
                .filter { it.isNotEmpty() }
                .map { p ->
                    val eq = p.indexOf('=')
                    if (eq >= 0) {
                        RtpParameter(name = p.substring(0, eq), value = p.substring(eq + 1))
                    } else {
                        RtpParameter(name = p)
                    }
                }
        payloadTypes.firstOrNull { it.id == id }?.let { it.parameters = params.toMutableList() }
    }
}

/**
 * Parse a raw SDP string line-by-line into Jingle `<content>` elements.
 * Rejects input with no media sections — an SDP with only session-level
 * lines is always a bug here.
 */
fun sdpToJingleContents(
    sdp: String,
    creator: ContentCreator,
): List<JingleContent> {
    val contents: MutableList<JingleContent> = mutableListOf()
    val state = MediaSectionState()

    for (rawLine in sdp.lineSequence()) {
        val line = rawLine.trimEnd()
        val mRest = line.removePrefix("m=")
        if (mRest !== line) {
            state.flush(creator, contents)
            state.startMediaSection(mRest)
        } else {
            state.parseAttribute(line)
        }
    }
    state.flush(creator, contents)

    require(contents.isNotEmpty()) { "No media sections found in SDP" }
    return contents
}

/**
 * Parse the portion after `a=candidate:` into an [IceUdpCandidate]. Accepts
 * the form: `foundation component protocol priority ip port typ type [generation N]`.
 */
internal fun parseSdpCandidateLine(line: String): IceUdpCandidate? {
    val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.size < 8) return null
    val foundation = parts[0]
    val component = parts[1].toIntOrNull() ?: return null
    val protocol = parts[2]
    val priority = parts[3].toLongOrNull() ?: return null
    val ip = parts[4]
    val port = parts[5].toIntOrNull() ?: return null
    if (parts[6] != "typ") return null
    val candidateType = CandidateType.fromAttr(parts[7])

    var generation: Int? = null
    var i = 8
    while (i + 1 < parts.size) {
        if (parts[i] == "generation") {
            generation = parts[i + 1].toIntOrNull()
        }
        i += 2
    }

    return IceUdpCandidate(
        foundation = foundation,
        component = component,
        protocol = protocol,
        priority = priority,
        ip = ip,
        port = port,
        candidateType = candidateType,
        generation = generation,
    )
}

// ---------------------------------------------------------------------------
// High-level helpers
// ---------------------------------------------------------------------------

/**
 * Extract an SDP offer string from a `<jingle>` element by parsing its
 * `<content>` children with standard Jingle/RTP/ICE-UDP types.
 */
fun extractSdpOfferFromJingle(jingle: JingleElement): String {
    val contents =
        jingle
            .childrenOfTag("content", Xep0166.NS)
            .mapNotNull(::parseJingleContentElement)
    require(contents.isNotEmpty()) { "No <content> elements found in Jingle element" }
    return jingleContentsToSdp(contents)
}

/**
 * Build a `<jingle action="session-accept">` element from an SDP answer
 * string. Emits the XEP-0338 `<group semantics='BUNDLE'>` element when the
 * answer advertises `a=group:BUNDLE ...`. Without that group,
 * browsers/stanza.js reconstruct separate transports per m-section and the
 * non-primary ones never complete ICE — media is silently lost.
 */
fun buildJingleSessionAccept(
    sid: String,
    responder: String,
    sdpAnswer: String,
): JingleElement {
    val contents = sdpToJingleContents(sdpAnswer, ContentCreator.INITIATOR)
    val bundleMids = extractBundleMids(sdpAnswer)

    val jingle =
        JingleElement
            .builder("jingle", Xep0166.NS)
            .attr("action", "session-accept")
            .attr("sid", sid)
            .attr("responder", responder)
            .build()

    buildBundleGroupElement(bundleMids)?.let(jingle.children::add)
    for (content in contents) jingle.children.add(buildJingleContentElement(content))
    return jingle
}

/** Read the `sid` attribute from a Jingle element. */
fun extractSid(jingle: JingleElement): String? = jingle.attr("sid")

/** Read the `action` attribute from a Jingle element. */
fun extractAction(jingle: JingleElement): String? = jingle.attr("action")

/**
 * Build a Jingle `session-info` element containing Waddle's custom
 * `<participant-map xmlns="urn:waddle:sfu:participant-map:0">` with
 * `<entry msid="..." jid="..."/>` children.
 */
fun buildParticipantMap(
    sid: String,
    mappings: List<Pair<String, String>>,
): JingleElement {
    val participantMap = JingleElement.builder("participant-map", JingleSdp.NS_PARTICIPANT_MAP)
    for ((msid, jid) in mappings) {
        participantMap.child(
            JingleElement
                .builder("entry", JingleSdp.NS_PARTICIPANT_MAP)
                .attr("msid", msid)
                .attr("jid", jid)
                .build(),
        )
    }
    return JingleElement
        .builder("jingle", Xep0166.NS)
        .attr("action", "session-info")
        .attr("sid", sid)
        .child(participantMap.build())
        .build()
}
