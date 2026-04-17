package social.waddle.android.call.jingle

/**
 * XEP-0167: Jingle RTP Sessions.
 *
 * Port of `server/crates/waddle-xmpp/src/xep/xep0167.rs`.
 */
object Xep0167 {
    const val NS: String = "urn:xmpp:jingle:apps:rtp:1"
}

sealed class MediaType(
    val attr: String,
) {
    data object Audio : MediaType("audio")

    data object Video : MediaType("video")

    data object Application : MediaType("application")

    data class Other(
        val raw: String,
    ) : MediaType(raw)

    companion object {
        fun fromAttr(value: String): MediaType =
            when (value) {
                "audio" -> Audio
                "video" -> Video
                "application" -> Application
                else -> Other(value)
            }
    }

    override fun toString(): String = attr
}

data class RtpParameter(
    val name: String,
    val value: String? = null,
)

data class RtpPayloadType(
    val id: Int,
    var name: String? = null,
    var clockrate: Long? = null,
    var channels: Int? = null,
    var parameters: MutableList<RtpParameter> = mutableListOf(),
) {
    companion object {
        fun empty(id: Int): RtpPayloadType = RtpPayloadType(id = id)
    }
}

data class RtpDescription(
    val media: MediaType,
    val payloadTypes: MutableList<RtpPayloadType> = mutableListOf(),
    var rtcpMux: Boolean = false,
)

fun JingleElement.isRtpDescription(): Boolean = isTag("description", Xep0167.NS)

fun parseRtpDescriptionElement(elem: JingleElement): RtpDescription? {
    if (!elem.isRtpDescription()) return null
    val mediaAttr = elem.attr("media") ?: return null
    val description = RtpDescription(media = MediaType.fromAttr(mediaAttr))

    for (child in elem.children) {
        when {
            child.isTag("payload-type", Xep0167.NS) -> parsePayloadType(child)?.let(description.payloadTypes::add)
            child.isTag("rtcp-mux", Xep0167.NS) -> description.rtcpMux = true
        }
    }
    return description
}

private fun parsePayloadType(elem: JingleElement): RtpPayloadType? {
    val id = elem.attr("id")?.toIntOrNull() ?: return null
    val pt = RtpPayloadType(id = id)
    pt.name = elem.attr("name")?.takeIf { it.isNotEmpty() }
    pt.clockrate = elem.attr("clockrate")?.toLongOrNull()
    pt.channels = elem.attr("channels")?.toIntOrNull()
    elem.children
        .filter { it.isTag("parameter", Xep0167.NS) }
        .mapNotNull(::parseParameterElement)
        .forEach(pt.parameters::add)
    return pt
}

private fun parseParameterElement(elem: JingleElement): RtpParameter? {
    val name = elem.attr("name")?.takeIf { it.isNotEmpty() } ?: return null
    val value = elem.attr("value")?.takeIf { it.isNotEmpty() }
    return RtpParameter(name = name, value = value)
}

fun buildRtpDescriptionElement(description: RtpDescription): JingleElement {
    val builder =
        JingleElement
            .builder("description", Xep0167.NS)
            .attr("media", description.media.attr)

    for (pt in description.payloadTypes) builder.child(buildPayloadTypeElement(pt))
    if (description.rtcpMux) {
        builder.child(JingleElement.builder("rtcp-mux", Xep0167.NS).build())
    }
    return builder.build()
}

private fun buildPayloadTypeElement(pt: RtpPayloadType): JingleElement {
    val builder =
        JingleElement
            .builder("payload-type", Xep0167.NS)
            .attr("id", pt.id.toString())
            .attrIfNotNull("name", pt.name)
            .attrIfNotNull("clockrate", pt.clockrate?.toString())
            .attrIfNotNull("channels", pt.channels?.toString())

    for (param in pt.parameters) {
        builder.child(
            JingleElement
                .builder("parameter", Xep0167.NS)
                .attr("name", param.name)
                .attrIfNotNull("value", param.value)
                .build(),
        )
    }
    return builder.build()
}
