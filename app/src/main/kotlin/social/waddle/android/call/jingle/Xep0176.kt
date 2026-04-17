package social.waddle.android.call.jingle

/**
 * XEP-0176: Jingle ICE-UDP Transport Method.
 *
 * Port of `server/crates/waddle-xmpp/src/xep/xep0176.rs`.
 */
object Xep0176 {
    const val NS: String = "urn:xmpp:jingle:transports:ice-udp:1"
}

sealed class CandidateType(
    val attr: String,
) {
    data object Host : CandidateType("host")

    data object Srflx : CandidateType("srflx")

    data object Prflx : CandidateType("prflx")

    data object Relay : CandidateType("relay")

    data class Other(
        val raw: String,
    ) : CandidateType(raw)

    companion object {
        fun fromAttr(value: String): CandidateType =
            when (value) {
                "host" -> Host
                "srflx" -> Srflx
                "prflx" -> Prflx
                "relay" -> Relay
                else -> Other(value)
            }
    }

    override fun toString(): String = attr
}

data class IceUdpCandidate(
    val foundation: String,
    val component: Int,
    val protocol: String,
    val priority: Long,
    val ip: String,
    val port: Int,
    val candidateType: CandidateType,
    val generation: Int? = null,
)

data class IceUdpTransport(
    var ufrag: String? = null,
    var pwd: String? = null,
    val fingerprints: MutableList<DtlsFingerprint> = mutableListOf(),
    val candidates: MutableList<IceUdpCandidate> = mutableListOf(),
)

fun JingleElement.isIceUdpTransport(): Boolean = isTag("transport", Xep0176.NS)

fun parseCandidateElement(elem: JingleElement): IceUdpCandidate? {
    if (!elem.isTag("candidate", Xep0176.NS)) return null
    val foundation = elem.attr("foundation")?.takeIf { it.isNotEmpty() } ?: return null
    val component = elem.attr("component")?.toIntOrNull() ?: return null
    val protocol = elem.attr("protocol")?.takeIf { it.isNotEmpty() } ?: return null
    val priority = elem.attr("priority")?.toLongOrNull() ?: return null
    val ip = elem.attr("ip")?.takeIf { it.isNotEmpty() } ?: return null
    val port = elem.attr("port")?.toIntOrNull() ?: return null
    val typeAttr = elem.attr("type") ?: return null
    val generation = elem.attr("generation")?.toIntOrNull()
    return IceUdpCandidate(
        foundation = foundation,
        component = component,
        protocol = protocol,
        priority = priority,
        ip = ip,
        port = port,
        candidateType = CandidateType.fromAttr(typeAttr),
        generation = generation,
    )
}

fun buildCandidateElement(candidate: IceUdpCandidate): JingleElement =
    JingleElement
        .builder("candidate", Xep0176.NS)
        .attr("foundation", candidate.foundation)
        .attr("component", candidate.component.toString())
        .attr("protocol", candidate.protocol)
        .attr("priority", candidate.priority.toString())
        .attr("ip", candidate.ip)
        .attr("port", candidate.port.toString())
        .attr("type", candidate.candidateType.attr)
        .attrIfNotNull("generation", candidate.generation?.toString())
        .build()

fun parseIceUdpTransportElement(elem: JingleElement): IceUdpTransport? {
    if (!elem.isIceUdpTransport()) return null
    val transport = IceUdpTransport()
    transport.ufrag = elem.attr("ufrag")?.takeIf { it.isNotEmpty() }
    transport.pwd = elem.attr("pwd")?.takeIf { it.isNotEmpty() }
    for (child in elem.children) {
        when {
            child.isFingerprint() -> {
                parseFingerprintElement(child)?.let(transport.fingerprints::add)
            }

            child.isTag("candidate", Xep0176.NS) -> {
                parseCandidateElement(child)?.let(transport.candidates::add)
            }
        }
    }
    return transport
}

fun buildIceUdpTransportElement(transport: IceUdpTransport): JingleElement {
    val builder = JingleElement.builder("transport", Xep0176.NS)
    transport.ufrag?.let { builder.attr("ufrag", it) }
    transport.pwd?.let { builder.attr("pwd", it) }
    for (fp in transport.fingerprints) builder.child(buildFingerprintElement(fp))
    for (c in transport.candidates) builder.child(buildCandidateElement(c))
    return builder.build()
}
