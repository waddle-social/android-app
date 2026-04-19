package social.waddle.android.xmpp

import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.StandardExtensionElement
import org.jivesoftware.smack.packet.StanzaBuilder
import org.jivesoftware.smackx.sid.element.StanzaIdElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.jxmpp.jid.impl.JidCreate

class XmppMessageMapperTest {
    private val mapper =
        XmppMessageMapper(
            accountUserId = null,
            accountUsername = null,
            mucNickname = null,
        )

    @Test
    fun ignoresSubjectOnlyRoomMessages() {
        val message =
            StanzaBuilder
                .buildMessage("subject-1")
                .from(JidCreate.entityBareFrom(ROOM_JID))
                .ofType(Message.Type.groupchat)
                .setSubject("Welcome to $ROOM_JID!")
                .build()

        assertNull(mapper.liveRoomMessage(message, ROOM_JID))
    }

    @Test
    fun keepsBodyWhenRoomMessageAlsoHasSubject() {
        val message =
            StanzaBuilder
                .buildMessage("message-1")
                .from(JidCreate.entityFullFrom("$ROOM_JID/alice"))
                .ofType(Message.Type.groupchat)
                .setSubject("Room topic")
                .setBody("real message")
                .build()

        assertEquals("real message", mapper.liveRoomMessage(message, ROOM_JID)?.body)
    }

    @Test
    fun roomMessageUsesOnlyRoomAssignedStanzaIdAsServerId() {
        val message =
            StanzaBuilder
                .buildMessage("client-origin")
                .from(JidCreate.entityFullFrom("$ROOM_JID/alice"))
                .ofType(Message.Type.groupchat)
                .setBody("hello")
                .addExtension(StanzaIdElement("attacker-id", "mallory@example.test"))
                .addExtension(StanzaIdElement("room-id", ROOM_JID))
                .addExtension(XmppExtensions.originId("origin-id"))
                .build()

        val mapped = mapper.liveRoomMessage(message, ROOM_JID)

        assertEquals("room-id", mapped?.id)
        assertEquals("room-id", mapped?.serverId)
        assertEquals("origin-id", mapped?.originStanzaId)
    }

    @Test
    fun roomReplyFallbackIsStrippedFromBody() {
        val prefix = "> old message\n\n"
        val message =
            StanzaBuilder
                .buildMessage("reply-origin")
                .from(JidCreate.entityFullFrom("$ROOM_JID/alice"))
                .ofType(Message.Type.groupchat)
                .setBody(prefix + "new message")
                .addExtension(StanzaIdElement("room-reply-id", ROOM_JID))
                .addExtension(XmppExtensions.reply("room-parent-id", "$ROOM_JID/bob"))
                .addExtension(XmppExtensions.fallback("urn:xmpp:reply:0", 0, prefix.length))
                .build()

        val mapped = mapper.liveRoomMessage(message, ROOM_JID)

        assertEquals("new message", mapped?.body)
        assertEquals("room-parent-id", mapped?.replyToMessageId)
    }

    @Test
    fun directReplyFallbackIsStrippedFromBody() {
        val prefix = "> old direct\n\n"
        val message =
            StanzaBuilder
                .buildMessage("direct-origin")
                .from(JidCreate.entityFullFrom("$PEER_JID/phone"))
                .to(JidCreate.entityBareFrom(OWN_JID))
                .ofType(Message.Type.chat)
                .setBody(prefix + "new direct")
                .addExtension(XmppExtensions.reply("direct-parent-id", "$OWN_JID/android"))
                .addExtension(XmppExtensions.fallback("urn:xmpp:reply:0", 0, prefix.length))
                .build()

        val mapped = mapper.liveDirectMessage(message, OWN_JID)

        assertEquals("new direct", mapped?.body)
        assertEquals("direct-parent-id", mapped?.replyToMessageId)
    }

