package social.waddle.android.call.jingle

/**
 * Minimal in-memory XML element model that mirrors the subset of
 * `minidom::Element` used by the server's `sfu/sdp.rs`. Kept Smack-agnostic so
 * the SDP↔Jingle bridge can be unit-tested without spinning up an XMPP
 * connection; a separate adapter (see [JingleSmackAdapter]) converts to and
 * from Smack's `XmlElement` when interacting with the wire.
 *
 * Only the features the Jingle port needs are modeled:
 *  - qualified name (local name + namespace)
 *  - attributes (order-preserving, string-keyed)
 *  - children (element-only — Jingle never mixes text and element children)
 *  - text (leaf-only — fingerprints carry the digest string)
 *
 * Namespaces on children are explicit, not inherited from the parent: Jingle
 * contains several sibling namespaces (RTP, ICE-UDP, DTLS) and each child
 * element carries its own `xmlns` attribute on the wire.
 */
data class JingleElement(
    val name: String,
    val namespace: String,
    val attributes: LinkedHashMap<String, String> = LinkedHashMap(),
    val children: MutableList<JingleElement> = mutableListOf(),
    var text: String = "",
) {
    fun attr(key: String): String? = attributes[key]

    /**
     * Local-name + namespace match. Lenient about the namespace when the
     * element appears with inherited default namespace `urn:xmpp:jingle:1`
     * — this is what the Waddle SFU emits (and what strict XEP parsers
     * reject). Extension elements nested inside a `<jingle>` IQ are
     * identifiable by local name alone because the Jingle schema doesn't
     * allow arbitrary unknown children, and the web client is lenient too.
     */
    fun isTag(
        localName: String,
        ns: String,
    ): Boolean {
        if (name != localName) return false
        if (namespace == ns) return true
        // Accept inherited / unqualified namespaces. The SFU frequently
        // emits inner elements without xmlns, which XML parses as the
        // parent Jingle namespace (`urn:xmpp:jingle:1`). Strict matching
        // would cause parseRtpDescription / parseIceUdpTransport to return
        // null, and the downstream SDP would lose its media sections.
        return namespace == JINGLE_DEFAULT_NAMESPACE || namespace.isEmpty()
    }

    fun firstChild(
        localName: String,
        ns: String,
    ): JingleElement? = children.firstOrNull { it.isTag(localName, ns) }

    fun childrenOfTag(
        localName: String,
        ns: String,
    ): List<JingleElement> = children.filter { it.isTag(localName, ns) }

    /**
     * Serialize to an XML string. Used for sending (Smack accepts an XML
     * string payload for extension elements) and for snapshotting in tests.
     * Produces attribute-quoted output with no whitespace between elements.
     */
    fun toXmlString(): String {
        val sb = StringBuilder()
        appendTo(sb)
        return sb.toString()
    }

    private fun appendTo(sb: StringBuilder) {
        sb.append('<').append(name)
        sb.append(" xmlns='").append(xmlEscape(namespace)).append('\'')
        for ((k, v) in attributes) {
            sb
                .append(' ')
                .append(k)
                .append("='")
                .append(xmlEscape(v))
                .append('\'')
        }
        if (children.isEmpty() && text.isEmpty()) {
            sb.append("/>")
            return
        }
        sb.append('>')
        if (text.isNotEmpty()) sb.append(xmlEscape(text))
        for (child in children) child.appendTo(sb)
        sb.append("</").append(name).append('>')
    }

    class Builder(
        private val element: JingleElement,
    ) {
        fun attr(
            key: String,
            value: String,
        ): Builder {
            element.attributes[key] = value
            return this
        }

        fun attrIfNotNull(
            key: String,
            value: String?,
        ): Builder {
            if (value != null) element.attributes[key] = value
            return this
        }

        fun child(child: JingleElement): Builder {
            element.children.add(child)
            return this
        }

        fun children(more: Iterable<JingleElement>): Builder {
            for (c in more) element.children.add(c)
            return this
        }

        fun text(value: String): Builder {
            element.text = value
            return this
        }

        fun build(): JingleElement = element
    }

    companion object {
        /** XEP-0166 Jingle namespace. Used when checking inherited default namespaces. */
        const val JINGLE_DEFAULT_NAMESPACE: String = "urn:xmpp:jingle:1"

        fun builder(
            name: String,
            namespace: String,
        ): Builder = Builder(JingleElement(name = name, namespace = namespace))

        fun xmlEscape(value: String): String {
            if (value.isEmpty()) return value
            val sb = StringBuilder(value.length)
            for (c in value) {
                when (c) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    '\'' -> sb.append("&apos;")
                    '"' -> sb.append("&quot;")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
