package social.waddle.android.xmpp

import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StandardExtensionElement
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
        val createdAt = Instant.now().toString()
        val senderName = message.historySenderName()
        return roomMessageFrom(
            message = message,
            roomJid = roomJid,
            createdAt = createdAt,
            serverId = liveMessageId(message),
            originStanzaId = message.stanzaId.nonBlank(),
            senderName = senderName,
        )
    }

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
            serverId = stableMessageId(result, message),
            originStanzaId = message.stanzaId.nonBlank(),
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
            createdAt = Instant.now().toString(),
            serverId = liveMessageId(message),
            originStanzaId = message.stanzaId.nonBlank(),
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
            serverId = stableMessageId(result, message),
            originStanzaId = message.stanzaId.nonBlank(),
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
            serverId = stableMessageId(result, message),
            originStanzaId = message.stanzaId.nonBlank(),
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun roomMessageFrom(
        message: Message,
        roomJid: String,
        createdAt: String,
        serverId: String?,
        originStanzaId: String?,
        senderName: String?,
    ): XmppHistoryMessage? {
        message.displayedTargetId()?.let { displayedId ->
            return XmppHistoryMessage(
                id = serverId ?: fallbackMessageId(roomJid, createdAt, "displayed:$displayedId"),
                serverId = serverId,
                originStanzaId = originStanzaId,
                roomJid = roomJid,
                senderId = senderIdFor(message, senderName),
                senderName = displayNameFor(senderName),
                body = "",
                createdAt = createdAt,
                displayedId = displayedId,
            )
        }

        message.chatState()?.let { chatState ->
            return XmppHistoryMessage(
                id = serverId ?: fallbackMessageId(roomJid, createdAt, "chat-state:$chatState"),
                serverId = serverId,
                originStanzaId = originStanzaId,
                roomJid = roomJid,
                senderId = senderIdFor(message, senderName),
                senderName = displayNameFor(senderName),
                body = "",
                createdAt = createdAt,
                chatState = chatState,
            )
        }

        message.reactionUpdate()?.let { reaction ->
            return XmppHistoryMessage(
                id = serverId ?: fallbackMessageId(roomJid, createdAt, "reaction:${reaction.targetId}"),
                serverId = serverId,
                originStanzaId = originStanzaId,
                roomJid = roomJid,
                senderId = senderIdFor(message, senderName),
                senderName = displayNameFor(senderName),
                body = "",
                createdAt = createdAt,
                reactionTargetId = reaction.targetId,
                reactionEmojis = reaction.emojis,
            )
        }

        message.retractionTargetId()?.let { retractsId ->
            return XmppHistoryMessage(
                id = serverId ?: fallbackMessageId(roomJid, createdAt, "retract:$retractsId"),
                serverId = serverId,
                originStanzaId = originStanzaId,
                roomJid = roomJid,
                senderId = senderIdFor(message, senderName),
                senderName = displayNameFor(senderName),
                body = "",
                createdAt = createdAt,
                retractsId = retractsId,
            )
        }

        val sharedFile = message.sharedFile()
        val callInvite = message.callInvite(serverId ?: message.stanzaId ?: fallbackMessageId(roomJid, createdAt, "call"))
        val body =
            message.body?.takeIf { it.isNotBlank() }
                ?: sharedFile?.description?.takeIf { it.isNotBlank() }
                ?: sharedFile?.name?.takeIf { it.isNotBlank() }
                ?: sharedFile?.url?.takeIf { it.isNotBlank() }
                ?: callInvite?.meetingDescription?.takeIf { it.isNotBlank() }
                ?: callInvite?.externalUri?.takeIf { it.isNotBlank() }
                ?: return null
        return XmppHistoryMessage(
            id = serverId ?: fallbackMessageId(roomJid, createdAt, body),
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
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun directMessageFrom(
        message: Message,
        ownBareJid: String,
        peerJid: String,
        createdAt: String,
        serverId: String?,
        originStanzaId: String?,
    ): XmppDirectMessage? {
        val fromBare = message.from?.toString()?.bareJid() ?: return null
        message.displayedTargetId()?.let { displayedId ->
            return XmppDirectMessage(
                id = serverId ?: fallbackMessageId(peerJid, createdAt, "dm-displayed:$displayedId"),
                serverId = serverId,
                originStanzaId = originStanzaId,
                peerJid = peerJid,
                fromJid = fromBare,
                senderName = directSenderName(fromBare, ownBareJid, peerJid),
                body = "",
                createdAt = createdAt,
                displayedId = displayedId,
            )
        }

        message.chatState()?.let { chatState ->
            return XmppDirectMessage(
                id = serverId ?: fallbackMessageId(peerJid, createdAt, "dm-chat-state:$chatState"),
                serverId = serverId,
                originStanzaId = originStanzaId,
                peerJid = peerJid,
                fromJid = fromBare,
                senderName = directSenderName(fromBare, ownBareJid, peerJid),
                body = "",
                createdAt = createdAt,
                chatState = chatState,
            )
        }

        message.reactionUpdate()?.let { reaction ->
            return XmppDirectMessage(
                id = serverId ?: fallbackMessageId(peerJid, createdAt, "dm-reaction:${reaction.targetId}"),
                serverId = serverId,
                originStanzaId = originStanzaId,
                peerJid = peerJid,
                fromJid = fromBare,
                senderName = directSenderName(fromBare, ownBareJid, peerJid),
                body = "",
                createdAt = createdAt,
                reactionTargetId = reaction.targetId,
                reactionEmojis = reaction.emojis,
            )
        }

        message.retractionTargetId()?.let { retractsId ->
            return XmppDirectMessage(
                id = serverId ?: fallbackMessageId(peerJid, createdAt, "dm-retract:$retractsId"),
                serverId = serverId,
                originStanzaId = originStanzaId,
                peerJid = peerJid,
                fromJid = fromBare,
                senderName = directSenderName(fromBare, ownBareJid, peerJid),
                body = "",
                createdAt = createdAt,
                retractsId = retractsId,
            )
        }

        val sharedFile = message.sharedFile()
        val callInvite = message.callInvite(serverId ?: message.stanzaId ?: fallbackMessageId(peerJid, createdAt, "dm-call"))
        val body =
            message.body?.takeIf { it.isNotBlank() }
                ?: sharedFile?.description?.takeIf { it.isNotBlank() }
                ?: sharedFile?.name?.takeIf { it.isNotBlank() }
                ?: sharedFile?.url?.takeIf { it.isNotBlank() }
                ?: callInvite?.meetingDescription?.takeIf { it.isNotBlank() }
                ?: callInvite?.externalUri?.takeIf { it.isNotBlank() }
                ?: return null
        return XmppDirectMessage(
            id = serverId ?: fallbackMessageId(peerJid, createdAt, body),
            serverId = serverId,
            originStanzaId = originStanzaId,
            peerJid = peerJid,
            fromJid = fromBare,
            senderName = directSenderName(fromBare, ownBareJid, peerJid),
            body = body,
            createdAt = createdAt,
            mentions = message.mentionUris(),
            broadcastMention = message.broadcastMention(),
            sharedFile = sharedFile,
            isSticker = message.isSticker(),
            callInvite = callInvite,
            replacesId = message.correctionTargetId(),
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

    private fun stableMessageId(
        result: MamElements.MamResultExtension,
        message: Message,
    ): String? =
        message
            .getExtensions(StanzaIdElement::class.java)
            .firstOrNull()
            ?.id
            .nonBlank()
            ?: result.id.nonBlank()
            ?: message.stanzaId.nonBlank()

    private fun liveMessageId(message: Message): String? {
        val stableId =
            message
                .getExtensions(StanzaIdElement::class.java)
                .firstOrNull()
                ?.id
                .nonBlank()
        return stableId ?: message.stanzaId.nonBlank()
    }

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

    private companion object {
        const val CALL_INVITES_NAMESPACE = "urn:xmpp:call-invites:0"
        const val CHAT_MARKERS_NAMESPACE = "urn:xmpp:chat-markers:0"
        const val CHAT_STATES_NAMESPACE = "http://jabber.org/protocol/chatstates"
        const val CORRECTION_NAMESPACE = "urn:xmpp:message-correct:0"
        const val EXPLICIT_MENTIONS_NAMESPACE = "urn:xmpp:emn:0"
        const val FASTEN_NAMESPACE = "urn:xmpp:fasten:0"
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
