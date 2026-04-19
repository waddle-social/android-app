package social.waddle.android.xmpp

import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smackx.delay.packet.DelayInformation
import org.jivesoftware.smackx.mam.element.MamElements
import org.jivesoftware.smackx.sid.element.StanzaIdElement
import java.time.Instant

internal class XmppMessageMapper(
    private val accountUserId: String?,
    private val accountUsername: String?,
    private val mucNickname: String?,
) {
    fun liveRoomMessage(
        message: Message,
        roomJid: String,
    ): XmppHistoryMessage? {
        if (MamElements.MamResultExtension.from(message) != null) {
            return null
        }
        // Prefer a XEP-0203 <delay> stamp when present — that's the real server
        // receive time, not "now". This is how we tell re-delivered backlog
        // (stream resumption / MUC rejoin history) apart from live messages.
        val createdAt = message.effectiveCreatedAt()
        val senderName = message.historySenderName()
        return roomMessageFrom(
            message = message,
            roomJid = roomJid,
            createdAt = createdAt,
            serverId = roomStableMessageId(message, roomJid),
            originStanzaId = message.effectiveOriginId(),
            senderName = senderName,
        )
    }

    /**
     * The sender-chosen origin id. Prefer XEP-0359 `<origin-id xmlns="urn:xmpp:sid:0"/>`
     * and fall back to the `<message id="...">` attribute when the extension is
     * absent. This is useful for pending-row reconciliation and direct-message
     * references, but it is sender-controlled and must not be treated like a
     * trusted server-assigned stanza-id.
     */
    private fun Message.effectiveOriginId(): String? {
        val explicit =
            (getExtensionElement("origin-id", ORIGIN_ID_NAMESPACE) as? StandardExtensionElement)
                ?.getAttributeValue("id")
                ?.takeIf { it.isNotBlank() }
        return explicit ?: stanzaId.nonBlank()
    }

    /**
     * Returns the message's server-side stamp if it carries a XEP-0203 `<delay>`,
     * otherwise the current instant. Used so re-delivered messages on reconnect
     * carry a real timestamp rather than masquerading as fresh arrivals.
     */
    private fun Message.effectiveCreatedAt(): String =
        DelayInformation
            .from(this)
            ?.stamp
            ?.toInstant()
            ?.toString()
            ?: Instant.now().toString()

    fun roomMessageFromMam(
        result: MamElements.MamResultExtension,
        roomJid: String,
    ): XmppHistoryMessage? {
        val forwarded = result.forwarded ?: return null
        val message = forwarded.forwardedStanza ?: return null
        val createdAt =
            forwarded.delayInformation
                ?.stamp
                ?.toInstant()
                ?.toString()
                ?: Instant.now().toString()
        return roomMessageFrom(
            message = message,
            roomJid = roomJid,
            createdAt = createdAt,
            serverId = roomStableMessageId(message, roomJid),
            originStanzaId = message.effectiveOriginId(),
            senderName = message.historySenderName(),
        )
    }

    fun liveDirectMessage(
        message: Message,
        ownBareJid: String,
    ): XmppDirectMessage? {
        if (MamElements.MamResultExtension.from(message) != null) {
            return null
        }
        val fromBare = message.from?.toString()?.bareJid() ?: return null
        val toBare = message.to?.toString()?.bareJid()
        val peerJid =
            when (fromBare) {
                ownBareJid -> toBare ?: return null
                else -> fromBare
            }
        return directMessageFrom(
            message = message,
            ownBareJid = ownBareJid,
            peerJid = peerJid,
            createdAt = message.effectiveCreatedAt(),
            serverId = directStableMessageId(message, trustedDirectStanzaIdAssigners(message, ownBareJid, peerJid)),
            originStanzaId = message.effectiveOriginId(),
        )
    }

    fun directMessageFromMam(
        result: MamElements.MamResultExtension,
        ownBareJid: String,
        peerJid: String,
    ): XmppDirectMessage? {
        val forwarded = result.forwarded ?: return null
        val message = forwarded.forwardedStanza ?: return null
        val createdAt =
            forwarded.delayInformation
                ?.stamp
                ?.toInstant()
                ?.toString()
                ?: Instant.now().toString()
        return directMessageFrom(
            message = message,
            ownBareJid = ownBareJid,
            peerJid = peerJid,
            createdAt = createdAt,
            serverId = directStableMessageId(message, trustedDirectStanzaIdAssigners(message, ownBareJid, peerJid)),
            originStanzaId = message.effectiveOriginId(),
        )
    }

    /**
     * Map a MAM result from the user's own archive (no peer filter) into an
     * [XmppDirectMessage]. The peer JID is derived from the message's from/to
     * so callers can bucket messages into per-peer conversations.
     */
    fun directMessageFromOwnArchive(
        result: MamElements.MamResultExtension,
        ownBareJid: String,
    ): XmppDirectMessage? {
        val forwarded = result.forwarded ?: return null
        val message = forwarded.forwardedStanza ?: return null
        val fromBare = message.from?.toString()?.bareJid() ?: return null
        val toBare = message.to?.toString()?.bareJid() ?: return null
        val peerJid =
            when (ownBareJid) {
                fromBare -> toBare
                toBare -> fromBare
                else -> return null
            }
        val createdAt =
            forwarded.delayInformation
                ?.stamp
                ?.toInstant()
                ?.toString()
                ?: Instant.now().toString()
        return directMessageFrom(
            message = message,
            ownBareJid = ownBareJid,
            peerJid = peerJid,
            createdAt = createdAt,
            serverId = directStableMessageId(message, trustedDirectStanzaIdAssigners(message, ownBareJid, peerJid)),
            originStanzaId = message.effectiveOriginId(),
        )
    }

    private fun roomMessageFrom(
        message: Message,
        roomJid: String,
        createdAt: String,
        serverId: String?,
        originStanzaId: String?,
        senderName: String?,
    ): XmppHistoryMessage? {
        roomUpdateMessage(message, roomJid, createdAt, serverId, originStanzaId, senderName)?.let { return it }

        val sharedFile = message.sharedFile()
        val messageId = serverId ?: originStanzaId ?: fallbackMessageId(roomJid, createdAt, "message")
        val callInvite = message.callInvite(messageId)
        val body =
            message.body
                ?.takeIf { it.isNotBlank() }
                ?.stripFallbacks(message, REPLY_NAMESPACE, FILE_SHARING_NAMESPACE)
                ?.takeIf { it.isNotBlank() }
                ?: sharedFile?.description?.takeIf { it.isNotBlank() }
                ?: sharedFile?.name?.takeIf { it.isNotBlank() }
                ?: sharedFile?.url?.takeIf { it.isNotBlank() }
                ?: callInvite?.meetingDescription?.takeIf { it.isNotBlank() }
                ?: callInvite?.externalUri?.takeIf { it.isNotBlank() }
                ?: return null
        return XmppHistoryMessage(
            id = serverId ?: originStanzaId ?: fallbackMessageId(roomJid, createdAt, body),
            serverId = serverId,
            originStanzaId = originStanzaId,
            roomJid = roomJid,
            senderId = senderIdFor(message, senderName),
            senderName = displayNameFor(senderName),
            body = body,
            createdAt = createdAt,
            replyToMessageId = message.replyTargetId(),
            mentions = message.mentionUris(),
            broadcastMention = message.broadcastMention(),
            sharedFile = sharedFile,
            isSticker = message.isSticker(),
            callInvite = callInvite,
            replacesId = message.correctionTargetId(),
            hats = message.hats(),
            threadId = message.threadId(),
            parentThreadId = null,
            forumTopicTitle = message.forumTopicTitle(),
            forumReplyThreadId = message.forumReplyThreadId(),
            markupRanges = message.markupRanges(),
        )
    }

    private fun roomUpdateMessage(
        message: Message,
        roomJid: String,
        createdAt: String,
        serverId: String?,
        originStanzaId: String?,
        senderName: String?,
    ): XmppHistoryMessage? {
        val base =
            RoomMessageBase(
                idPrefix = roomJid,
                createdAt = createdAt,
                serverId = serverId,
                originStanzaId = originStanzaId,
                roomJid = roomJid,
                senderId = senderIdFor(message, senderName),
                senderName = displayNameFor(senderName),
            )
        message.displayedTargetId()?.let { return base.displayed(it) }
        message.chatState()?.let { return base.chatState(it) }
        message.reactionUpdate()?.let { return base.reaction(it) }
        message.retractionTargetId()?.let { return base.retraction(it) }
        return null
    }

    private fun directMessageFrom(
        message: Message,
        ownBareJid: String,
        peerJid: String,
        createdAt: String,
        serverId: String?,
        originStanzaId: String?,
    ): XmppDirectMessage? {
        val fromBare = message.from?.toString()?.bareJid() ?: return null
        directUpdateMessage(message, peerJid, fromBare, ownBareJid, createdAt, serverId, originStanzaId)?.let { return it }

        val sharedFile = message.sharedFile()
        val messageId = serverId ?: originStanzaId ?: fallbackMessageId(peerJid, createdAt, "dm-message")
        val callInvite = message.callInvite(messageId)
        val body =
            message.body
                ?.takeIf { it.isNotBlank() }
                ?.stripFallbacks(message, REPLY_NAMESPACE, FILE_SHARING_NAMESPACE)
                ?.takeIf { it.isNotBlank() }
                ?: sharedFile?.description?.takeIf { it.isNotBlank() }
                ?: sharedFile?.name?.takeIf { it.isNotBlank() }
                ?: sharedFile?.url?.takeIf { it.isNotBlank() }
                ?: callInvite?.meetingDescription?.takeIf { it.isNotBlank() }
                ?: callInvite?.externalUri?.takeIf { it.isNotBlank() }
                ?: return null
        return XmppDirectMessage(
            id = serverId ?: originStanzaId ?: fallbackMessageId(peerJid, createdAt, body),
            serverId = serverId,
            originStanzaId = originStanzaId,
            peerJid = peerJid,
            fromJid = fromBare,
            senderName = directSenderName(fromBare, ownBareJid, peerJid),
            body = body,
            createdAt = createdAt,
            replyToMessageId = message.replyTargetId(),
            mentions = message.mentionUris(),
            broadcastMention = message.broadcastMention(),
            sharedFile = sharedFile,
            isSticker = message.isSticker(),
            callInvite = callInvite,
            replacesId = message.correctionTargetId(),
            hats = message.hats(),
            threadId = message.threadId(),
            parentThreadId = null,
            markupRanges = message.markupRanges(),
        )
    }

    private fun directUpdateMessage(
        message: Message,
        peerJid: String,
        fromBare: String,
        ownBareJid: String,
        createdAt: String,
        serverId: String?,
        originStanzaId: String?,
    ): XmppDirectMessage? {
        val base =
            DirectMessageBase(
                idPrefix = peerJid,
                createdAt = createdAt,
                serverId = serverId,
                originStanzaId = originStanzaId,
                peerJid = peerJid,
                fromJid = fromBare,
                senderName = directSenderName(fromBare, ownBareJid, peerJid),
            )
        message.displayedTargetId()?.let { return base.displayed(it) }
        message.chatState()?.let { return base.chatState(it) }
        message.reactionUpdate()?.let { return base.reaction(it) }
        message.retractionTargetId()?.let { return base.retraction(it) }
        return null
    }

    private inner class RoomMessageBase(
        private val idPrefix: String,
        private val createdAt: String,
        private val serverId: String?,
        private val originStanzaId: String?,
        private val roomJid: String,
        private val senderId: String?,
        private val senderName: String?,
    ) {
        fun displayed(displayedId: String): XmppHistoryMessage = message(displayedId = displayedId, idFallback = "displayed:$displayedId")

        fun chatState(chatState: ChatState): XmppHistoryMessage = message(chatState = chatState, idFallback = "chat-state:$chatState")

        fun reaction(reaction: ReactionUpdate): XmppHistoryMessage =
            message(
                reactionTargetId = reaction.targetId,
                reactionEmojis = reaction.emojis,
                idFallback = "reaction:${reaction.targetId}",
            )

        fun retraction(retractsId: String): XmppHistoryMessage = message(retractsId = retractsId, idFallback = "retract:$retractsId")

        private fun message(
            idFallback: String,
            displayedId: String? = null,
            chatState: ChatState? = null,
            reactionTargetId: String? = null,
            reactionEmojis: List<String> = emptyList(),
            retractsId: String? = null,
        ): XmppHistoryMessage =
            XmppHistoryMessage(
                id = serverId ?: fallbackMessageId(idPrefix, createdAt, idFallback),
                serverId = serverId,
                originStanzaId = originStanzaId,
                roomJid = roomJid,
                senderId = senderId,
                senderName = senderName,
                body = "",
                createdAt = createdAt,
                displayedId = displayedId,
                chatState = chatState,
                reactionTargetId = reactionTargetId,
                reactionEmojis = reactionEmojis,
                retractsId = retractsId,
            )
    }

    private inner class DirectMessageBase(
        private val idPrefix: String,
        private val createdAt: String,
        private val serverId: String?,
        private val originStanzaId: String?,
        private val peerJid: String,
        private val fromJid: String,
        private val senderName: String,
    ) {
        fun displayed(displayedId: String): XmppDirectMessage = message(displayedId = displayedId, idFallback = "dm-displayed:$displayedId")

        fun chatState(chatState: ChatState): XmppDirectMessage = message(chatState = chatState, idFallback = "dm-chat-state:$chatState")

        fun reaction(reaction: ReactionUpdate): XmppDirectMessage =
            message(
                reactionTargetId = reaction.targetId,
                reactionEmojis = reaction.emojis,
                idFallback = "dm-reaction:${reaction.targetId}",
            )

        fun retraction(retractsId: String): XmppDirectMessage = message(retractsId = retractsId, idFallback = "dm-retract:$retractsId")

        private fun message(
            idFallback: String,
            displayedId: String? = null,
            chatState: ChatState? = null,
            reactionTargetId: String? = null,
            reactionEmojis: List<String> = emptyList(),
            retractsId: String? = null,
        ): XmppDirectMessage =
            XmppDirectMessage(
                id = serverId ?: fallbackMessageId(idPrefix, createdAt, idFallback),
                serverId = serverId,
                originStanzaId = originStanzaId,
                peerJid = peerJid,
                fromJid = fromJid,
                senderName = senderName,
                body = "",
                createdAt = createdAt,
                displayedId = displayedId,
                chatState = chatState,
                reactionTargetId = reactionTargetId,
                reactionEmojis = reactionEmojis,
                retractsId = retractsId,
            )
    }

    private data class ReactionUpdate(
        val targetId: String,
        val emojis: List<String>,
    )

    private fun Message.reactionUpdate(): ReactionUpdate? {
        val element = standardExtension("reactions", REACTIONS_NAMESPACE) ?: return null
        val targetId = element.getAttributeValue("id")?.takeIf { it.isNotBlank() } ?: return null
        val emojis =
            element
                .getElements("reaction")
                .mapNotNull { it.text?.takeIf(String::isNotBlank) }
                .distinct()
        return ReactionUpdate(targetId = targetId, emojis = emojis)
    }

    private fun Message.retractionTargetId(): String? =
        standardExtension("retract", RETRACTION_NAMESPACE)
            ?.getAttributeValue("id")
            ?.takeIf { it.isNotBlank() }
            ?: standardExtension("apply-to", FASTEN_NAMESPACE)
                ?.takeIf {
                    it.getFirstElement("retract", RETRACTION_NAMESPACE) != null ||
                        it.getFirstElement("moderated", MESSAGE_MODERATE_NAMESPACE) != null
                }?.getAttributeValue("id")
                ?.takeIf { it.isNotBlank() }

    private fun Message.correctionTargetId(): String? =
        standardExtension("replace", CORRECTION_NAMESPACE)
            ?.getAttributeValue("id")
            ?.takeIf { it.isNotBlank() }

    private fun Message.displayedTargetId(): String? =
        standardExtension("displayed", CHAT_MARKERS_NAMESPACE)
            ?.getAttributeValue("id")
            ?.takeIf { it.isNotBlank() }

    private fun Message.replyTargetId(): String? =
        standardExtension("reply", REPLY_NAMESPACE)
            ?.getAttributeValue("id")
            ?.takeIf { it.isNotBlank() }

    private fun Message.chatState(): ChatState? =
        CHAT_STATE_ELEMENTS.firstNotNullOfOrNull { state ->
            getExtensionElement(state.element, CHAT_STATES_NAMESPACE)?.let { state.state }
        }

    private fun Message.mentionUris(): List<String> =
        getExtensions("reference", REFERENCE_NAMESPACE)
            .mapNotNull { it as? StandardExtensionElement }
            .filter { it.getAttributeValue("type") == "mention" }
            .mapNotNull { reference ->
                reference
                    .getAttributeValue("uri")
                    ?.removePrefix("xmpp:")
                    ?.takeIf { it.isNotBlank() }
            }

    private fun Message.broadcastMention(): String? {
        val mentions = standardExtension("mentions", EXPLICIT_MENTIONS_NAMESPACE) ?: return null
        return mentions
            .getElements("mention")
            .firstNotNullOfOrNull { mention ->
                mention.getAttributeValue("type")?.takeIf { it == "everyone" || it == "here" }
            }
    }

    private fun Message.sharedFile(): XmppSharedFile? {
        val sharing = standardExtension("file-sharing", FILE_SHARING_NAMESPACE) ?: return null
        val file = sharing.getFirstElement("file", FILE_METADATA_NAMESPACE)
        val sources = sharing.getFirstElement("sources", FILE_SHARING_NAMESPACE)
        val url =
            sources
                ?.getFirstElement("url-data", URL_DATA_NAMESPACE)
                ?.getAttributeValue("target")
                ?.takeIf { it.isNotBlank() }
                ?: return null
        return XmppSharedFile(
            url = url,
            disposition = sharing.getAttributeValue("disposition") ?: "inline",
            name = file?.childText("name", FILE_METADATA_NAMESPACE),
            mediaType = file?.childText("media-type", FILE_METADATA_NAMESPACE),
            size = file?.childText("size", FILE_METADATA_NAMESPACE)?.toLongOrNull(),
            description = file?.childText("desc", FILE_METADATA_NAMESPACE),
        )
    }

    private fun Message.isSticker(): Boolean = getExtensionElement("sticker", STICKERS_NAMESPACE) != null

    /**
     * XEP-0201 `<thread>body</thread>` — Smack parses the `<thread>` element
     * into `Message.getThread()` directly, not as a StandardExtensionElement,
     * so we go through the built-in property.
     */
    private fun Message.threadId(): String? = thread?.takeIf { it.isNotBlank() }

    /** XEP-0508 `<thread-create xmlns='urn:xmpp:forums:0' title='…'/>`. */
    private fun Message.forumTopicTitle(): String? =
        (getExtensionElement("thread-create", FORUMS_NAMESPACE) as? StandardExtensionElement)
            ?.getAttributeValue("title")
            ?.takeIf { it.isNotBlank() }

    /** XEP-0508 `<thread-reply xmlns='urn:xmpp:forums:0' thread-id='…'/>`. */
    private fun Message.forumReplyThreadId(): String? =
        (getExtensionElement("thread-reply", FORUMS_NAMESPACE) as? StandardExtensionElement)
            ?.getAttributeValue("thread-id")
            ?.takeIf { it.isNotBlank() }

    /**
     * XEP-0394 Message Markup: parses `<markup xmlns='urn:xmpp:markup:0'>` with
     * child elements like `<span start='0' end='5'><bold/></span>` or
     * semantic tags (`<code>`, `<bquote>`, `<link url='…'>`).
     */
    private fun Message.markupRanges(): List<XmppMarkupRange> {
        val messageBody = body ?: return emptyList()
        val markup = (getExtensionElement("markup", MARKUP_NAMESPACE) as? StandardExtensionElement) ?: return emptyList()
        return markup.elements.mapNotNull { child -> parseMarkupChild(child, messageBody) }
    }

    private fun parseMarkupChild(
        child: StandardExtensionElement,
        messageBody: String,
    ): XmppMarkupRange? {
        val start = child.getAttributeValue("start")?.toIntOrNull() ?: return null
        val end = child.getAttributeValue("end")?.toIntOrNull() ?: return null
        if (end <= start) return null
        val style = markupStyleFor(child) ?: return null
        val range = messageBody.xmppCodePointRangeToUtf16(start, end) ?: return null
        return XmppMarkupRange(start = range.first, end = range.second, style = style)
    }

    private fun markupStyleFor(child: StandardExtensionElement): XmppMarkupStyle? {
        tagToStyle(child.elementName)?.let { return it }
        if (child.elementName == "span") {
            val inner = child.elements.firstOrNull() ?: return null
            return tagToStyle(inner.elementName)
        }
        return null
    }

    private fun tagToStyle(tag: String): XmppMarkupStyle? =
        when (tag) {
            "strong" -> XmppMarkupStyle.BOLD
            "bold" -> XmppMarkupStyle.BOLD
            "emphasis" -> XmppMarkupStyle.ITALIC
            "italic" -> XmppMarkupStyle.ITALIC
            "deleted" -> XmppMarkupStyle.STRIKE
            "strike" -> XmppMarkupStyle.STRIKE
            "code" -> XmppMarkupStyle.CODE
            "bcode" -> XmppMarkupStyle.CODE
            "bquote" -> XmppMarkupStyle.BLOCKQUOTE
            "link" -> XmppMarkupStyle.LINK
            else -> null
        }

    /**
     * Parse XEP-0317 hats. Accepts both wire shapes:
     *   `<hats xmlns='urn:xmpp:hats:0'><hat uri='…' title='…'/></hats>` (standard)
     *   `<hat xmlns='urn:xmpp:hats:0' uri='…' title='…'/>` (chat-frontend shorthand)
     */
    private fun Message.hats(): List<WaddleHat> {
        val wrapped = standardExtension("hats", HATS_NAMESPACE)
        if (wrapped != null) {
            return wrapped.getElements("hat").mapNotNull(::parseHat)
        }
        return getExtensions("hat", HATS_NAMESPACE)
            .mapNotNull { it as? StandardExtensionElement }
            .mapNotNull(::parseHat)
    }

    private fun parseHat(element: StandardExtensionElement): WaddleHat? {
        val uri = element.getAttributeValue("uri")?.takeIf { it.isNotBlank() } ?: return null
        val title = element.getAttributeValue("title")?.takeIf { it.isNotBlank() } ?: uri.substringAfterLast(':')
        return WaddleHat(uri = uri, title = title)
    }

    /**
     * XEP-0428 Fallback Indication: when the message carries a
     * `<fallback for="urn:xmpp:reply:0">` element with a `<body start end/>`
     * range, that range of the body is quoted-preamble for legacy clients.
     * We're reply-aware (we render a `<reply/>` chip), so we strip that
     * range — per the XEP — and display only the new reply text.
     */
    private fun String.stripFallbacks(
        message: Message,
        vararg namespaces: String,
    ): String {
        val ranges =
            namespaces
                .flatMap { namespace -> message.fallbackBodyRanges(namespace, this) }
                .distinct()
                .sortedByDescending { it.first }
        if (ranges.isEmpty()) return this
        return ranges
            .fold(this) { current, range ->
                current.removeRange(range.first, range.second.coerceAtMost(current.length))
            }.trim()
    }

    private fun Message.fallbackBodyRanges(
        namespace: String,
        messageBody: String,
    ): List<Pair<Int, Int>> =
        getExtensions("fallback", FALLBACK_NAMESPACE)
            .mapNotNull { it as? StandardExtensionElement }
            .filter { it.getAttributeValue("for") == namespace }
            .flatMap { fallback ->
                val bodyRanges = fallback.getElements("body")
                if (bodyRanges.isEmpty()) {
                    listOf(0 to messageBody.length)
                } else {
                    bodyRanges.mapNotNull { bodyRange ->
                        val bodyCodePointLength = messageBody.codePointCount(0, messageBody.length)
                        val start = bodyRange.getAttributeValue("start")?.toIntOrNull()?.coerceIn(0, bodyCodePointLength) ?: 0
                        val end = bodyRange.getAttributeValue("end")?.toIntOrNull()?.coerceIn(0, bodyCodePointLength) ?: bodyCodePointLength
                        messageBody.xmppCodePointRangeToUtf16(start, end)
                    }
                }
            }

    private fun Message.callInvite(fallbackId: String): XmppCallInvite? {
        val invite = standardExtension("invite", CALL_INVITES_NAMESPACE) ?: return null
        val meeting = standardExtension("meeting", ONLINE_MEETINGS_NAMESPACE)
        val mujiElement = invite.getFirstElement("muji", CALL_INVITES_NAMESPACE)
        val jingle = invite.getFirstElement("jingle", CALL_INVITES_NAMESPACE)
        val external = invite.getFirstElement("external", CALL_INVITES_NAMESPACE)
        return XmppCallInvite(
            inviteId = invite.getAttributeValue("id")?.takeIf { it.isNotBlank() } ?: fallbackId,
            muji = mujiElement != null && mujiElement.text != "false",
            jingleSid = jingle?.getAttributeValue("sid")?.takeIf { it.isNotBlank() },
            jingleJid = jingle?.getAttributeValue("jid")?.takeIf { it.isNotBlank() },
            externalUri =
                external?.getAttributeValue("uri")?.takeIf { it.isNotBlank() }
                    ?: meeting?.getAttributeValue("url")?.takeIf { it.isNotBlank() },
            meetingDescription = meeting?.getAttributeValue("desc")?.takeIf { it.isNotBlank() },
        )
    }

    private fun StandardExtensionElement.childText(
        element: String,
        namespace: String,
    ): String? = getFirstElement(element, namespace)?.text?.takeIf { it.isNotBlank() }

    private fun Message.standardExtension(
        element: String,
        namespace: String,
    ): StandardExtensionElement? = getExtensionElement(element, namespace) as? StandardExtensionElement

    private fun roomStableMessageId(
        message: Message,
        roomJid: String,
    ): String? =
        message
            .getExtensions(StanzaIdElement::class.java)
            .firstOrNull { stanzaId -> stanzaId.by.toString().bareJid() == roomJid.bareJid() }
            ?.id
            .nonBlank()

    private fun directStableMessageId(
        message: Message,
        trustedBareJids: Set<String>,
    ): String? =
        message
            .getExtensions(StanzaIdElement::class.java)
            .firstOrNull { stanzaId -> stanzaId.by.toString().bareJid() in trustedBareJids }
            ?.id
            .nonBlank()

    private fun trustedDirectStanzaIdAssigners(
        message: Message,
        ownBareJid: String,
        peerJid: String,
    ): Set<String> =
        listOf(
            ownBareJid,
            peerJid,
            message.from?.toString()?.bareJid(),
            message.to?.toString()?.bareJid(),
        ).mapNotNull { it?.takeIf(String::isNotBlank) }
            .toSet()

    private fun Message.historySenderName(): String? {
        val fromValue = from?.toString() ?: return null
        return fromValue
            .substringAfter('/', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?: fromValue.substringBefore('@').takeIf { it.isNotBlank() }
    }

    private fun senderIdFor(
        message: Message,
        senderName: String?,
    ): String? {
        val currentUserId = accountUserId
        if (currentUserId != null && isOwnSender(senderName)) {
            return currentUserId
        }
        return message.from?.toString()
    }

    private fun displayNameFor(senderName: String?): String? =
        if (isOwnSender(senderName)) {
            accountUsername?.takeIf { it.isNotBlank() } ?: senderName
        } else {
            senderName
        }

    private fun isOwnSender(senderName: String?): Boolean {
        if (senderName == null) {
            return false
        }
        return senderName == mucNickname || senderName == accountUsername
    }

    private fun directSenderName(
        fromBare: String,
        ownBareJid: String,
        peerJid: String,
    ): String =
        if (fromBare == ownBareJid) {
            accountUsername ?: fromBare.substringBefore('@')
        } else {
            peerJid.substringBefore('@')
        }

    private fun fallbackMessageId(
        roomJid: String,
        createdAt: String,
        body: String,
    ): String = "$roomJid:$createdAt:${body.hashCode()}"

    private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

    private fun String.bareJid(): String = substringBefore('/')

    private fun String.xmppCodePointRangeToUtf16(
        start: Int,
        end: Int,
    ): Pair<Int, Int>? {
        if (end <= start) return null
        val codePointLength = codePointCount(0, length)
        val safeStart = start.coerceIn(0, codePointLength)
        val safeEnd = end.coerceIn(safeStart, codePointLength)
        return offsetByCodePoints(0, safeStart) to offsetByCodePoints(0, safeEnd)
    }

    private companion object {
        const val CALL_INVITES_NAMESPACE = "urn:xmpp:call-invites:0"
        const val CHAT_MARKERS_NAMESPACE = "urn:xmpp:chat-markers:0"
        const val CHAT_STATES_NAMESPACE = "http://jabber.org/protocol/chatstates"
        const val CORRECTION_NAMESPACE = "urn:xmpp:message-correct:0"
        const val EXPLICIT_MENTIONS_NAMESPACE = "urn:xmpp:emn:0"
        const val FALLBACK_NAMESPACE = "urn:xmpp:fallback:0"
        const val FASTEN_NAMESPACE = "urn:xmpp:fasten:0"
        const val FORUMS_NAMESPACE = "urn:xmpp:forums:0"
        const val HATS_NAMESPACE = "urn:xmpp:hats:0"
        const val MARKUP_NAMESPACE = "urn:xmpp:markup:0"
        const val ORIGIN_ID_NAMESPACE = "urn:xmpp:sid:0"
        const val FILE_METADATA_NAMESPACE = "urn:xmpp:file:metadata:0"
        const val FILE_SHARING_NAMESPACE = "urn:xmpp:sfs:0"
        const val MESSAGE_MODERATE_NAMESPACE = "urn:xmpp:message-moderate:0"
        const val ONLINE_MEETINGS_NAMESPACE = "urn:xmpp:http:online-meetings:invite:0"
        const val REACTIONS_NAMESPACE = "urn:xmpp:reactions:0"
        const val REFERENCE_NAMESPACE = "urn:xmpp:reference:0"
        const val REPLY_NAMESPACE = "urn:xmpp:reply:0"
        const val RETRACTION_NAMESPACE = "urn:xmpp:message-retract:1"
        const val STICKERS_NAMESPACE = "urn:xmpp:stickers:0"
        const val URL_DATA_NAMESPACE = "http://jabber.org/protocol/url-data"
        val CHAT_STATE_ELEMENTS =
            listOf(
                ChatStateElement("active", ChatState.Active),
                ChatStateElement("composing", ChatState.Composing),
                ChatStateElement("paused", ChatState.Paused),
                ChatStateElement("inactive", ChatState.Inactive),
                ChatStateElement("gone", ChatState.Gone),
            )
    }

    private data class ChatStateElement(
        val element: String,
        val state: ChatState,
    )
}
