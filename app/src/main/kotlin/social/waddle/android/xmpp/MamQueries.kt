package social.waddle.android.xmpp

import org.jivesoftware.smack.filter.StanzaFilter
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smackx.mam.element.MamElements

internal class MamHistoryQueryIq(
    private val queryId: String,
    private val maxResults: Int,
    private val afterId: String? = null,
    private val beforeId: String?,
    private val fullText: String? = null,
) : IQ("query", MAM_NAMESPACE) {
    override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
        xml.attribute("queryid", queryId)
        xml.rightAngleBracket()
        appendDataForm(xml, withJid = null, fullText = fullText)
        xml
            .halfOpenElement("set")
            .xmlnsAttribute(RSM_NAMESPACE)
            .rightAngleBracket()
            .element("max", maxResults.toString())
        appendAfter(xml, afterId)
        appendBefore(xml, beforeId)
        xml
            .closeElement("set")
        return xml
    }
}

internal class MamDirectHistoryQueryIq(
    private val queryId: String,
    private val maxResults: Int,
    private val peerJid: String,
    private val afterId: String? = null,
    private val beforeId: String?,
    private val fullText: String? = null,
) : IQ("query", MAM_NAMESPACE) {
    override fun getIQChildElementBuilder(xml: IQChildElementXmlStringBuilder): IQChildElementXmlStringBuilder {
        xml.attribute("queryid", queryId)
        xml.rightAngleBracket()
        appendDataForm(xml, withJid = peerJid, fullText = fullText)
        xml
            .halfOpenElement("set")
            .xmlnsAttribute(RSM_NAMESPACE)
            .rightAngleBracket()
            .element("max", maxResults.toString())
        appendAfter(xml, afterId)
        appendBefore(xml, beforeId)
        xml
            .closeElement("set")
        return xml
    }
}

private fun appendDataForm(
    xml: IQ.IQChildElementXmlStringBuilder,
    withJid: String?,
    fullText: String?,
) {
    if (withJid.isNullOrBlank() && fullText.isNullOrBlank()) {
        return
    }
    xml
        .halfOpenElement("x")
        .xmlnsAttribute("jabber:x:data")
        .attribute("type", "submit")
        .rightAngleBracket()
    appendField(xml, "FORM_TYPE", MAM_NAMESPACE, hidden = true)
    withJid?.takeIf { it.isNotBlank() }?.let { appendField(xml, "with", it) }
    fullText?.takeIf { it.isNotBlank() }?.let { appendField(xml, "fulltext", it) }
    xml.closeElement("x")
}

private fun appendField(
    xml: IQ.IQChildElementXmlStringBuilder,
    name: String,
    value: String,
    hidden: Boolean = false,
) {
    xml
        .halfOpenElement("field")
        .attribute("var", name)
    if (hidden) {
        xml.attribute("type", "hidden")
    }
    xml
        .rightAngleBracket()
        .element("value", value)
        .closeElement("field")
}

private fun appendAfter(
    xml: IQ.IQChildElementXmlStringBuilder,
    afterId: String?,
) {
    afterId?.takeIf { it.isNotBlank() }?.let { xml.element("after", it) }
}

private fun appendBefore(
    xml: IQ.IQChildElementXmlStringBuilder,
    beforeId: String?,
) {
    beforeId?.takeIf { it.isNotBlank() }?.let { xml.element("before", it) }
}

internal class MamHistoryFilter(
    private val queryId: String,
    private val iqId: String,
) : StanzaFilter {
    override fun accept(stanza: Stanza): Boolean =
        when (stanza) {
            is Message -> MamElements.MamResultExtension.from(stanza)?.queryId == queryId
            is IQ -> stanza.stanzaId == iqId
            else -> false
        }
}

internal const val MAM_NAMESPACE = "urn:xmpp:mam:2"
internal const val RSM_NAMESPACE = "http://jabber.org/protocol/rsm"