    @Test
    fun directMessageIgnoresUntrustedStanzaIds() {
        val message =
            StanzaBuilder
                .buildMessage("direct-origin")
                .from(JidCreate.entityFullFrom("$PEER_JID/phone"))
                .to(JidCreate.entityBareFrom(OWN_JID))
                .ofType(Message.Type.chat)
                .setBody("hello")
                .addExtension(StanzaIdElement("attacker-id", "mallory@example.test"))
                .addExtension(XmppExtensions.originId("origin-id"))
                .build()

        val mapped = mapper.liveDirectMessage(message, OWN_JID)

        assertEquals("origin-id", mapped?.id)
        assertNull(mapped?.serverId)
        assertEquals("origin-id", mapped?.originStanzaId)
    }

    @Test
    fun directMessageUsesTrustedAccountStanzaIdForDedupeOnly() {
        val message =
            StanzaBuilder
                .buildMessage("direct-origin")
                .from(JidCreate.entityFullFrom("$PEER_JID/phone"))
                .to(JidCreate.entityBareFrom(OWN_JID))
                .ofType(Message.Type.chat)
                .setBody("hello")
                .addExtension(StanzaIdElement("attacker-id", "mallory@example.test"))
                .addExtension(StanzaIdElement("trusted-id", PEER_JID))
                .addExtension(XmppExtensions.originId("origin-id"))
                .build()

        val mapped = mapper.liveDirectMessage(message, OWN_JID)

        assertEquals("trusted-id", mapped?.id)
        assertEquals("trusted-id", mapped?.serverId)
        assertEquals("origin-id", mapped?.originStanzaId)
    }

    @Test
    fun replyFallbackRangesUseXmppCodePointOffsets() {
        val prefix = "> 😀 old direct\n\n"
        val message =
            StanzaBuilder
                .buildMessage("direct-origin")
                .from(JidCreate.entityFullFrom("$PEER_JID/phone"))
                .to(JidCreate.entityBareFrom(OWN_JID))
                .ofType(Message.Type.chat)
                .setBody(prefix + "new direct")
                .addExtension(XmppExtensions.reply("direct-parent-id", "$OWN_JID/android"))
                .addExtension(XmppExtensions.fallback("urn:xmpp:reply:0", 0, prefix.codePointCount(0, prefix.length)))
                .build()

        val mapped = mapper.liveDirectMessage(message, OWN_JID)

        assertEquals("new direct", mapped?.body)
    }

    @Test
    fun parsesStandardMessageMarkupTagsWithCodePointOffsets() {
        val body = "😀bold"
        val markup =
            StandardExtensionElement
                .builder("markup", "urn:xmpp:markup:0")
                .addElement(
                    StandardExtensionElement
                        .builder("span", "urn:xmpp:markup:0")
                        .addAttribute("start", "1")
                        .addAttribute("end", "5")
                        .addElement(StandardExtensionElement.builder("strong", "urn:xmpp:markup:0").build())
                        .build(),
                ).build()
        val message =
            StanzaBuilder
                .buildMessage("message-1")
                .from(JidCreate.entityFullFrom("$ROOM_JID/alice"))
                .ofType(Message.Type.groupchat)
                .setBody(body)
                .addExtension(markup)
                .build()

        val mapped = mapper.liveRoomMessage(message, ROOM_JID)

        assertEquals(listOf(XmppMarkupRange(start = 2, end = 6, style = XmppMarkupStyle.BOLD)), mapped?.markupRanges)
    }

    @Test
    fun duplicateReactionsAreIgnored() {
        val message =
            StanzaBuilder
                .buildMessage("reaction-1")
                .from(JidCreate.entityFullFrom("$ROOM_JID/alice"))
                .ofType(Message.Type.groupchat)
                .addExtension(XmppExtensions.reaction("message-1", listOf("👍", "👍", "❤️")))
                .build()

        val mapped = mapper.liveRoomMessage(message, ROOM_JID)

        assertEquals(listOf("👍", "❤️"), mapped?.reactionEmojis)
    }

    private companion object {
        const val ROOM_JID = "room@muc.waddle.social"
        const val OWN_JID = "me@waddle.social"
        const val PEER_JID = "peer@waddle.social"
    }
}
