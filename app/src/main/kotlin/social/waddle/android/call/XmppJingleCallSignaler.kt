package social.waddle.android.call

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import social.waddle.android.call.jingle.ContentCreator
import social.waddle.android.call.jingle.JingleElement
import social.waddle.android.call.jingle.Xep0166
import social.waddle.android.call.jingle.Xep0176
import social.waddle.android.call.jingle.buildJingleContentElement
import social.waddle.android.call.jingle.extractBundleMids
import social.waddle.android.call.jingle.sdpToJingleContents
import social.waddle.android.util.WaddleLog
import social.waddle.android.xmpp.ExternalService
import social.waddle.android.xmpp.InboundCallSignal
import social.waddle.android.xmpp.XmppClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wire-format signaler that speaks the Waddle server's Jingle dialect by
 * delegating to [XmppClient] for stanza transport and [JingleSdpBridge] for
 * SDP↔Jingle translation.
 *
 *  - XEP-0482 `<invite>` / `<reject>` / `<left>` messages (call lifecycle)
 *  - XEP-0166 Jingle IQs addressed to the SFU
 *    (`session-initiate` / `-accept` / `-terminate`, `transport-info`)
 *  - XEP-0215 ExtDisco for STUN/TURN
 *
 * Pre-existing concern: this class stays stateless. Session state lives in
 * [CallController] which owns the SID ↔ SFU mapping.
 */
