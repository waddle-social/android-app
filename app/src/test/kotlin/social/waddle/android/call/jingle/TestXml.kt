package social.waddle.android.call.jingle

import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element as DomElement

/**
 * Parse an XML string into a [JingleElement] using the JVM's DOM parser.
 * Test-only helper — production code uses Smack's parsers, which are not
 * available (and not needed) for isolated unit tests of the SDP bridge.
 */
fun jingleFromXml(xml: String): JingleElement {
    val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
    val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    return convert(doc.documentElement)
}

private fun convert(dom: DomElement): JingleElement {
    val ns = dom.namespaceURI ?: ""
    val element = JingleElement(name = dom.localName ?: dom.tagName, namespace = ns)

    val attrs = dom.attributes
    for (i in 0 until attrs.length) {
        val attr = attrs.item(i)
        val name = attr.nodeName
        // Skip xmlns declarations — namespace is already captured on the element.
        if (name == "xmlns" || name.startsWith("xmlns:")) continue
        element.attributes[name] = attr.nodeValue
    }

    val children = dom.childNodes
    val textBuilder = StringBuilder()
    for (i in 0 until children.length) {
        val child = children.item(i)
        when (child.nodeType) {
            Node.ELEMENT_NODE -> element.children.add(convert(child as DomElement))
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> textBuilder.append(child.nodeValue)
        }
    }
    // Trim — DOM returns whitespace between elements as text nodes. Jingle
    // leaves (fingerprint value) carry their own content which never contains
    // meaningful whitespace.
    val text = textBuilder.toString().trim()
    if (text.isNotEmpty()) element.text = text

    return element
}
