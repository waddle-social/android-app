package social.waddle.android.call.jingle

/**
 * XEP-0166: Jingle (core).
 *
 * Port of `server/crates/waddle-xmpp/src/xep/xep0166.rs`.
 */
object Xep0166 {
    const val NS: String = "urn:xmpp:jingle:1"
}

enum class JingleAction(
    val attr: String,
) {
    SESSION_INITIATE("session-initiate"),
    SESSION_ACCEPT("session-accept"),
    SESSION_TERMINATE("session-terminate"),
    SESSION_INFO("session-info"),
    TRANSPORT_INFO("transport-info"),
    CONTENT_ADD("content-add"),
    CONTENT_REMOVE("content-remove"),
    ;

    companion object {
        fun fromAttr(value: String): JingleAction? = entries.firstOrNull { it.attr == value }
    }
}

enum class ContentCreator(
    val attr: String,
) {
    INITIATOR("initiator"),
    RESPONDER("responder"),
    ;

    companion object {
        fun fromAttr(value: String): ContentCreator? = entries.firstOrNull { it.attr == value }
    }
}

enum class Senders(
    val attr: String,
) {
    BOTH("both"),
    INITIATOR("initiator"),
    RESPONDER("responder"),
    NONE("none"),
    ;

    companion object {
        fun fromAttr(value: String): Senders? = entries.firstOrNull { it.attr == value }
    }
}

sealed class ReasonCondition(
    val elementName: String,
) {
    data object Success : ReasonCondition("success")

    data object Decline : ReasonCondition("decline")

    data object Busy : ReasonCondition("busy")

    data object Cancel : ReasonCondition("cancel")

    data object ConnectivityError : ReasonCondition("connectivity-error")

    data object FailedApplication : ReasonCondition("failed-application")

    data object FailedTransport : ReasonCondition("failed-transport")

    data object GeneralError : ReasonCondition("general-error")

    data object MediaError : ReasonCondition("media-error")

    data object SecurityError : ReasonCondition("security-error")

    data object Timeout : ReasonCondition("timeout")

    data object UnsupportedApplications : ReasonCondition("unsupported-applications")

    data object UnsupportedTransports : ReasonCondition("unsupported-transports")

    data class AlternativeSession(
        val sid: String,
    ) : ReasonCondition("alternative-session")

    data class Other(
        val rawName: String,
    ) : ReasonCondition(rawName)

    companion object {
        private val STATIC: Map<String, ReasonCondition> =
            listOf(
                Success,
                Decline,
                Busy,
                Cancel,
                ConnectivityError,
                FailedApplication,
                FailedTransport,
                GeneralError,
                MediaError,
                SecurityError,
                Timeout,
                UnsupportedApplications,
                UnsupportedTransports,
            ).associateBy { it.elementName }

        fun fromElement(elem: JingleElement): ReasonCondition {
            STATIC[elem.name]?.let { return it }
            if (elem.name == "alternative-session") return AlternativeSession(elem.text.trim())
            return Other(elem.name)
        }
    }

    fun toElement(): JingleElement {
        val builder = JingleElement.builder(elementName, Xep0166.NS)
        if (this is AlternativeSession) builder.text(sid)
        return builder.build()
    }
}

data class JingleReason(
    val condition: ReasonCondition,
    val text: String? = null,
)

data class JingleContent(
    val creator: ContentCreator,
    val name: String,
    var senders: Senders? = null,
    var description: JingleElement? = null,
    var transport: JingleElement? = null,
)

data class Jingle(
    val action: JingleAction,
    val sid: String,
    var initiator: String? = null,
    var responder: String? = null,
    val contents: MutableList<JingleContent> = mutableListOf(),
    var reason: JingleReason? = null,
)

fun JingleElement.isJingle(): Boolean = isTag("jingle", Xep0166.NS)

fun parseJingleContentElement(elem: JingleElement): JingleContent? {
    if (!elem.isTag("content", Xep0166.NS)) return null
    val creator = elem.attr("creator")?.let(ContentCreator::fromAttr) ?: return null
    val name = elem.attr("name")?.takeIf { it.isNotEmpty() } ?: return null
    val senders = elem.attr("senders")?.let(Senders::fromAttr)

    val description = elem.children.firstOrNull { it.name == "description" }
    val transport = elem.children.firstOrNull { it.name == "transport" }

    return JingleContent(
        creator = creator,
        name = name,
        senders = senders,
        description = description,
        transport = transport,
    )
}

fun buildJingleContentElement(content: JingleContent): JingleElement {
    val builder =
        JingleElement
            .builder("content", Xep0166.NS)
            .attr("creator", content.creator.attr)
            .attr("name", content.name)
            .attrIfNotNull("senders", content.senders?.attr)
    content.description?.let(builder::child)
    content.transport?.let(builder::child)
    return builder.build()
}

fun parseJingleReasonElement(elem: JingleElement): JingleReason? {
    if (!elem.isTag("reason", Xep0166.NS)) return null
    val conditionElem = elem.children.firstOrNull { it.namespace == Xep0166.NS && it.name != "text" } ?: return null
    val condition = ReasonCondition.fromElement(conditionElem)
    val text = elem.firstChild("text", Xep0166.NS)?.text?.takeIf { it.isNotEmpty() }
    return JingleReason(condition = condition, text = text)
}

fun buildJingleReasonElement(reason: JingleReason): JingleElement {
    val builder = JingleElement.builder("reason", Xep0166.NS).child(reason.condition.toElement())
    reason.text?.let {
        builder.child(JingleElement.builder("text", Xep0166.NS).text(it).build())
    }
    return builder.build()
}

fun parseJingleElement(elem: JingleElement): Jingle? {
    if (!elem.isJingle()) return null
    val action = elem.attr("action")?.let(JingleAction::fromAttr) ?: return null
    val sid = elem.attr("sid")?.takeIf { it.isNotEmpty() } ?: return null
    val initiator = elem.attr("initiator")?.takeIf { it.isNotEmpty() }
    val responder = elem.attr("responder")?.takeIf { it.isNotEmpty() }

    val jingle = Jingle(action = action, sid = sid, initiator = initiator, responder = responder)
    elem.childrenOfTag("content", Xep0166.NS).forEach { child ->
        parseJingleContentElement(child)?.let(jingle.contents::add)
    }
    elem.firstChild("reason", Xep0166.NS)?.let { reasonElem ->
        parseJingleReasonElement(reasonElem)?.let { jingle.reason = it }
    }
    return jingle
}

fun buildJingleElement(jingle: Jingle): JingleElement {
    val builder =
        JingleElement
            .builder("jingle", Xep0166.NS)
            .attr("action", jingle.action.attr)
            .attr("sid", jingle.sid)
            .attrIfNotNull("initiator", jingle.initiator)
            .attrIfNotNull("responder", jingle.responder)
    for (content in jingle.contents) builder.child(buildJingleContentElement(content))
    jingle.reason?.let { builder.child(buildJingleReasonElement(it)) }
    return builder.build()
}