@Singleton
class XmppJingleCallSignaler
    @Inject
    constructor(
        private val xmppClient: XmppClient,
        private val bridge: JingleSdpBridge,
    ) : CallSignaler {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var collectorJob: Job? = null

        private val mutableEvents =
            MutableSharedFlow<CallSignalEvent>(
                replay = 0,
                extraBufferCapacity = EVENT_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
        override val events: Flow<CallSignalEvent> = mutableEvents.asSharedFlow()

        init {
            collectorJob = scope.launch { xmppClient.callSignals.collect(::onInbound) }
        }

        /** Cancel the collector — intended for tests, not production lifecycles. */
        fun shutdown() {
            collectorJob?.cancel()
            scope.cancel()
        }

        override suspend fun sendInvite(
            peerJid: String,
            sid: String,
        ) {
            // The signaler is the only caller of sendCallInvite; it knows the
            // SFU jid because the controller set it before calling us. In the
            // current API the SFU jid defaults to `sfu.{domain}` which we
            // derive from the peer's bare jid.
            val sfuJid = peerJid.substringAfter('@').let { "sfu.$it" }
            xmppClient.sendCallInvite(
                peerJid = peerJid,
                sid = sid,
                sfuJid = sfuJid,
                muji = false,
                video = true,
            )
        }

        override suspend fun sendSessionInitiate(
            sfuJid: String,
            sid: String,
            offer: SessionDescription,
        ) {
            val jingle =
                buildJingleFromSdp(
                    action = "session-initiate",
                    sid = sid,
                    initiator = xmppClient.connectionState.value.bareJid(),
                    responder = null,
                    sdp = offer.description,
                )
            xmppClient.sendJingleIq(to = sfuJid, jingleXml = jingle.toXmlString())
        }

        override suspend fun sendSessionAccept(
            sfuJid: String,
            sid: String,
            answer: SessionDescription,
        ) {
            val jingle =
                buildJingleFromSdp(
                    action = "session-accept",
                    sid = sid,
                    initiator = null,
                    responder = xmppClient.connectionState.value.bareJid(),
                    sdp = answer.description,
                )
            xmppClient.sendJingleIq(to = sfuJid, jingleXml = jingle.toXmlString())
        }

        override suspend fun sendTransportInfo(
            sfuJid: String,
            sid: String,
            candidate: IceCandidate,
        ) {
            val candidateElement = bridge.candidateToJingleElement(candidate)
            // Wrap in a <content>/<transport> envelope so the SFU knows which
            // m-section the candidate targets. sdpMid maps to the content name.
            val transport =
                JingleElement
                    .builder("transport", Xep0176.NS)
                    .child(candidateElement)
                    .build()
            val content =
                JingleElement
                    .builder("content", Xep0166.NS)
                    .attr("creator", "initiator")
                    .attr("name", candidate.sdpMid ?: "0")
                    .child(transport)
                    .build()
            val jingle =
                JingleElement
                    .builder("jingle", Xep0166.NS)
                    .attr("action", "transport-info")
                    .attr("sid", sid)
                    .child(content)
                    .build()
            xmppClient.sendJingleIq(to = sfuJid, jingleXml = jingle.toXmlString())
        }

        override suspend fun sendSessionTerminate(
            sfuJid: String,
            sid: String,
            reason: String?,
        ) {
            val jingle =
                JingleElement
                    .builder("jingle", Xep0166.NS)
                    .attr("action", "session-terminate")
                    .attr("sid", sid)
                    .child(buildReasonElement(reason))
                    .build()
            xmppClient.sendJingleIq(to = sfuJid, jingleXml = jingle.toXmlString())
        }

        override suspend fun discoverIceServers(): List<IceServerConfig> {
            val services = xmppClient.discoverExternalServices()
            if (services.isEmpty()) return emptyList()
            return services.mapNotNull(::toIceServerConfig)
        }

        private fun buildJingleFromSdp(
            action: String,
            sid: String,
            initiator: String?,
            responder: String?,
            sdp: String,
        ): JingleElement {
            val contents = sdpToJingleContents(sdp, ContentCreator.INITIATOR)
            val bundleMids = extractBundleMids(sdp)

            val builder =
                JingleElement
                    .builder("jingle", Xep0166.NS)
                    .attr("action", action)
                    .attr("sid", sid)
                    .attrIfNotNull("initiator", initiator)
                    .attrIfNotNull("responder", responder)

            if (bundleMids.isNotEmpty()) {
                val group =
                    JingleElement
                        .builder("group", social.waddle.android.call.jingle.JingleSdp.NS_JINGLE_GROUPING)
                        .attr("semantics", "BUNDLE")
                for (mid in bundleMids) {
                    group.child(
                        JingleElement
                            .builder("content", social.waddle.android.call.jingle.JingleSdp.NS_JINGLE_GROUPING)
                            .attr("name", mid)
                            .build(),
                    )
                }
                builder.child(group.build())
            }
            for (content in contents) builder.child(buildJingleContentElement(content))
            return builder.build()
        }

        private fun buildReasonElement(reason: String?): JingleElement {
            val condition = reason?.takeIf { it.isNotBlank() } ?: "success"
            return JingleElement
                .builder("reason", Xep0166.NS)
                .child(JingleElement.builder(condition, Xep0166.NS).build())
                .build()
        }

        /**
         * XEP-0215 `<service>` → WebRTC-friendly [IceServerConfig]. STUN goes
         * through as a single-url host; TURN expands to `turn:` or `turns:`
         * with the declared transport suffix, following the url scheme used
         * by the Waddle web client. Unknown service types are dropped
         * silently — WebRTC can't use them.
         */
        private fun toIceServerConfig(service: ExternalService): IceServerConfig? {
            val scheme =
                when (service.type.lowercase()) {
                    "stun" -> "stun"
                    "stuns" -> "stuns"
                    "turn" -> "turn"
                    "turns" -> "turns"
                    else -> return null
                }
            val transportSuffix =
                service.transport?.lowercase()?.let { transport ->
                    if ((transport == "udp") && (scheme == "turn" || scheme == "stun")) {
                        ""
                    } else {
                        "?transport=$transport"
                    }
                } ?: ""
            val url = "$scheme:${service.host}:${service.port}$transportSuffix"
            return IceServerConfig(
                urls = listOf(url),
                username = service.username,
                credential = service.password,
            )
        }

        private suspend fun onInbound(signal: InboundCallSignal) {
            val event: CallSignalEvent? =
                when (signal) {
                    is InboundCallSignal.Invite -> {
                        CallSignalEvent.IncomingInvite(
                            sid = signal.sid,
                            fromJid = signal.fromJid.substringBeforeLast('/'),
                            fromDisplayName = null,
                            sfuJid = signal.jingleJid,
                            muji = signal.muji,
                            meetingDescription = signal.meetingDescription,
                        )
                    }

                    is InboundCallSignal.Reject -> {
                        CallSignalEvent.RemoteTerminate(sid = signal.sid, reason = "decline")
                    }

                    is InboundCallSignal.Left -> {
                        CallSignalEvent.RemoteTerminate(sid = signal.sid, reason = "cancel")
                    }

                    is InboundCallSignal.Jingle -> {
                        mapJingleSignal(signal.fromJid, signal.jingleXml)
                    }
                }
            event?.let { mutableEvents.emit(it) }
        }

        /**
         * Parse an inbound `<jingle>` IQ to a [CallSignalEvent]. The IQ XML
         * comes straight from the provider as a string, so we do a minimal
         * parse for the action + sid attributes and then feed the full XML
         * into the bridge if we need the SDP (session-accept only).
         */
        private fun mapJingleSignal(
            fromJid: String,
            jingleXml: String,
        ): CallSignalEvent? {
            val action = extractAttr(jingleXml, "action") ?: return null
            val sid = extractAttr(jingleXml, "sid") ?: return null
            return when (action) {
                "session-accept" -> mapSessionAccept(fromJid, sid, jingleXml)
                "transport-info" -> mapTransportInfo(sid, jingleXml)
                "session-terminate" -> CallSignalEvent.RemoteTerminate(sid = sid, reason = extractReasonCondition(jingleXml))
                else -> null
            }
        }

        private fun mapSessionAccept(
            fromJid: String,
            sid: String,
            jingleXml: String,
        ): CallSignalEvent =
            CallSignalEvent.RemoteAnswer(
                sid = sid,
                fromJid = fromJid,
                answer =
                    SessionDescription(
                        SessionDescription.Type.ANSWER,
                        sdpFromJingle(jingleXml),
                    ),
            )

        private fun mapTransportInfo(
            sid: String,
            jingleXml: String,
        ): CallSignalEvent? {
            // Minimal parse: find the <candidate/> element and its <content name=...>
            // wrapper. We rely on the server sending one candidate per IQ,
            // which matches the web client's behavior.
            val contentName = extractContentName(jingleXml) ?: "0"
            val candidateLine = extractCandidateLine(jingleXml) ?: return null
            return CallSignalEvent.RemoteCandidate(
                sid = sid,
                candidate = IceCandidate(contentName, 0, candidateLine),
            )
        }

        private fun sdpFromJingle(jingleXml: String): String {
            // Use a simple sub-parser: we only need the content elements and
            // the BUNDLE group. For that we turn the XML back into
            // JingleElements via a minimal tokenizer.
            val jingleElement = parseJingleXmlToElement(jingleXml)
            return social.waddle.android.call.jingle
                .extractSdpOfferFromJingle(jingleElement)
        }

        private fun extractAttr(
            xml: String,
            name: String,
        ): String? {
            val marker = "$name='"
            val start = xml.indexOf(marker).takeIf { it >= 0 } ?: return null
            val end = xml.indexOf('\'', start + marker.length).takeIf { it > 0 } ?: return null
            return xml.substring(start + marker.length, end)
        }

        private fun extractContentName(xml: String): String? {
            val idx = xml.indexOf("<content")
            if (idx < 0) return null
            val closing = xml.indexOf('>', idx)
            if (closing < 0) return null
            return extractAttr(xml.substring(idx, closing + 1), "name")
        }

        private fun extractCandidateLine(xml: String): String? {
            val idx = xml.indexOf("<candidate")
            if (idx < 0) return null
            val closing = xml.indexOf("/>", idx).takeIf { it > 0 } ?: xml.indexOf('>', idx)
            if (closing < 0) return null
            val slice = xml.substring(idx, closing + 2)
            val foundation = extractAttr(slice, "foundation") ?: return null
            val component = extractAttr(slice, "component") ?: return null
            val protocol = extractAttr(slice, "protocol") ?: return null
            val priority = extractAttr(slice, "priority") ?: return null
            val ip = extractAttr(slice, "ip") ?: return null
            val port = extractAttr(slice, "port") ?: return null
            val type = extractAttr(slice, "type") ?: return null
            val generation = extractAttr(slice, "generation")
            return buildString {
                append("candidate:")
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
                    .append(type)
                generation?.let { append(" generation ").append(it) }
            }
        }

        private fun extractReasonCondition(xml: String): String? {
            // <reason><decline/></reason> → "decline".
            val reasonStart = xml.indexOf("<reason")
            if (reasonStart < 0) return null
            val reasonEnd = xml.indexOf("</reason", reasonStart)
            if (reasonEnd < 0) return null
            val slice = xml.substring(reasonStart, reasonEnd)
            // First child element inside reason.
            var i = slice.indexOf('>', 7) + 1
            while (i < slice.length && slice[i].isWhitespace()) i++
            if (i >= slice.length || slice[i] != '<') return null
            val nameStart = i + 1
            var nameEnd = nameStart
            while (
                nameEnd < slice.length &&
                (slice[nameEnd].isLetterOrDigit() || slice[nameEnd] == '-')
            ) {
                nameEnd++
            }
            return slice.substring(nameStart, nameEnd).takeIf { it.isNotEmpty() }
        }

        private fun parseJingleXmlToElement(xml: String): JingleElement {
            // Minimal, allocation-cheap parse that uses Java's SAX parser to
            // build a [JingleElement] tree. This is only used on the receive
            // path where we need to feed the bridge — the hot send path
            // builds JingleElements directly.
            val factory =
                javax.xml.parsers.SAXParserFactory.newInstance().apply {
                    isNamespaceAware = true
                }
            val parser = factory.newSAXParser()
            val handler = JingleSaxHandler()
            parser.parse(
                org.xml.sax.InputSource(java.io.StringReader(xml)),
                handler,
            )
            return handler.root ?: error("Failed to parse <jingle> payload.")
        }

        private fun social.waddle.android.xmpp.XmppConnectionState.bareJid(): String? =
            (this as? social.waddle.android.xmpp.XmppConnectionState.Connected)?.jid

        private companion object {
            const val EVENT_BUFFER = 64
        }
    }

/**
 * SAX handler that materializes a [JingleElement] tree from a XEP-0166 IQ
 * payload. Lives at file scope so it can stay simple — Android's default
 * SAX parser is enough here, we don't need Smack's stanza parser machinery.
 */
private class JingleSaxHandler : org.xml.sax.helpers.DefaultHandler() {
    var root: JingleElement? = null
    private val stack: ArrayDeque<JingleElement> = ArrayDeque()

    override fun startElement(
        uri: String?,
        localName: String?,
        qName: String?,
        attributes: org.xml.sax.Attributes?,
    ) {
        val name = (localName?.takeIf { it.isNotEmpty() } ?: qName) ?: return
        val ns = uri ?: ""
        val element = JingleElement(name = name, namespace = ns)
        attributes?.let { attrs -> copyAttributes(attrs, element) }
        if (stack.isEmpty()) root = element else stack.last().children.add(element)
        stack.addLast(element)
    }

    override fun endElement(
        uri: String?,
        localName: String?,
        qName: String?,
    ) {
        stack.removeLast()
    }

    override fun characters(
        ch: CharArray?,
        start: Int,
        length: Int,
    ) {
        if (ch == null || length == 0 || stack.isEmpty()) return
        val text = String(ch, start, length).trim()
        if (text.isEmpty()) return
        val current = stack.last()
        current.text = (current.text + text).trim()
    }

    private fun copyAttributes(
        attrs: org.xml.sax.Attributes,
        into: JingleElement,
    ) {
        var i = 0
        val count = attrs.length
        while (i < count) {
            val qn = attrs.getQName(i)
            if (qn != null && qn != "xmlns" && !qn.startsWith("xmlns:")) {
                into.attributes[qn] = attrs.getValue(i)
            }
            i += 1
        }
    }
}
