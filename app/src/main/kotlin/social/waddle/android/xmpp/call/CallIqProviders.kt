package social.waddle.android.xmpp.call

import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.IqData
import org.jivesoftware.smack.packet.XmlEnvironment
import org.jivesoftware.smack.parsing.SmackParsingException
import org.jivesoftware.smack.provider.IqProvider
import org.jivesoftware.smack.util.PacketParserUtils
import org.jivesoftware.smack.xml.XmlPullParser
import org.jxmpp.JxmppContext
import social.waddle.android.xmpp.ExternalService

/**
 * Namespaces used for call signaling on the wire. Centralized so the IQ
 * providers, IQ subclasses, and XEP-0482 message extensions stay in sync.
 */
object CallNamespaces {
    const val JINGLE: String = "urn:xmpp:jingle:1"
    const val EXT_DISCO: String = "urn:xmpp:extdisco:2"
    const val CALL_INVITES: String = "urn:xmpp:call-invites:0"
    const val MEETING: String = "urn:xmpp:meeting:0"
    const val JINGLE_MESSAGE: String = "urn:xmpp:jingle-message:0"
}

// ---------------------------------------------------------------------------
// Generic Jingle IQ carrier
// ---------------------------------------------------------------------------

/**
 * Smack IQ that carries a Jingle element (namespace [CallNamespaces.JINGLE]).
 *
 * Attributes of the outer `<jingle>` element are kept separately from inner
 * children so Smack's `getIQChildElementBuilder` helper can emit the
 * attributes through its native API (which handles escaping and stream
 * defaults). Children are kept as pre-serialized XML since they cross
 * multiple namespaces (XEP-0167, -0176, -0320, -0338, -0294) and re-typing
 * them here is the Jingle bridge's job, not Smack's.
 */
class JingleIQ(
    val attributes: Map<String, String>,
    val innerXml: String,
) : IQ(ELEMENT, CallNamespaces.JINGLE) {
    override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
        for ((k, v) in attributes) xml.attribute(k, v)
        if (innerXml.isEmpty()) {
            xml.setEmptyElement()
            return xml
        }
        xml.rightAngleBracket()
        xml.append(innerXml)
        return xml
    }

    companion object {
        const val ELEMENT: String = "jingle"
    }
}

/**
 * IQ provider for `<jingle xmlns='urn:xmpp:jingle:1'>`. Captures the outer
 * attributes and the children subtree as raw XML so the SDP bridge can
 * parse it with its own typed model.
 */
class JingleIQProvider : IqProvider<JingleIQ>() {
    override fun parse(
        parser: XmlPullParser,
        initialDepth: Int,
        iqData: IqData,
        xmlEnvironment: XmlEnvironment?,
        jxmppContext: JxmppContext?,
    ): JingleIQ {
        val attrs = parseOuterAttributes(parser)
        val inner = readInnerXml(parser)
        return JingleIQ(attrs, inner)
    }

    private fun parseOuterAttributes(parser: XmlPullParser): Map<String, String> =
        (0 until parser.attributeCount)
            .mapNotNull { i ->
                val name = parser.getAttributeName(i) ?: return@mapNotNull null
                val value = parser.getAttributeValue(i) ?: return@mapNotNull null
                if (name == "xmlns") null else name to value
            }.toMap()

    private fun readInnerXml(parser: XmlPullParser): String {
        val startDepth = parser.depth
        val inner = StringBuilder()
        while (true) {
            val event = parser.next()
            if (dispatchEvent(parser, event, inner, startDepth)) return inner.toString()
        }
    }

    /**
     * Returns true when the outer end tag has been consumed and parsing
     * should stop. Text and START_ELEMENT append into [inner]; unknown
     * events are ignored; an unexpected EOF throws. Splitting this out of
     * [readInnerXml] keeps the outer loop cyclomatically cheap.
     */
    private fun dispatchEvent(
        parser: XmlPullParser,
        event: XmlPullParser.Event,
        inner: StringBuilder,
        startDepth: Int,
    ): Boolean {
        when (event) {
            XmlPullParser.Event.START_ELEMENT -> {
                inner.append(PacketParserUtils.parseElement(parser))
            }

            XmlPullParser.Event.TEXT_CHARACTERS -> {
                inner.append(xmlText(parser.text.orEmpty()))
            }

            XmlPullParser.Event.END_ELEMENT -> {
                if (parser.depth <= startDepth) return true
            }

            XmlPullParser.Event.END_DOCUMENT -> {
                throw SmackParsingException("Unexpected EOF inside <jingle>")
            }

            else -> {
                Unit
            }
        }
        return false
    }
}

// ---------------------------------------------------------------------------
// XEP-0215 External Service Discovery
// ---------------------------------------------------------------------------

/** Request stanza for `<services xmlns='urn:xmpp:extdisco:2'/>`. */
class ExtDiscoRequestIQ : IQ("services", CallNamespaces.EXT_DISCO) {
    init {
        type = Type.get
    }

    override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
        xml.setEmptyElement()
        return xml
    }
}

/** Result stanza carrying the parsed service list. */
class ExtDiscoResultIQ(
    val services: List<ExternalService>,
) : IQ("services", CallNamespaces.EXT_DISCO) {
    override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
        xml.rightAngleBracket()
        for (svc in services) {
            xml.halfOpenElement("service")
            xml.attribute("host", svc.host)
            xml.attribute("port", svc.port.toString())
            xml.attribute("type", svc.type)
            svc.transport?.let { xml.attribute("transport", it) }
            svc.username?.let { xml.attribute("username", it) }
            svc.password?.let { xml.attribute("password", it) }
            if (svc.restricted) xml.attribute("restricted", "1")
            svc.expires?.let { xml.attribute("expires", it) }
            xml.closeEmptyElement()
        }
        return xml
    }
}

class ExtDiscoIQProvider : IqProvider<ExtDiscoResultIQ>() {
    override fun parse(
        parser: XmlPullParser,
        initialDepth: Int,
        iqData: IqData,
        xmlEnvironment: XmlEnvironment?,
        jxmppContext: JxmppContext?,
    ): ExtDiscoResultIQ {
        val services = mutableListOf<ExternalService>()
        val rootDepth = parser.depth
        while (true) {
            val event = parser.next()
            when (event) {
                XmlPullParser.Event.START_ELEMENT -> {
                    if (parser.name == "service") {
                        parseServiceElement(parser)?.let(services::add)
                    }
                }

                XmlPullParser.Event.END_ELEMENT -> {
                    if (parser.depth <= rootDepth) return ExtDiscoResultIQ(services)
                }

                XmlPullParser.Event.END_DOCUMENT -> {
                    throw SmackParsingException("Unexpected EOF inside <services>")
                }

                else -> {
                    Unit
                }
            }
        }
    }

    private fun parseServiceElement(parser: XmlPullParser): ExternalService? {
        val attrs =
            (0 until parser.attributeCount).associate {
                parser.getAttributeName(it) to parser.getAttributeValue(it)
            }
        val host = attrs["host"]?.takeIf { it.isNotEmpty() } ?: return null
        val port = attrs["port"]?.toIntOrNull() ?: return null
        val type = attrs["type"]?.takeIf { it.isNotEmpty() } ?: return null
        return ExternalService(
            host = host,
            port = port,
            type = type,
            transport = attrs["transport"],
            username = attrs["username"],
            password = attrs["password"],
            restricted = attrs["restricted"] == "1" || attrs["restricted"] == "true",
            expires = attrs["expires"],
        )
    }
}

private fun xmlText(value: String): String =
    buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(c)
            }
        }
    }
